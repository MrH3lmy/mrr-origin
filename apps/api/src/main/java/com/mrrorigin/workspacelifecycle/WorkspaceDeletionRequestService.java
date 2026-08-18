package com.mrrorigin.workspacelifecycle;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.mrrorigin.attribution.AttributionWorkspaceDataDeletionService;
import com.mrrorigin.billing.BillingWorkspaceDataDeletionService;
import com.mrrorigin.billing.StripeConnectionService;
import com.mrrorigin.identity.IdentityWorkspaceDataDeletionService;
import com.mrrorigin.notification.NotificationWorkspaceDataDeletionService;
import com.mrrorigin.reporting.ReportingWorkspaceDataDeletionService;
import com.mrrorigin.revenue.RevenueWorkspaceDataDeletionService;
import com.mrrorigin.tracking.IngestionKeyService;
import com.mrrorigin.tracking.TrackingWorkspaceDataDeletionService;

/**
 * Orchestrates #62's owner-only, resumable, cross-module workspace deletion. Generalizes
 * {@code ProjectDataDeletionService}'s (#8) fetch-a-bounded-slice/apply/advance-checkpoint shape to a
 * run whose "slices" are entire module-owned deletion sweeps rather than single-table batches -- see
 * {@link WorkspaceDeletionPhase} for the phase order and why it is dependency-safe.
 *
 * <p><b>Resumability and idempotency.</b> {@link #runBatch} processes at most one phase's worth of
 * work per call (one module's {@code deleteBatch}, or the fixed admission/root-delete step), advancing
 * the phase only once that phase reports exhaustion. A crash before the transaction commits leaves the
 * run's phase/counters exactly where they were; the next call re-evaluates the same phase and resumes.
 * Every module's {@code deleteBatch} is itself idempotent (unconditional bounded deletes, safe to
 * repeat), and the admission step's three actions (mark {@code DELETING}, revoke keys, disable Stripe
 * sync) are each independently idempotent, so replaying any phase after an interruption is harmless.
 *
 * <p><b>Concurrency protection.</b> The request row is read with {@code SELECT ... FOR UPDATE} before
 * any work happens, so concurrent calls for the same workspace serialize on that row rather than
 * double-processing the same phase -- the same row-lock-as-lease pattern
 * {@code ProjectDataDeletionService} uses, which is multi-instance-safe because Postgres row locks are
 * visible to every API instance sharing the database, not just the process that acquired them.
 *
 * <p><b>One request per workspace.</b> {@code workspace_deletion_requests.workspace_id} is
 * database-unique; {@link #createOrGetRequest} always validates the confirmation string first, then
 * either creates the one lifetime row for this workspace (running its admission step synchronously, in
 * the same call, per the accepted "mark the workspace DELETING first" ordering) or returns the
 * already-existing request's current progress instead of starting duplicate work.
 *
 * <p><b>Why {@code projects} and {@code workspace_members} are never their own phase.</b> Both cascade
 * from {@code workspaces} ({@code ON DELETE CASCADE}), and nothing else restricts deleting them by the
 * time every earlier phase has run. Sweeping them explicitly, before deleting the workspace row itself,
 * would require the same owner-authorization check the {@code WORKSPACE_ROOT} phase performs -- except
 * a membership row it just deleted would then make every later call in the same run unable to
 * re-authenticate. Deleting {@code workspaces} directly lets Postgres cascade both in the same atomic
 * statement as the one authorized call that starts it.
 */
@Service
public class WorkspaceDeletionRequestService {

    private static final int ADMISSION_STEP_ROWS = 0;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final IngestionKeyService ingestionKeys;
    private final StripeConnectionService stripeConnections;
    private final ReportingWorkspaceDataDeletionService reporting;
    private final AttributionWorkspaceDataDeletionService attribution;
    private final IdentityWorkspaceDataDeletionService identity;
    private final TrackingWorkspaceDataDeletionService tracking;
    private final BillingWorkspaceDataDeletionService billing;
    private final RevenueWorkspaceDataDeletionService revenue;
    private final NotificationWorkspaceDataDeletionService notification;

