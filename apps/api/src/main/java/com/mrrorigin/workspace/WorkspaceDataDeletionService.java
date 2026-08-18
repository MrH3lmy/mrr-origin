package com.mrrorigin.workspace;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.mrrorigin.billing.StripeConnectionService;
import com.mrrorigin.tracking.IngestionKeyService;

/**
 * Resumable, checkpointed, full workspace hard deletion (#62) -- the same fetch-a-bounded-slice /
 * apply / advance-checkpoint shape {@code ProjectDataDeletionService} (#8) uses, generalized from
 * one project's tracking data to every workspace-owned table across billing, revenue, attribution,
 * reporting, notification, and tracking. See {@link WorkspaceDeletionPhase} for the full phase list
 * and dependency-order rationale.
 *
 * <p><b>Confirmation is explicit and separate from batching.</b> {@link #confirm} is the only entry
 * point that transitions a workspace from {@code ACTIVE} to {@code DELETING} and creates the run;
 * {@link #runBatch} deliberately does not lazily create a run the way {@code
 * ProjectDataDeletionService#runBatch} does -- calling it before {@link #confirm} throws {@link
 * IllegalStateException} rather than silently starting a deletion.
 *
 * <p><b>Resumability and idempotency.</b> Once confirmed, {@link #runBatch} processes at most {@code
 * maxRows} rows of the run's current phase in one transaction, advancing the phase only once that
 * phase reports fewer than {@code maxRows} affected (exhausted). Every table-sweep delete is
 * unconditional (no cutoff) and bounded, so reapplying a phase that partially completed before an
 * interruption is harmless. Calling {@link #runBatch} again once {@code status = COMPLETED} is a
 * no-op that returns the run's final counts -- though by that point the workspace (and everyone's
 * membership in it) is gone, so in practice no caller can still pass {@code WorkspaceContext}'s
 * authorization to reach that no-op; see {@link WorkspaceDeletionController}.
 *
 * <p><b>Final phase.</b> {@link WorkspaceDeletionPhase#WORKSPACE} deletes the {@code workspaces} row
 * itself, which cascades away {@code workspace_members}, {@code projects}, this run's own row, and
 * any remaining pure-cascade stragglers (e.g. {@code weekly_summary_opt_outs}) in one statement --
 * safe only because every RESTRICT-guarded table was already cleared by an earlier phase. It writes
 * {@code workspace_deletion_tombstones} in the same transaction, which is the durable, minimal,
 * non-PII record of the deletion once the run row itself is gone.
 */
@Service
public class WorkspaceDataDeletionService {

    private final JdbcClient jdbc;
    private final WorkspaceRepository workspaceRepository;
    private final IngestionKeyService ingestionKeys;
    private final StripeConnectionService stripeConnections;
    private final Clock clock;

    WorkspaceDataDeletionService(
            JdbcClient jdbc,
            WorkspaceRepository workspaceRepository,
            IngestionKeyService ingestionKeys,
            StripeConnectionService stripeConnections,
            Clock clock) {
        this.jdbc = jdbc;
        this.workspaceRepository = workspaceRepository;
        this.ingestionKeys = ingestionKeys;
        this.stripeConnections = stripeConnections;
        this.clock = clock;
    }

    /**
     * Marks the workspace {@code DELETING} and creates its deletion run, iff {@code confirmationSlug}
     * matches the workspace's actual slug -- the explicit confirmation step the accepted #62 contract
     * requires before any destructive action starts. Idempotent: a repeat call (e.g. a retried
     * request) for a workspace that already has a run returns that run's current outcome rather than
     * failing or creating a second one.
     */
    @Transactional
    public ConfirmationOutcome confirm(UUID workspaceId, String requestedBy, String confirmationSlug) {
        Optional<Run> existing = findRun(workspaceId);
        if (existing.isPresent()) {
            return confirmationOutcomeFrom(existing.get());
        }
        Workspace workspace = workspaceRepository
                .findById(workspaceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found"));
        if (confirmationSlug == null || !confirmationSlug.equals(workspace.slug())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "confirmationSlug must exactly match the workspace's slug");
        }

        UUID runId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(clock);
        try {
            workspace.markDeleting();
            workspaceRepository.saveAndFlush(workspace);
            jdbc.sql("""
                            INSERT INTO workspace_data_deletion_runs
                                (id, workspace_id, request_id, requested_by, status, phase, rows_deleted,
                                 started_at, updated_at)
                            VALUES
                                (:id, :workspaceId, :requestId, :requestedBy, 'RUNNING', :firstPhase, 0, :now, :now)
                            """)
                    .param("id", runId)
                    .param("workspaceId", workspaceId)
                    .param("requestId", requestId)
                    .param("requestedBy", requestedBy)
                    .param("firstPhase", WorkspaceDeletionPhase.values()[0].name())
                    .param("now", now)
                    .update();
        } catch (DataIntegrityViolationException concurrentConfirm) {
            // Lost a race against another confirm call for the same workspace; the run it created is
            // authoritative, not this one.
            return findRun(workspaceId).map(this::confirmationOutcomeFrom).orElseThrow(() -> concurrentConfirm);
        }
        return new ConfirmationOutcome(requestId, WorkspaceDeletionPhase.values()[0].name(), false);
    }

