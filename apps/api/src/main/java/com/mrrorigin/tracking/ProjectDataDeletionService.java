package com.mrrorigin.tracking;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mrrorigin.identity.IdentityLinkingService;

/**
 * Resumable, checkpointed, full project tracking-data deletion (#8) -- the same
 * fetch-a-bounded-slice/apply/advance-checkpoint shape {@code StripeBackfillService} (#12) and {@code
 * AttributionRecalculationService} (#19) use, adapted to a job that sweeps several tables in a fixed
 * dependency order ({@link ProjectDataDeletionPhase}) instead of one paginated resource. See V16's
 * migration comment for the full phase list and why touchpoints (and, transitively, the sessions and
 * visitors that would otherwise cascade through them) still referenced by {@code
 * customer_attribution_results} -- and external identities still linked to a Stripe customer via
 * {@code stripe_customer_links} -- are skipped rather than force-deleted.
 *
 * <p><b>Resumability and idempotency.</b> {@link #runBatch} processes at most {@code maxRows} rows of
 * the run's current phase in one transaction, advancing the phase only once that phase reports fewer
 * than {@code maxRows} deleted (i.e. exhausted). A crash or exception before the transaction commits
 * leaves the run's phase/counters exactly where they were; the next call re-evaluates the same phase
 * and resumes. Every delete is an unconditional (no cutoff) bounded {@code DELETE ... LIMIT}, so
 * reapplying a phase that partially succeeded before an interruption is harmless. Calling {@link
 * #runBatch} again once {@code status = COMPLETED} is a no-op that returns the run's final counts.
 *
 * <p><b>Concurrency protection.</b> The run row is read with {@code SELECT ... FOR UPDATE} before any
 * work happens, so two concurrent {@link #runBatch} calls for the same project serialize on that row
 * rather than double-processing the same slice.
 */
@Service
public class ProjectDataDeletionService {

    private final JdbcClient jdbc;
    private final IdentityLinkingService identities;
    private final Clock clock;

    ProjectDataDeletionService(JdbcClient jdbc, IdentityLinkingService identities, Clock clock) {
        this.jdbc = jdbc;
        this.identities = identities;
        this.clock = clock;
    }

    @Transactional
    public DeletionRunOutcome runBatch(UUID workspaceId, UUID projectId, int maxRows) {
        if (maxRows <= 0) {
            throw new IllegalArgumentException("maxRows must be positive");
        }
        Run run = loadOrCreateRunForUpdate(workspaceId, projectId);
        if (run.status().equals("COMPLETED")) {
            return outcomeFrom(run);
        }
        ProjectDataDeletionPhase phase = ProjectDataDeletionPhase.valueOf(run.phase());
        PhaseResult result = process(phase, workspaceId, projectId, maxRows);
        ProjectDataDeletionPhase nextPhase = result.exhausted() ? phase.next() : phase;
        boolean complete = nextPhase == ProjectDataDeletionPhase.DONE;
        long rowsDeleted = run.rowsDeleted() + result.deleted();
        long skippedEvidenceRows = run.skippedEvidenceRows() + result.newlySkipped();
        updateRun(run.id(), nextPhase, rowsDeleted, skippedEvidenceRows, complete);
        return new DeletionRunOutcome(nextPhase.name(), result.deleted(), rowsDeleted, skippedEvidenceRows, complete);
    }