    WorkspaceDeletionRequestService(
            JdbcClient jdbc,
            Clock clock,
            IngestionKeyService ingestionKeys,
            StripeConnectionService stripeConnections,
            ReportingWorkspaceDataDeletionService reporting,
            AttributionWorkspaceDataDeletionService attribution,
            IdentityWorkspaceDataDeletionService identity,
            TrackingWorkspaceDataDeletionService tracking,
            BillingWorkspaceDataDeletionService billing,
            RevenueWorkspaceDataDeletionService revenue,
            NotificationWorkspaceDataDeletionService notification) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.ingestionKeys = ingestionKeys;
        this.stripeConnections = stripeConnections;
        this.reporting = reporting;
        this.attribution = attribution;
        this.identity = identity;
        this.tracking = tracking;
        this.billing = billing;
        this.revenue = revenue;
        this.notification = notification;
    }

    /**
     * Validates the exact confirmation string {@code "DELETE <workspaceId>"}, then either starts a new
     * deletion run (performing its admission step synchronously, in this same call) or returns the
     * existing run's current progress if one is already underway or complete. The confirmation is
     * checked on every call, including retries, before any existing-request short-circuit -- a wrong
     * confirmation is always rejected even if a valid request already exists.
     */
    @Transactional
    public DeletionRunOutcome createOrGetRequest(UUID workspaceId, String confirmation) {
        String expected = "DELETE " + workspaceId;
        if (confirmation == null || !confirmation.equals(expected)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Confirmation must be exactly \"DELETE <workspaceId>\"");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        boolean created = jdbc.sql("""
                        INSERT INTO workspace_deletion_requests
                            (id, workspace_id, status, phase, rows_deleted, requested_at, updated_at)
                        VALUES (:id, :workspaceId, 'RUNNING', 'ADMISSION', 0, :now, :now)
                        ON CONFLICT (workspace_id) DO NOTHING
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("now", now)
                .update()
                == 1;
        Run run = loadForUpdate(workspaceId).orElseThrow();
        if (created) {
            return processCurrentPhase(run, workspaceId, ADMISSION_STEP_ROWS);
        }
        return outcomeFrom(run);
    }

    /** Processes at most one phase's worth of work for an existing run. A no-op once COMPLETED. */
    @Transactional
    public DeletionRunOutcome runBatch(UUID workspaceId, int maxRows) {
        if (maxRows <= 0) {
            throw new IllegalArgumentException("maxRows must be positive");
        }
        Run run = loadForUpdate(workspaceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No deletion request for this workspace"));
        if (run.status().equals("COMPLETED")) {
            return outcomeFrom(run);
        }
        return processCurrentPhase(run, workspaceId, maxRows);
    }

    @Transactional(readOnly = true)
    public Optional<DeletionRunOutcome> status(UUID workspaceId) {
        return find(workspaceId).map(this::outcomeFrom);
    }

    private DeletionRunOutcome processCurrentPhase(Run run, UUID workspaceId, int maxRows) {
        WorkspaceDeletionPhase phase = WorkspaceDeletionPhase.valueOf(run.phase());
        PhaseResult result = process(phase, workspaceId, maxRows);
        WorkspaceDeletionPhase nextPhase = result.exhausted() ? phase.next() : phase;
        boolean complete = nextPhase == WorkspaceDeletionPhase.DONE;
        long rowsDeleted = run.rowsDeleted() + result.deleted();
        updateRun(run.id(), nextPhase, rowsDeleted, complete);
        return new DeletionRunOutcome(nextPhase.name(), result.deleted(), rowsDeleted, complete);
    }

    private PhaseResult process(WorkspaceDeletionPhase phase, UUID workspaceId, int maxRows) {
        return switch (phase) {
            case ADMISSION -> admit(workspaceId);
            case REPORTING -> {
                var batch = reporting.deleteBatch(workspaceId, maxRows);
                yield new PhaseResult(batch.rowsDeleted(), batch.exhausted());
            }
            case ATTRIBUTION -> {
                var batch = attribution.deleteBatch(workspaceId, maxRows);
                yield new PhaseResult(batch.rowsDeleted(), batch.exhausted());
            }
            case IDENTITY -> {
                var batch = identity.deleteBatch(workspaceId, maxRows);
                yield new PhaseResult(batch.rowsDeleted(), batch.exhausted());
            }
            case TRACKING -> {
                var batch = tracking.deleteBatch(workspaceId, maxRows);
                yield new PhaseResult(batch.rowsDeleted(), batch.exhausted());
            }
            case BILLING -> {
                var batch = billing.deleteBatch(workspaceId, maxRows);
                yield new PhaseResult(batch.rowsDeleted(), batch.exhausted());
            }
            case REVENUE -> {
                var batch = revenue.deleteBatch(workspaceId, maxRows);
                yield new PhaseResult(batch.rowsDeleted(), batch.exhausted());
            }
            case NOTIFICATION -> {
                var batch = notification.deleteBatch(workspaceId, maxRows);
                yield new PhaseResult(batch.rowsDeleted(), batch.exhausted());
            }
            case WORKSPACE_ROOT -> deleteWorkspaceRoot(workspaceId);
            case DONE -> new PhaseResult(0, true);
        };
    }

    /** Marks the workspace DELETING, revokes ingestion keys, and disables Stripe sync. Idempotent. */
    private PhaseResult admit(UUID workspaceId) {
        jdbc.sql("UPDATE workspaces SET status = 'DELETING' WHERE id = :id AND status = 'ACTIVE'")
                .param("id", workspaceId)
                .update();
        ingestionKeys.revokeAllForWorkspace(workspaceId);
        stripeConnections.disableSyncForDeletion(workspaceId);
        return new PhaseResult(0, true);
    }

    /** Deletes the workspace row itself, cascading its last remaining projects and members. */
    private PhaseResult deleteWorkspaceRoot(UUID workspaceId) {
        int deleted = jdbc.sql("DELETE FROM workspaces WHERE id = :id").param("id", workspaceId).update();
        return new PhaseResult(deleted, true);
    }

    private Optional<Run> loadForUpdate(UUID workspaceId) {
        return jdbc.sql("""
                        SELECT id, status, phase, rows_deleted
                        FROM workspace_deletion_requests
                        WHERE workspace_id = :workspaceId
                        FOR UPDATE
                        """)
                .param("workspaceId", workspaceId)
                .query((rs, rowNum) -> new Run(
                        rs.getObject("id", UUID.class), rs.getString("status"), rs.getString("phase"), rs.getLong("rows_deleted")))
                .optional();
    }

    private Optional<Run> find(UUID workspaceId) {
        return jdbc.sql("""
                        SELECT id, status, phase, rows_deleted
                        FROM workspace_deletion_requests
                        WHERE workspace_id = :workspaceId
                        """)
                .param("workspaceId", workspaceId)
                .query((rs, rowNum) -> new Run(
                        rs.getObject("id", UUID.class), rs.getString("status"), rs.getString("phase"), rs.getLong("rows_deleted")))
                .optional();
    }

    private void updateRun(UUID id, WorkspaceDeletionPhase phase, long rowsDeleted, boolean complete) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        jdbc.sql("""
                        UPDATE workspace_deletion_requests
                        SET phase = :phase, rows_deleted = :rowsDeleted, updated_at = :now,
                            status = (CASE WHEN :complete THEN 'COMPLETED' ELSE 'RUNNING' END),
                            completed_at = (CASE WHEN :complete THEN :now ELSE NULL END)
                        WHERE id = :id
                        """)
                .param("phase", phase.name())
                .param("rowsDeleted", rowsDeleted)
                .param("now", now)
                .param("complete", complete)
                .param("id", id)
                .update();
    }

    private DeletionRunOutcome outcomeFrom(Run run) {
        return new DeletionRunOutcome(run.phase(), 0, run.rowsDeleted(), run.status().equals("COMPLETED"));
    }

    private record Run(UUID id, String status, String phase, long rowsDeleted) {}

    private record PhaseResult(int deleted, boolean exhausted) {}

    public record DeletionRunOutcome(String phase, int rowsDeletedThisBatch, long totalRowsDeleted, boolean complete) {}
}