    /**
     * Processes one bounded batch of the current phase. Requires a prior {@link #confirm} call for
     * this workspace -- unlike {@code ProjectDataDeletionService#runBatch}, this does not lazily
     * create a run, since doing so would bypass the explicit-confirmation requirement.
     */
    @Transactional
    public DeletionRunOutcome runBatch(UUID workspaceId, int maxRows) {
        if (maxRows <= 0) {
            throw new IllegalArgumentException("maxRows must be positive");
        }
        Run run = loadRunForUpdate(workspaceId)
                .orElseThrow(() -> new IllegalStateException("Workspace deletion has not been confirmed"));
        if (run.status().equals("COMPLETED")) {
            return deletionOutcomeFrom(run);
        }
        WorkspaceDeletionPhase phase = WorkspaceDeletionPhase.valueOf(run.phase());
        PhaseResult result = process(phase, workspaceId, run.requestId(), run.startedAt(), maxRows);
        WorkspaceDeletionPhase nextPhase = result.exhausted() ? phase.next() : phase;
        boolean complete = nextPhase == WorkspaceDeletionPhase.DONE;
        long rowsDeleted = run.rowsDeleted() + result.affected();
        // Once the WORKSPACE phase has run, this row was already cascade-deleted along with the
        // workspace itself; this update then affects zero rows, which is a harmless no-op.
        updateRun(run.id(), nextPhase, rowsDeleted, complete);
        return new DeletionRunOutcome(run.requestId(), nextPhase.name(), result.affected(), rowsDeleted, complete);
    }

    @Transactional(readOnly = true)
    public Optional<DeletionRunOutcome> status(UUID workspaceId) {
        return findRun(workspaceId).map(this::deletionOutcomeFrom);
    }

    private PhaseResult process(
            WorkspaceDeletionPhase phase, UUID workspaceId, UUID requestId, OffsetDateTime requestedAt, int maxRows) {
        return switch (phase) {
            case REVOKE_INGESTION_KEYS -> {
                ingestionKeys.revokeAllForWorkspace(workspaceId);
                yield new PhaseResult(0, true);
            }
            case DISABLE_STRIPE_SYNC -> {
                stripeConnections.disconnectForWorkspaceDeletion(workspaceId);
                yield new PhaseResult(0, true);
            }
            case WORKSPACE -> finalizeWorkspaceDeletion(workspaceId, requestId, requestedAt);
            case DONE -> new PhaseResult(0, true);
            default -> unconditionalByWorkspace(tableFor(phase), workspaceId, maxRows);
        };
    }

    private PhaseResult unconditionalByWorkspace(String table, UUID workspaceId, int maxRows) {
        int deleted = jdbc.sql("""
                        DELETE FROM %s
                        WHERE id IN (
                            SELECT id FROM %s
                            WHERE workspace_id = :workspaceId
                            LIMIT :maxRows
                        )
                        """.formatted(table, table))
                .param("workspaceId", workspaceId)
                .param("maxRows", maxRows)
                .update();
        return new PhaseResult(deleted, deleted < maxRows);
    }

