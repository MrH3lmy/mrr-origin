package com.mrrorigin.billing;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

/**
 * Computes workspace-scoped Stripe billing-data health (#15): connection/checkpoint visibility,
 * webhook processing failures, and reconciliation mismatches within the locally normalized ledger.
 * Entirely read-only and computed live from existing V5/V7 state -- no new persisted snapshot, so
 * the report is always current and there is nothing here that could itself go stale.
 *
 * <p><b>Reconciliation.</b> Per V7's migration comment, {@code billing_subscriptions},
 * {@code billing_invoices}, etc. deliberately reference other billing objects by plain Stripe ID
 * columns rather than foreign keys, because Stripe does not guarantee delivery or backfill order
 * across object types. That means a real gap -- e.g. a subscription webhook processed before its
 * customer ever arrived, and the customer webhook then permanently missed -- is only detectable by
 * checking those references at query time. {@link #mismatches} does exactly that, bounded by {@link
 * #MISMATCH_SAMPLE_LIMIT} rows per kind, and reports it as a DEGRADED reason without ever touching
 * (or needing to touch) any raw Stripe payload.
 *
 * <p><b>Sync lag.</b> There is no dedicated "last successful sync" column. {@link #lastSyncAt} uses
 * the latest of: this connection's {@code updated_at} (touched on every verification outcome and
 * every backfill checkpoint advance) and the most recent {@code updated_at} among this workspace's
 * {@code PROCESSED} webhook events. This is a deliberately conservative proxy, not a precise "last
 * successful write" timestamp -- it can be pushed forward by an unrelated verification retry -- but
 * it can never hide genuine staleness: if nothing has touched the connection or processed a webhook
 * recently, none of these timestamps advance either.
 */
@Service
@Transactional(readOnly = true)
public class StripeBillingHealthService {

    /** Sync activity older than this, with no other degrading reason present, marks the workspace STALE. */
    static final Duration STALE_THRESHOLD = Duration.ofHours(24);

    /** Bounds both the cost and the response size of each reconciliation-mismatch kind's query. */
    private static final int MISMATCH_SAMPLE_LIMIT = 200;

    /** How many sample Stripe IDs are echoed back per mismatch kind in the report. */
    private static final int MISMATCH_PREVIEW_LIMIT = 10;

    private static final List<MismatchDefinition> MISMATCH_DEFINITIONS = List.of(
            new MismatchDefinition(
                    "SUBSCRIPTION_MISSING_CUSTOMER",
                    """
                    SELECT bs.stripe_subscription_id AS stripe_id FROM billing_subscriptions bs
                    WHERE bs.workspace_id = :workspaceId AND NOT EXISTS (
                        SELECT 1 FROM billing_customers bc
                        WHERE bc.workspace_id = bs.workspace_id AND bc.stripe_customer_id = bs.stripe_customer_id)
                    ORDER BY bs.stripe_subscription_id
                    LIMIT :limit
                    """),
            new MismatchDefinition(
                    "INVOICE_MISSING_CUSTOMER",
                    """
                    SELECT bi.stripe_invoice_id AS stripe_id FROM billing_invoices bi
                    WHERE bi.workspace_id = :workspaceId AND NOT EXISTS (
                        SELECT 1 FROM billing_customers bc
                        WHERE bc.workspace_id = bi.workspace_id AND bc.stripe_customer_id = bi.stripe_customer_id)
                    ORDER BY bi.stripe_invoice_id
                    LIMIT :limit
                    """),
            new MismatchDefinition(
                    "INVOICE_MISSING_SUBSCRIPTION",
                    """
                    SELECT bi.stripe_invoice_id AS stripe_id FROM billing_invoices bi
                    WHERE bi.workspace_id = :workspaceId AND bi.stripe_subscription_id IS NOT NULL AND NOT EXISTS (
                        SELECT 1 FROM billing_subscriptions bs
                        WHERE bs.workspace_id = bi.workspace_id AND bs.stripe_subscription_id = bi.stripe_subscription_id)
                    ORDER BY bi.stripe_invoice_id
                    LIMIT :limit
                    """),
            new MismatchDefinition(
                    "PAYMENT_MISSING_INVOICE",
                    """
                    SELECT bp.stripe_charge_id AS stripe_id FROM billing_payments bp
                    WHERE bp.workspace_id = :workspaceId AND bp.stripe_invoice_id IS NOT NULL AND NOT EXISTS (
                        SELECT 1 FROM billing_invoices bi
                        WHERE bi.workspace_id = bp.workspace_id AND bi.stripe_invoice_id = bp.stripe_invoice_id)
                    ORDER BY bp.stripe_charge_id
                    LIMIT :limit
                    """),
            new MismatchDefinition(
                    "REFUND_MISSING_PAYMENT",
                    """
                    SELECT br.stripe_refund_id AS stripe_id FROM billing_refunds br
                    WHERE br.workspace_id = :workspaceId AND NOT EXISTS (
                        SELECT 1 FROM billing_payments bp
                        WHERE bp.workspace_id = br.workspace_id AND bp.stripe_charge_id = br.stripe_charge_id)
                    ORDER BY br.stripe_refund_id
                    LIMIT :limit
                    """));

