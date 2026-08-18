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
 * <p><b>Concurrency protection.</b> The run row is read with {@code SELECT ... FOR UPDATE} before any
 * work happens, so concurrent calls for the same workspace serialize on that row rather than
 * double-processing the same phase -- the same row-lock-as-lease pattern
 * {@code ProjectDataDeletionService} uses, which is multi-instance-safe because Postgres row locks are
 * visible to every API instance sharing the database, not just the process that acquired them.
 *
 * <p><b>One request per workspace, correct across completion.</b> {@code workspace_deletion_runs.workspace_id}
 * is database-unique, so a retry while a run is still {@code RUNNING} finds the same row instead of
 * starting duplicate work. Once a run completes, its row is replaced -- in the same transaction -- by
 * a {@code workspace_deletion_tombstones} row (see {@link #deleteWorkspaceRoot}); the run row no
 * longer exists to be found. Both {@link #createOrGetRequest} and {@link #status} therefore check the
 * tombstone table whenever no run row is found, so a retry (or a status query) against an
 * already-completed deletion correctly reports {@code COMPLETED} instead of either erroring or -- far
 * worse -- silently starting a brand new run for a workspace whose data is already gone.
 *
 * <p><b>Why {@code projects} and {@code workspace_members} are never their own phase.</b> Both cascade
 * from {@code workspaces} ({@code ON DELETE CASCADE}), and nothing else restricts deleting them by the
 * time every earlier phase has run. Sweeping them explicitly, before deleting the workspace row itself,
 * would require the same owner-authorization check the {@code WORKSPACE_ROOT} phase performs -- except
 * a membership row it just deleted would then make every later call in the same run unable to
 * re-authenticate. Deleting {@code workspaces} directly lets Postgres cascade both (and this run's own
 * checkpoint row) in the same atomic statement as the one authorized call that starts it.
 */
@Service
public class WorkspaceDeletionRequestService {

    private static final int ADMISSION_STEP_ROWS = 0;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final IngestionKeyService ingestionKeys;
    private final StripeConnectionService stripeConnections;
    private final NotificationWorkspaceDataDeletionService notification;
    private final ReportingWorkspaceDataDeletionService reporting;
    private final AttributionWorkspaceDataDeletionService attribution;
    private final RevenueWorkspaceDataDeletionService revenue;
    private final IdentityWorkspaceDataDeletionService identity;
    private final TrackingWorkspaceDataDeletionService tracking;
    private final BillingWorkspaceDataDeletionService billing;

    WorkspaceDeletionRequestService(
            JdbcClient jdbc,
            Clock clock,
            IngestionKeyService ingestionKeys,
            StripeConnectionService stripeConnections,
            NotificationWorkspaceDataDeletionService notification,
            ReportingWorkspaceDataDeletionService reporting,
            AttributionWorkspaceDataDeletionService attribution,
            RevenueWorkspaceDataDeletionService revenue,
            IdentityWorkspaceDataDeletionService identity,
            TrackingWorkspaceDataDeletionService tracking,
            BillingWorkspaceDataDeletionService billing) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.ingestionKeys = ingestionKeys;
        this.stripeConnections = stripeConnections;
        this.notification = notification;
        this.reporting = reporting;
        this.attribution = attribution;
        this.revenue = revenue;
        this.identity = identity;
        this.tracking = tracking;
        this.billing = billing;
    }

    /**
     * Validates the exact confirmation string {@code "DELETE <workspaceId>"}, then either starts a new
     * deletion run (performing its admission step synchronously, in this same call) or returns the
     * current outcome of an already-existing run or an already-written tombstone. The confirmation is
     * checked on every call, including retries, before any existing-request short-circuit -- a wrong
     * confirmation is always rejected even if a valid request already exists or already completed.
     */
    @Transactional
    public DeletionRunOutcome createOrGetRequest(UUID workspaceId, String confirmation) {
        String expected = "DELETE " + workspaceId;
        if (confirmation == null || !confirmation.equals(expected)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Confirmation must be exactly \"DELETE <workspaceId>\"");
        }
        Optional<DeletionRunOutcome> tombstoned = tombstoneOutcome(workspaceId);
        if (tombstoned.isPresent()) {
            return tombstoned.get();
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        boolean created = jdbc.sql("""
                        INSERT INTO workspace_deletion_runs (id, workspace_id, phase, rows_deleted, requested_at, updated_at)
                        VALUES (:id, :workspaceId, 'ADMISSION', 0, :now, :now)
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

    /**
     * Processes at most one phase's worth of work for an existing run. A no-op returning the tombstone
     * outcome once the run has already completed (see class Javadoc on why the run row itself is gone
     * by then); 404 if this workspace was never confirmed for deletion at all.
     */
    @Transactional
    public DeletionRunOutcome runBatch(UUID workspaceId, int maxRows) {
        if (maxRows <= 0) {
            throw new IllegalArgumentException("maxRows must be positive");
        }
        Optional<Run> run = loadForUpdate(workspaceId);
        if (run.isEmpty()) {
            return tombstoneOutcome(workspaceId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "No deletion request for this workspace"));
        }
        return processCurrentPhase(run.get(), workspaceId, maxRows);
    }

    @Transactional(readOnly = true)
    public Optional<DeletionRunOutcome> status(UUID workspaceId) {
        Optional<DeletionRunOutcome> running = find(workspaceId).map(this::outcomeFrom);
        return running.isPresent() ? running : tombstoneOutcome(workspaceId);
    }

    private DeletionRunOutcome processCurrentPhase(Run run, UUID workspaceId, int maxRows) {
        WorkspaceDeletionPhase phase = WorkspaceDeletionPhase.valueOf(run.phase());
        PhaseResult result = process(phase, workspaceId, run, maxRows);
        if (phase == WorkspaceDeletionPhase.WORKSPACE_ROOT) {
            // The run row (and the workspaces row it referenced) no longer exist; the tombstone this
            // phase just wrote is now the only record, and this run's total counter is meaningless to
            // keep updating -- rowsDeleted already reflects everything summed across every prior phase.
            return new DeletionRunOutcome(WorkspaceDeletionPhase.DONE.name(), result.deleted(), run.rowsDeleted(), true);
        }
        WorkspaceDeletionPhase nextPhase = result.exhausted() ? phase.next() : phase;
        boolean complete = nextPhase == WorkspaceDeletionPhase.DONE;
        long rowsDeleted = run.rowsDeleted() + result.deleted();
        updateRun(run.id(), nextPhase, rowsDeleted);
        return new DeletionRunOutcome(nextPhase.name(), result.deleted(), rowsDeleted, complete);
    }

    private PhaseResult process(WorkspaceDeletionPhase phase, UUID workspaceId, Run run, int maxRows) {
        return switch (phase) {
            case ADMISSION -> admit(workspaceId);
            case NOTIFICATION -> {
                var batch = notification.deleteBatch(workspaceId, maxRows);
                yield new PhaseResult(batch.rowsDeleted(), batch.exhausted());
            }
            case REPORTING -> {
                var batch = reporting.deleteBatch(workspaceId, maxRows);
                yield new PhaseResult(batch.rowsDeleted(), batch.exhausted());
            }
            case ATTRIBUTION -> {
                var batch = attribution.deleteBatch(workspaceId, maxRows);
                yield new PhaseResult(batch.rowsDeleted(), batch.exhausted());
            }
            case REVENUE -> {
                var batch = revenue.deleteBatch(workspaceId, maxRows);
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
            case WORKSPACE_ROOT -> deleteWorkspaceRoot(workspaceId, run);
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

    /**
     * Deletes the workspace row itself -- cascading its last remaining projects, members, and this
     * run's own checkpoint row -- and writes the tombstone in the same transaction, so there is no
     * window where neither record exists.
     */
    private PhaseResult deleteWorkspaceRoot(UUID workspaceId, Run run) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        int deleted = jdbc.sql("DELETE FROM workspaces WHERE id = :id").param("id", workspaceId).update();
        jdbc.sql("""
                        INSERT INTO workspace_deletion_tombstones (id, workspace_id, status, requested_at, completed_at)
                        VALUES (:id, :workspaceId, 'COMPLETED', :requestedAt, :completedAt)
                        """)
                .param("id", run.id())
                .param("workspaceId", workspaceId)
                .param("requestedAt", run.requestedAt())
                .param("completedAt", now)
                .update();
        return new PhaseResult(deleted, true);
    }

    private Optional<Run> loadForUpdate(UUID workspaceId) {
        return jdbc.sql(SELECT_RUN + " FOR UPDATE")
                .param("workspaceId", workspaceId)
                .query(WorkspaceDeletionRequestService::mapRun)
                .optional();
    }

    private Optional<Run> find(UUID workspaceId) {
        return jdbc.sql(SELECT_RUN).param("workspaceId", workspaceId).query(WorkspaceDeletionRequestService::mapRun).optional();
    }

    private static final String SELECT_RUN = """
            SELECT id, phase, rows_deleted, requested_at
            FROM workspace_deletion_runs
            WHERE workspace_id = :workspaceId
            """;

    private static Run mapRun(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Run(
                rs.getObject("id", UUID.class),
                rs.getString("phase"),
                rs.getLong("rows_deleted"),
                rs.getObject("requested_at", OffsetDateTime.class));
    }

    private void updateRun(UUID id, WorkspaceDeletionPhase phase, long rowsDeleted) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        jdbc.sql("""
                        UPDATE workspace_deletion_runs
                        SET phase = :phase, rows_deleted = :rowsDeleted, updated_at = :now
                        WHERE id = :id
                        """)
                .param("phase", phase.name())
                .param("rowsDeleted", rowsDeleted)
                .param("now", now)
                .param("id", id)
                .update();
    }

    private Optional<DeletionRunOutcome> tombstoneOutcome(UUID workspaceId) {
        return jdbc.sql("""
                        SELECT status FROM workspace_deletion_tombstones WHERE workspace_id = :workspaceId
                        """)
                .param("workspaceId", workspaceId)
                .query(String.class)
                .optional()
                .map(status -> new DeletionRunOutcome(WorkspaceDeletionPhase.DONE.name(), 0, 0, true));
    }

    private DeletionRunOutcome outcomeFrom(Run run) {
        return new DeletionRunOutcome(run.phase(), 0, run.rowsDeleted(), false);
    }

    private record Run(UUID id, String phase, long rowsDeleted, OffsetDateTime requestedAt) {}

    private record PhaseResult(int deleted, boolean exhausted) {}

    public record DeletionRunOutcome(String phase, int rowsDeletedThisBatch, long totalRowsDeleted, boolean complete) {}
}