    /** Resets a COMPLETED run to sweep again from the beginning, e.g. because the project received new tracking data. */
    @Transactional
    public void restart(UUID workspaceId, UUID projectId) {
        Run run = loadOrCreateRunForUpdate(workspaceId, projectId);
        if (!run.status().equals("COMPLETED")) {
            throw new IllegalStateException("project data deletion run is still in progress");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        jdbc.sql("""
                        UPDATE project_data_deletion_runs
                        SET status = 'RUNNING', phase = 'EVENTS', rows_deleted = 0, skipped_evidence_rows = 0,
                            updated_at = :now, completed_at = NULL
                        WHERE id = :id
                        """)
                .param("now", now)
                .param("id", run.id())
                .update();
    }

    @Transactional(readOnly = true)
    public Optional<DeletionRunOutcome> status(UUID workspaceId, UUID projectId) {
        return findRun(workspaceId, projectId).map(this::outcomeFrom);
    }

    private PhaseResult process(ProjectDataDeletionPhase phase, UUID workspaceId, UUID projectId, int maxRows) {
        return switch (phase) {
            case EVENTS -> unconditional("tracking_event_envelopes", workspaceId, projectId, maxRows);
            case BATCHES -> deleteOrphanedBatches(workspaceId, projectId, maxRows);
            case FAILURE_DIAGNOSTICS -> unconditional("tracking_ingestion_failures", workspaceId, projectId, maxRows);
            case VERIFICATION -> unconditional("tracking_verification_attempts", workspaceId, projectId, maxRows);
            case IDENTITY -> deleteIdentity(workspaceId, projectId, maxRows);
            case TOUCHPOINTS -> deleteTouchpoints(workspaceId, projectId, maxRows);
            case SESSIONS -> deleteSessions(workspaceId, projectId, maxRows);
            case VISITORS -> deleteVisitors(workspaceId, projectId, maxRows);
            case DONE -> new PhaseResult(0, true, 0);
        };
    }

    private PhaseResult unconditional(String table, UUID workspaceId, UUID projectId, int maxRows) {
        int deleted = jdbc.sql("""
                        DELETE FROM %s
                        WHERE id IN (
                            SELECT id FROM %s
                            WHERE workspace_id = :workspaceId AND project_id = :projectId
                            LIMIT :maxRows
                        )
                        """.formatted(table, table))
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("maxRows", maxRows)
                .update();
        return new PhaseResult(deleted, deleted < maxRows, 0);
    }

    private PhaseResult deleteOrphanedBatches(UUID workspaceId, UUID projectId, int maxRows) {
        int deleted = jdbc.sql("""
                        DELETE FROM tracking_ingestion_batches
                        WHERE id IN (
                            SELECT b.id FROM tracking_ingestion_batches b
                            WHERE b.workspace_id = :workspaceId AND b.project_id = :projectId
                              AND NOT EXISTS (SELECT 1 FROM tracking_event_envelopes e WHERE e.ingestion_batch_id = b.id)
                            LIMIT :maxRows
                        )
                        """)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("maxRows", maxRows)
                .update();
        return new PhaseResult(deleted, deleted < maxRows, 0);
    }

    private PhaseResult deleteIdentity(UUID workspaceId, UUID projectId, int maxRows) {
        IdentityLinkingService.IdentityDeletionBatch batch = identities.deleteIdentityDataBatch(workspaceId, projectId, maxRows);
        boolean exhausted = batch.aliasesDeleted() < maxRows && batch.identitiesDeleted() < maxRows;
        long newlySkipped = exhausted
                ? identities.countProtectedAliases(workspaceId, projectId) + identities.countProtectedIdentities(workspaceId, projectId)
                : 0;
        return new PhaseResult(batch.totalDeleted(), exhausted, newlySkipped);
    }

    private PhaseResult deleteTouchpoints(UUID workspaceId, UUID projectId, int maxRows) {
        int deleted = jdbc.sql("""
                        DELETE FROM touchpoints
                        WHERE id IN (
                            SELECT tp.id FROM touchpoints tp
                            WHERE tp.workspace_id = :workspaceId AND tp.project_id = :projectId
                              AND NOT EXISTS (
                                  SELECT 1 FROM customer_attribution_results r
                                  WHERE r.workspace_id = tp.workspace_id
                                    AND (r.first_touchpoint_id = tp.id OR r.last_touchpoint_id = tp.id))
                            LIMIT :maxRows
                        )
                        """)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("maxRows", maxRows)
                .update();
        boolean exhausted = deleted < maxRows;
        long newlySkipped = exhausted ? countProtectedTouchpoints(workspaceId, projectId) : 0;
        return new PhaseResult(deleted, exhausted, newlySkipped);
    }

    private long countProtectedTouchpoints(UUID workspaceId, UUID projectId) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM touchpoints tp
                        WHERE tp.workspace_id = :workspaceId AND tp.project_id = :projectId
                          AND EXISTS (
                              SELECT 1 FROM customer_attribution_results r
                              WHERE r.workspace_id = tp.workspace_id
                                AND (r.first_touchpoint_id = tp.id OR r.last_touchpoint_id = tp.id))
                        """)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .query(Long.class)
                .single();
    }

    private PhaseResult deleteSessions(UUID workspaceId, UUID projectId, int maxRows) {
        int deleted = jdbc.sql("""
                        DELETE FROM tracking_sessions
                        WHERE id IN (
                            SELECT s.id FROM tracking_sessions s
                            WHERE s.workspace_id = :workspaceId AND s.project_id = :projectId
                              AND NOT EXISTS (SELECT 1 FROM touchpoints tp WHERE tp.session_id = s.id)
                              AND NOT EXISTS (SELECT 1 FROM tracking_event_envelopes e WHERE e.session_id = s.id)
                            LIMIT :maxRows
                        )
                        """)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("maxRows", maxRows)
                .update();
        return new PhaseResult(deleted, deleted < maxRows, 0);
    }

    private PhaseResult deleteVisitors(UUID workspaceId, UUID projectId, int maxRows) {
        int deleted = jdbc.sql("""
                        DELETE FROM visitors
                        WHERE id IN (
                            SELECT v.id FROM visitors v
                            WHERE v.workspace_id = :workspaceId AND v.project_id = :projectId
                              AND NOT EXISTS (SELECT 1 FROM touchpoints tp WHERE tp.visitor_id = v.id)
                              AND NOT EXISTS (SELECT 1 FROM tracking_sessions s WHERE s.visitor_id = v.id)
                              AND NOT EXISTS (SELECT 1 FROM tracking_event_envelopes e WHERE e.visitor_id = v.id)
                              AND NOT EXISTS (SELECT 1 FROM visitor_aliases a WHERE a.visitor_id = v.id)
                            LIMIT :maxRows
                        )
                        """)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("maxRows", maxRows)
                .update();
        return new PhaseResult(deleted, deleted < maxRows, 0);
    }

    private Run loadOrCreateRunForUpdate(UUID workspaceId, UUID projectId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        jdbc.sql("""
                        INSERT INTO project_data_deletion_runs
                            (id, workspace_id, project_id, status, phase, rows_deleted, skipped_evidence_rows,
                             started_at, updated_at)
                        VALUES (:id, :workspaceId, :projectId, 'RUNNING', 'EVENTS', 0, 0, :now, :now)
                        ON CONFLICT (project_id) DO NOTHING
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("now", now)
                .update();
        return jdbc.sql("""
                        SELECT id, status, phase, rows_deleted, skipped_evidence_rows
                        FROM project_data_deletion_runs
                        WHERE workspace_id = :workspaceId AND project_id = :projectId
                        FOR UPDATE
                        """)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .query((rs, rowNum) -> new Run(
                        rs.getObject("id", UUID.class), rs.getString("status"), rs.getString("phase"),
                        rs.getLong("rows_deleted"), rs.getLong("skipped_evidence_rows")))
                .single();
    }