    /**
     * Deletes the {@code workspaces} row itself -- cascading away {@code workspace_members}, {@code
     * projects}, this run's own row, and any remaining pure-cascade stragglers -- and writes the
     * durable, minimal, non-PII deletion tombstone in the same transaction. Safe only because every
     * table an earlier phase was responsible for (in particular everything RESTRICT-guarded) is
     * already empty by the time this phase runs.
     */
    private PhaseResult finalizeWorkspaceDeletion(UUID workspaceId, UUID requestId, OffsetDateTime requestedAt) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        jdbc.sql("DELETE FROM workspaces WHERE id = :workspaceId")
                .param("workspaceId", workspaceId)
                .update();
        jdbc.sql("""
                        INSERT INTO workspace_deletion_tombstones
                            (request_id, workspace_id, status, created_at, completed_at)
                        VALUES (:requestId, :workspaceId, 'COMPLETED', :createdAt, :completedAt)
                        """)
                .param("requestId", requestId)
                .param("workspaceId", workspaceId)
                .param("createdAt", requestedAt)
                .param("completedAt", now)
                .update();
        return new PhaseResult(1, true);
    }

    private static String tableFor(WorkspaceDeletionPhase phase) {
        return switch (phase) {
            case EXPORT_AUDIT_LOG -> "export_audit_log";
            case WEEKLY_SUMMARY_DELIVERIES -> "weekly_summary_deliveries";
            case STRIPE_CUSTOMER_LINK_REPAIR_AUDIT_LOG -> "stripe_customer_link_repair_audit_log";
            case TRACKING_INGESTION_FAILURES -> "tracking_ingestion_failures";
            case TRACKING_VERIFICATION_ATTEMPTS -> "tracking_verification_attempts";
            case ATTRIBUTION_RESULTS -> "customer_attribution_results";
            case ATTRIBUTION_RECALCULATION_RUNS -> "attribution_recalculation_runs";
            case TRACKING_EVENT_ENVELOPES -> "tracking_event_envelopes";
            case TRACKING_INGESTION_BATCHES -> "tracking_ingestion_batches";
            case TOUCHPOINTS -> "touchpoints";
            case TRACKING_SESSIONS -> "tracking_sessions";
            case VISITOR_ALIASES -> "visitor_aliases";
            case VISITORS -> "visitors";
            case STRIPE_CUSTOMER_LINKS -> "stripe_customer_links";
            case EXTERNAL_IDENTITIES -> "external_identities";
            case PROJECT_INGESTION_KEYS -> "project_ingestion_keys";
            case PROJECT_ALLOWED_DOMAINS -> "project_allowed_domains";
            case REVENUE_SUBSCRIPTION_STATE_ITEMS -> "revenue_subscription_state_items";
            case REVENUE_SUBSCRIPTION_STATE_DISCOUNTS -> "revenue_subscription_state_discounts";
            case REVENUE_SUBSCRIPTION_STATES -> "revenue_subscription_states";
            case CUSTOMER_MRR_MOVEMENTS -> "customer_mrr_movements";
            case CUSTOMER_MRR_SNAPSHOTS -> "customer_mrr_snapshots";
            case BILLING_SUBSCRIPTION_STATUS_EVENTS -> "billing_subscription_status_events";
            case BILLING_SUBSCRIPTION_ITEMS -> "billing_subscription_items";
            case BILLING_SUBSCRIPTIONS -> "billing_subscriptions";
            case BILLING_INVOICES -> "billing_invoices";
            case BILLING_PAYMENTS -> "billing_payments";
            case BILLING_REFUNDS -> "billing_refunds";
            case BILLING_DISCOUNTS -> "billing_discounts";
            case BILLING_PRICES -> "billing_prices";
            case BILLING_CUSTOMERS -> "billing_customers";
            case STRIPE_WEBHOOK_EVENTS -> "stripe_webhook_events";
            case STRIPE_OAUTH_STATES -> "stripe_oauth_states";
            case STRIPE_CONNECTIONS -> "stripe_connections";
            default -> throw new IllegalStateException("No table mapping for phase " + phase);
        };
    }

    private Optional<Run> loadRunForUpdate(UUID workspaceId) {
        return jdbc.sql(SELECT_RUN + " FOR UPDATE")
                .param("workspaceId", workspaceId)
                .query(WorkspaceDataDeletionService::mapRun)
                .optional();
    }

    private Optional<Run> findRun(UUID workspaceId) {
        return jdbc.sql(SELECT_RUN)
                .param("workspaceId", workspaceId)
                .query(WorkspaceDataDeletionService::mapRun)
                .optional();
    }

    private static final String SELECT_RUN = """
            SELECT id, request_id, requested_by, status, phase, rows_deleted, started_at
            FROM workspace_data_deletion_runs
            WHERE workspace_id = :workspaceId
            """;

    private static Run mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new Run(
                rs.getObject("id", UUID.class),
                rs.getObject("request_id", UUID.class),
                rs.getString("requested_by"),
                rs.getString("status"),
                rs.getString("phase"),
                rs.getLong("rows_deleted"),
                rs.getObject("started_at", OffsetDateTime.class));
    }

    private void updateRun(UUID id, WorkspaceDeletionPhase phase, long rowsDeleted, boolean complete) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        jdbc.sql("""
                        UPDATE workspace_data_deletion_runs
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

    private ConfirmationOutcome confirmationOutcomeFrom(Run run) {
        return new ConfirmationOutcome(run.requestId(), run.phase(), run.status().equals("COMPLETED"));
    }

    private DeletionRunOutcome deletionOutcomeFrom(Run run) {
        return new DeletionRunOutcome(run.requestId(), run.phase(), 0, run.rowsDeleted(), run.status().equals("COMPLETED"));
    }

    private record Run(
            UUID id, UUID requestId, String requestedBy, String status, String phase, long rowsDeleted,
            OffsetDateTime startedAt) {}

    private record PhaseResult(int affected, boolean exhausted) {}

    public record ConfirmationOutcome(UUID requestId, String phase, boolean complete) {}

    public record DeletionRunOutcome(
            UUID requestId, String phase, int rowsAffectedThisBatch, long totalRowsDeleted, boolean complete) {}
}