    private final JdbcClient jdbc;
    private final StripeConnectionRepository connections;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public StripeBillingHealthService(
            JdbcClient jdbc, StripeConnectionRepository connections, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.connections = connections;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public StripeBillingHealthReport health(UUID workspaceId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        StripeConnection connection = connections.findByWorkspaceId(workspaceId).orElse(null);

        WebhookCounts webhookCounts = webhookCounts(workspaceId);
        List<ReconciliationMismatch> mismatches = mismatches(workspaceId);

        List<StripeBillingHealthReason> reasons = new ArrayList<>();
        String backfillPhase = null;
        boolean backfillComplete = false;
        OffsetDateTime lastSyncAt = null;

        if (connection == null) {
            reasons.add(StripeBillingHealthReason.NO_ACTIVE_CONNECTION);
        } else {
            StripeBackfillCheckpoint checkpoint = StripeBackfillCheckpoint.parse(objectMapper, connection.syncCheckpoint());
            backfillPhase = checkpoint.phase().name();
            backfillComplete = checkpoint.isComplete();

            if (connection.status() != StripeConnectionStatus.ACTIVE) {
                reasons.add(StripeBillingHealthReason.CONNECTION_NOT_ACTIVE);
            }
            if (connection.verificationStatus() != StripeVerificationStatus.VERIFIED) {
                reasons.add(StripeBillingHealthReason.CONNECTION_UNVERIFIED);
            }
            if (webhookCounts.failedTransient() + webhookCounts.failedUnsupported() > 0) {
                reasons.add(StripeBillingHealthReason.WEBHOOK_FAILURES_PRESENT);
            }
            if (!mismatches.isEmpty()) {
                reasons.add(StripeBillingHealthReason.RECONCILIATION_MISMATCH_PRESENT);
            }
            if (webhookCounts.orphaned() > 0) {
                reasons.add(StripeBillingHealthReason.ORPHANED_EVENTS_PRESENT);
            }

            lastSyncAt = lastSyncAt(workspaceId, connection);
            boolean anyDegradingReason = reasons.contains(StripeBillingHealthReason.CONNECTION_NOT_ACTIVE)
                    || reasons.contains(StripeBillingHealthReason.CONNECTION_UNVERIFIED)
                    || reasons.contains(StripeBillingHealthReason.WEBHOOK_FAILURES_PRESENT)
                    || reasons.contains(StripeBillingHealthReason.RECONCILIATION_MISMATCH_PRESENT);
            if (!anyDegradingReason) {
                if (lastSyncAt == null) {
                    reasons.add(StripeBillingHealthReason.NEVER_SYNCED);
                } else if (Duration.between(lastSyncAt, now).compareTo(STALE_THRESHOLD) > 0) {
                    reasons.add(StripeBillingHealthReason.SYNC_LAG_EXCEEDED);
                }
            }
            if (!backfillComplete) {
                reasons.add(StripeBillingHealthReason.BACKFILL_IN_PROGRESS);
            }
        }

        StripeBillingHealthStatus status = overallStatus(reasons);
        Long syncLagSeconds = lastSyncAt == null ? null : Duration.between(lastSyncAt, now).toSeconds();
        LedgerTotals totals = ledgerTotals(workspaceId);

        return new StripeBillingHealthReport(
                workspaceId,
                status,
                List.copyOf(reasons),
                connection != null,
                connection == null ? null : connection.status(),
                connection == null ? null : connection.verificationStatus(),
                connection == null ? null : connection.mode(),
                backfillPhase,
                backfillComplete,
                lastSyncAt,
                syncLagSeconds,
                webhookCounts.pending(),
                webhookCounts.orphaned(),
                webhookCounts.processed(),
                webhookCounts.failedTransient(),
                webhookCounts.failedUnsupported(),
                totals,
                mismatches,
                now);
    }

    /** Bounded, workspace-scoped diagnostics for currently-FAILED events (no payload/raw_payload). */
    public List<FailedEventDiagnostic> failedEvents(UUID workspaceId, int limit) {
        int bounded = Math.max(1, Math.min(limit, 200));
        return jdbc.sql(
                        """
                        SELECT id, stripe_event_id, event_type, failure_kind, attempt_count, last_error,
                               received_at, last_attempted_at, replay_count, last_replayed_at
                        FROM stripe_webhook_events
                        WHERE workspace_id = :workspaceId AND processing_state = 'FAILED'
                        ORDER BY received_at ASC
                        LIMIT :limit
                        """)
                .param("workspaceId", workspaceId)
                .param("limit", bounded)
                .query((rs, rowNum) -> new FailedEventDiagnostic(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("stripe_event_id"),
                        rs.getString("event_type"),
                        rs.getString("failure_kind"),
                        rs.getInt("attempt_count"),
                        rs.getString("last_error"),
                        rs.getObject("received_at", OffsetDateTime.class),
                        rs.getObject("last_attempted_at", OffsetDateTime.class),
                        rs.getInt("replay_count"),
                        rs.getObject("last_replayed_at", OffsetDateTime.class)))
                .list();
    }

    private WebhookCounts webhookCounts(UUID workspaceId) {
        Map<String, Long> byState = new HashMap<>();
        for (StripeWebhookProcessingState state : StripeWebhookProcessingState.values()) {
            byState.put(state.name(), 0L);
        }
        jdbc.sql("SELECT processing_state, COUNT(*) AS c FROM stripe_webhook_events WHERE workspace_id = :workspaceId GROUP BY processing_state")
                .param("workspaceId", workspaceId)
                .query((rs, rowNum) -> Map.entry(rs.getString("processing_state"), rs.getLong("c")))
                .list()
                .forEach(entry -> byState.put(entry.getKey(), entry.getValue()));

        long failedTransient = jdbc.sql(
                        "SELECT COUNT(*) FROM stripe_webhook_events WHERE workspace_id = :workspaceId AND processing_state = 'FAILED' AND failure_kind = 'TRANSIENT'")
                .param("workspaceId", workspaceId)
                .query(Long.class)
                .single();
        long failedUnsupported = jdbc.sql(
                        "SELECT COUNT(*) FROM stripe_webhook_events WHERE workspace_id = :workspaceId AND processing_state = 'FAILED' AND failure_kind = 'UNSUPPORTED'")
                .param("workspaceId", workspaceId)
                .query(Long.class)
                .single();

        return new WebhookCounts(
                byState.getOrDefault("PENDING", 0L),
                byState.getOrDefault("ORPHANED", 0L),
                byState.getOrDefault("PROCESSED", 0L),
                failedTransient,
                failedUnsupported);
    }

    private List<ReconciliationMismatch> mismatches(UUID workspaceId) {
        List<ReconciliationMismatch> mismatches = new ArrayList<>();
        for (MismatchDefinition definition : MISMATCH_DEFINITIONS) {
            List<String> ids = jdbc.sql(definition.sql())
                    .param("workspaceId", workspaceId)
                    .param("limit", MISMATCH_SAMPLE_LIMIT)
                    .query(String.class)
                    .list();
            if (!ids.isEmpty()) {
                mismatches.add(new ReconciliationMismatch(
                        definition.kind(),
                        ids.size(),
                        ids.size() == MISMATCH_SAMPLE_LIMIT,
                        ids.stream().limit(MISMATCH_PREVIEW_LIMIT).toList()));
            }
        }
        return mismatches;
    }

    private OffsetDateTime lastSyncAt(UUID workspaceId, StripeConnection connection) {
        // A bare MAX(...) aggregate always returns exactly one row, whose value is null rather than
        // absent when nothing matches -- .list() (which tolerates a null element) is used instead of
        // .optional() (which would wrap that null in Optional.of(null) and throw) for exactly that reason.
        List<OffsetDateTime> rows = jdbc.sql(
                        "SELECT MAX(updated_at) FROM stripe_webhook_events WHERE workspace_id = :workspaceId AND processing_state = 'PROCESSED'")
                .param("workspaceId", workspaceId)
                .query(OffsetDateTime.class)
                .list();
        OffsetDateTime lastProcessedWebhookAt = rows.isEmpty() ? null : rows.get(0);
        OffsetDateTime connectionUpdatedAt = connection.updatedAt();
        if (lastProcessedWebhookAt == null) {
            return connectionUpdatedAt;
        }
        if (connectionUpdatedAt == null) {
            return lastProcessedWebhookAt;
        }
        return lastProcessedWebhookAt.isAfter(connectionUpdatedAt) ? lastProcessedWebhookAt : connectionUpdatedAt;
    }

    private LedgerTotals ledgerTotals(UUID workspaceId) {
        return new LedgerTotals(
                countRows("billing_customers", workspaceId),
                countRows("billing_prices", workspaceId),
                countRows("billing_subscriptions", workspaceId),
                countRows("billing_invoices", workspaceId),
                countRows("billing_payments", workspaceId),
                countRows("billing_refunds", workspaceId),
                countRows("billing_discounts", workspaceId));
    }

    private long countRows(String table, UUID workspaceId) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE workspace_id = :workspaceId")
                .param("workspaceId", workspaceId)
                .query(Long.class)
                .single();
    }