    private Optional<Run> findRun(UUID workspaceId, UUID projectId) {
        return jdbc.sql("""
                        SELECT id, status, phase, rows_deleted, skipped_evidence_rows
                        FROM project_data_deletion_runs
                        WHERE workspace_id = :workspaceId AND project_id = :projectId
                        """)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .query((rs, rowNum) -> new Run(
                        rs.getObject("id", UUID.class), rs.getString("status"), rs.getString("phase"),
                        rs.getLong("rows_deleted"), rs.getLong("skipped_evidence_rows")))
                .optional();
    }

    private void updateRun(
            UUID id, ProjectDataDeletionPhase phase, long rowsDeleted, long skippedEvidenceRows, boolean complete) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        jdbc.sql("""
                        UPDATE project_data_deletion_runs
                        SET phase = :phase, rows_deleted = :rowsDeleted, skipped_evidence_rows = :skipped,
                            updated_at = :now,
                            status = (CASE WHEN :complete THEN 'COMPLETED' ELSE 'RUNNING' END),
                            completed_at = (CASE WHEN :complete THEN :now ELSE NULL END)
                        WHERE id = :id
                        """)
                .param("phase", phase.name())
                .param("rowsDeleted", rowsDeleted)
                .param("skipped", skippedEvidenceRows)
                .param("now", now)
                .param("complete", complete)
                .param("id", id)
                .update();
    }

    private DeletionRunOutcome outcomeFrom(Run run) {
        return new DeletionRunOutcome(run.phase(), 0, run.rowsDeleted(), run.skippedEvidenceRows(), run.status().equals("COMPLETED"));
    }

    private record Run(UUID id, String status, String phase, long rowsDeleted, long skippedEvidenceRows) {}

    private record PhaseResult(int deleted, boolean exhausted, long newlySkipped) {}

    public record DeletionRunOutcome(
            String phase, int rowsDeletedThisBatch, long totalRowsDeleted, long skippedEvidenceRows, boolean complete) {}
}