    private static StripeBillingHealthStatus overallStatus(List<StripeBillingHealthReason> reasons) {
        boolean degraded = reasons.contains(StripeBillingHealthReason.NO_ACTIVE_CONNECTION)
                || reasons.contains(StripeBillingHealthReason.CONNECTION_NOT_ACTIVE)
                || reasons.contains(StripeBillingHealthReason.CONNECTION_UNVERIFIED)
                || reasons.contains(StripeBillingHealthReason.WEBHOOK_FAILURES_PRESENT)
                || reasons.contains(StripeBillingHealthReason.RECONCILIATION_MISMATCH_PRESENT);
        if (degraded) {
            return StripeBillingHealthStatus.DEGRADED;
        }
        boolean stale = reasons.contains(StripeBillingHealthReason.NEVER_SYNCED)
                || reasons.contains(StripeBillingHealthReason.SYNC_LAG_EXCEEDED);
        return stale ? StripeBillingHealthStatus.STALE : StripeBillingHealthStatus.HEALTHY;
    }

    private record MismatchDefinition(String kind, String sql) {}

    private record WebhookCounts(long pending, long orphaned, long processed, long failedTransient, long failedUnsupported) {}

    public record LedgerTotals(
            long customers, long prices, long subscriptions, long invoices, long payments, long refunds, long discounts) {}

    public record ReconciliationMismatch(String kind, int count, boolean truncated, List<String> sampleStripeIds) {}

    public record FailedEventDiagnostic(
            UUID id,
            String stripeEventId,
            String eventType,
            String failureKind,
            int attemptCount,
            String lastError,
            OffsetDateTime receivedAt,
            OffsetDateTime lastAttemptedAt,
            int replayCount,
            OffsetDateTime lastReplayedAt) {}

    public record StripeBillingHealthReport(
            UUID workspaceId,
            StripeBillingHealthStatus status,
            List<StripeBillingHealthReason> reasons,
            boolean connectionPresent,
            StripeConnectionStatus connectionStatus,
            StripeVerificationStatus verificationStatus,
            StripeConnectionMode connectionMode,
            String backfillPhase,
            boolean backfillComplete,
            OffsetDateTime lastSyncAt,
            Long syncLagSeconds,
            long pendingWebhookEvents,
            long orphanedWebhookEvents,
            long processedWebhookEvents,
            long failedWebhookEventsTransient,
            long failedWebhookEventsUnsupported,
            LedgerTotals ledgerTotals,
            List<ReconciliationMismatch> reconciliationMismatches,
            OffsetDateTime computedAt) {}
}
