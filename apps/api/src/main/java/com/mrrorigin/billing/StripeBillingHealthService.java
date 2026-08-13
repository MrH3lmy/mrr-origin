package com.mrrorigin.billing;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Computes workspace-scoped Stripe billing-data health (#15): connection/checkpoint visibility,
 * webhook processing failures, reconciliation mismatches within the locally normalized ledger, and
 * a bounded live spot-check against Stripe's own current state.
 *
 * <p><b>Reconciliation.</b> Per V7's migration comment, {@code billing_subscriptions},
 * {@code billing_invoices}, etc. deliberately reference other billing objects by plain Stripe ID
 * columns rather than foreign keys, because Stripe does not guarantee delivery or backfill order
 * across object types. That means a real gap -- e.g. a subscription webhook processed before its
 * customer ever arrived, and the customer webhook then permanently missed -- is only detectable by
 * checking those references at query time. {@link #mismatches} does exactly that, bounded by {@link
 * #MISMATCH_SAMPLE_LIMIT} rows per kind.
 *
 * <p>Intra-ledger reference checks can never detect a Stripe object that is missing locally end to
 * end -- one with no dependent local row pointing at it at all (e.g. a customer Stripe has that we
 * never received any webhook or backfill page for). {@link #providerSpotCheck} closes that gap with
 * a bounded live comparison: Stripe's List API does not expose a total-count endpoint for
 * customers/subscriptions (asking "how many does Stripe have" is not a cheap, or even available,
 * operation), so instead of an exact count comparison this fetches exactly one page (the existing,
 * already-bounded {@link StripeBackfillClient#PAGE_SIZE}) of Stripe's most-recently-created
 * customers and subscriptions -- reusing the same client the resumable backfill already uses -- and
 * checks which of those specific ids have no local row at all. This is only trusted once the initial
 * backfill has reached {@code DONE}: before that, every not-yet-imported object would otherwise look
 * like a false "missing" gap.
 *
 * <p><b>Sync lag.</b> {@link #lastSyncAt} is the latest {@code updated_at} across every {@code
 * billing_*} ledger table for this workspace -- i.e. the last time any billing object actually
 * changed as a result of a successful backfill page or webhook normalization. It deliberately does
 * NOT fall back to {@code stripe_connections.updated_at}: that column is also touched by
 * verification checks, reconnects, and disconnects, none of which mean billing data has advanced, so
 * using it as a proxy could mask genuine staleness (a workspace whose webhook pipeline has been
 * silently broken for days could still look "just synced" purely because a routine verification
 * happened to run). The one carve-out is a connection whose initial backfill has completed and whose
 * ledger is (correctly) still empty -- nothing to have gone stale.
 */
@Service
public class StripeBillingHealthService {

    /** Sync activity older than this, with no other degrading reason present, marks the workspace STALE. */
    static final Duration STALE_THRESHOLD = Duration.ofHours(24);

    /** Bounds both the cost and the response size of each reconciliation-mismatch kind's query. */
    private static final int MISMATCH_SAMPLE_LIMIT = 200;

    /** How many sample Stripe IDs are echoed back per mismatch kind in the report. */
    private static final int MISMATCH_PREVIEW_LIMIT = 10;

    private static final List<String> LEDGER_TABLES = List.of(
            "billing_customers", "billing_prices", "billing_subscriptions", "billing_invoices",
            "billing_payments", "billing_refunds", "billing_discounts");

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
    private final StripeBackfillClient backfillClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public StripeBillingHealthService(
            JdbcClient jdbc,
            StripeConnectionRepository connections,
            StripeBackfillClient backfillClient,
            ObjectMapper objectMapper,
            Clock clock) {
        this.jdbc = jdbc;
        this.connections = connections;
        this.backfillClient = backfillClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public StripeBillingHealthReport health(UUID workspaceId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        StripeConnection connection = connections.findByWorkspaceId(workspaceId).orElse(null);

        WebhookCounts webhookCounts = webhookCounts(workspaceId);
        List<ReconciliationMismatch> mismatches = mismatches(workspaceId);
        LedgerTotals totals = ledgerTotals(workspaceId);

        List<StripeBillingHealthReason> reasons = new ArrayList<>();
        String backfillPhase = null;
        boolean backfillComplete = false;
        OffsetDateTime lastSyncAt = null;
        ProviderSpotCheck providerSpotCheck = ProviderSpotCheck.unavailable("no active, verified connection");

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
            if (webhookCounts.failedTransient() + webhookCounts.failedUnsupported() + webhookCounts.failedLegacy() > 0) {
                reasons.add(StripeBillingHealthReason.WEBHOOK_FAILURES_PRESENT);
            }
            if (!mismatches.isEmpty()) {
                reasons.add(StripeBillingHealthReason.RECONCILIATION_MISMATCH_PRESENT);
            }
            if (webhookCounts.orphaned() > 0) {
                reasons.add(StripeBillingHealthReason.ORPHANED_EVENTS_PRESENT);
            }

            boolean connectionEligible = connection.status() == StripeConnectionStatus.ACTIVE
                    && connection.verificationStatus() == StripeVerificationStatus.VERIFIED;
            if (connectionEligible) {
                providerSpotCheck = providerSpotCheck(workspaceId, connection, backfillComplete);
                if (providerSpotCheck.available()) {
                    if (!providerSpotCheck.missingCustomerIds().isEmpty()
                            || !providerSpotCheck.missingSubscriptionIds().isEmpty()) {
                        reasons.add(StripeBillingHealthReason.PROVIDER_RECONCILIATION_MISMATCH_PRESENT);
                    }
                } else {
                    reasons.add(StripeBillingHealthReason.PROVIDER_CHECK_UNAVAILABLE);
                }
            }

            lastSyncAt = lastSyncAt(workspaceId);
            boolean emptyAndFullySynced = backfillComplete && totals.isEmpty();
            boolean anyDegradingReason = reasons.contains(StripeBillingHealthReason.CONNECTION_NOT_ACTIVE)
                    || reasons.contains(StripeBillingHealthReason.CONNECTION_UNVERIFIED)
                    || reasons.contains(StripeBillingHealthReason.WEBHOOK_FAILURES_PRESENT)
                    || reasons.contains(StripeBillingHealthReason.RECONCILIATION_MISMATCH_PRESENT)
                    || reasons.contains(StripeBillingHealthReason.PROVIDER_RECONCILIATION_MISMATCH_PRESENT);
            if (!anyDegradingReason && !emptyAndFullySynced
                    && (lastSyncAt == null || Duration.between(lastSyncAt, now).compareTo(STALE_THRESHOLD) > 0)) {
                reasons.add(StripeBillingHealthReason.SYNC_LAG_EXCEEDED);
            }
            if (!backfillComplete) {
                reasons.add(StripeBillingHealthReason.BACKFILL_IN_PROGRESS);
            }
        }

        StripeBillingHealthStatus status = overallStatus(reasons);
        Long syncLagSeconds = lastSyncAt == null ? null : Duration.between(lastSyncAt, now).toSeconds();

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
                webhookCounts.failedLegacy(),
                totals,
                mismatches,
                providerSpotCheck,
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

        return new WebhookCounts(
                byState.getOrDefault("PENDING", 0L),
                byState.getOrDefault("ORPHANED", 0L),
                byState.getOrDefault("PROCESSED", 0L),
                failedCountByKind(workspaceId, "TRANSIENT"),
                failedCountByKind(workspaceId, "UNSUPPORTED"),
                failedCountByKind(workspaceId, "LEGACY"));
    }

    private long failedCountByKind(UUID workspaceId, String failureKind) {
        return jdbc.sql(
                        "SELECT COUNT(*) FROM stripe_webhook_events WHERE workspace_id = :workspaceId AND processing_state = 'FAILED' AND failure_kind = :failureKind")
                .param("workspaceId", workspaceId)
                .param("failureKind", failureKind)
                .query(Long.class)
                .single();
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

    /**
     * A bounded, live, single-page comparison against Stripe's own most-recently-created customers
     * and subscriptions -- see the class Javadoc for why this (rather than a total-count comparison,
     * which Stripe's List API does not support) is the reconciliation this method performs. Runs
     * outside of any database transaction, exactly like {@link StripeBackfillService}'s own page
     * fetches, since it is a blocking network call.
     */
    private ProviderSpotCheck providerSpotCheck(UUID workspaceId, StripeConnection connection, boolean backfillComplete) {
        if (!backfillComplete) {
            return ProviderSpotCheck.unavailable("initial backfill still in progress");
        }
        try {
            List<String> customerIds = providerIds(backfillClient.listCustomers(connection.mode(), connection.stripeAccountId(), null));
            List<String> missingCustomers = missingLocally(workspaceId, customerIds, "billing_customers", "stripe_customer_id");
            List<String> subscriptionIds =
                    providerIds(backfillClient.listSubscriptions(connection.mode(), connection.stripeAccountId(), null));
            List<String> missingSubscriptions =
                    missingLocally(workspaceId, subscriptionIds, "billing_subscriptions", "stripe_subscription_id");
            return new ProviderSpotCheck(
                    true, null, customerIds.size(), missingCustomers, subscriptionIds.size(), missingSubscriptions);
        } catch (RuntimeException requestFailed) {
            // The underlying StripeBackfillException message never includes response/payload
            // content (see its Javadoc), but a fixed message is used here regardless, so this
            // diagnostic surface can never depend on what a failure happens to say.
            return ProviderSpotCheck.unavailable("Stripe request failed");
        }
    }

    private static List<String> providerIds(StripeBackfillClient.StripePage page) {
        List<String> ids = new ArrayList<>();
        for (JsonNode item : page.data()) {
            JsonNode id = item.get("id");
            if (id != null && id.isTextual()) {
                ids.add(id.textValue());
            }
        }
        return ids;
    }

    private List<String> missingLocally(UUID workspaceId, List<String> candidateIds, String table, String column) {
        if (candidateIds.isEmpty()) {
            return List.of();
        }
        List<String> existing = jdbc.sql(
                        "SELECT " + column + " FROM " + table + " WHERE workspace_id = :workspaceId AND " + column + " IN (:ids)")
                .param("workspaceId", workspaceId)
                .param("ids", candidateIds)
                .query(String.class)
                .list();
        Set<String> existingSet = new HashSet<>(existing);
        return candidateIds.stream().filter(id -> !existingSet.contains(id)).toList();
    }

    /**
     * The latest {@code updated_at} across every {@code billing_*} ledger table for this workspace --
     * see the class Javadoc for why {@code stripe_connections.updated_at} is deliberately not used as
     * a fallback.
     */
    private OffsetDateTime lastSyncAt(UUID workspaceId) {
        StringBuilder sql = new StringBuilder("SELECT MAX(updated_at) FROM (");
        for (int i = 0; i < LEDGER_TABLES.size(); i++) {
            if (i > 0) {
                sql.append(" UNION ALL ");
            }
            sql.append("SELECT updated_at FROM ").append(LEDGER_TABLES.get(i)).append(" WHERE workspace_id = :workspaceId");
        }
        sql.append(") all_ledger_updates");
        // A bare MAX(...) aggregate always returns exactly one row, whose value is null rather than
        // absent when nothing matches -- .list() (which tolerates a null element) is used instead of
        // .optional() (which would wrap that null in Optional.of(null) and throw) for exactly that reason.
        List<OffsetDateTime> rows =
                jdbc.sql(sql.toString()).param("workspaceId", workspaceId).query(OffsetDateTime.class).list();
        return rows.isEmpty() ? null : rows.get(0);
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
                || reasons.contains(StripeBillingHealthReason.RECONCILIATION_MISMATCH_PRESENT)
                || reasons.contains(StripeBillingHealthReason.PROVIDER_RECONCILIATION_MISMATCH_PRESENT);
        if (degraded) {
            return StripeBillingHealthStatus.DEGRADED;
        }
        boolean stale = reasons.contains(StripeBillingHealthReason.SYNC_LAG_EXCEEDED);
        return stale ? StripeBillingHealthStatus.STALE : StripeBillingHealthStatus.HEALTHY;
    }

    private record MismatchDefinition(String kind, String sql) {}

    private record WebhookCounts(
            long pending, long orphaned, long processed, long failedTransient, long failedUnsupported, long failedLegacy) {}

    public record LedgerTotals(
            long customers, long prices, long subscriptions, long invoices, long payments, long refunds, long discounts) {

        boolean isEmpty() {
            return customers == 0 && prices == 0 && subscriptions == 0 && invoices == 0
                    && payments == 0 && refunds == 0 && discounts == 0;
        }
    }

    public record ReconciliationMismatch(String kind, int count, boolean truncated, List<String> sampleStripeIds) {}

    public record ProviderSpotCheck(
            boolean available,
            String unavailableReason,
            int customersChecked,
            List<String> missingCustomerIds,
            int subscriptionsChecked,
            List<String> missingSubscriptionIds) {

        static ProviderSpotCheck unavailable(String reason) {
            return new ProviderSpotCheck(false, reason, 0, List.of(), 0, List.of());
        }
    }

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
            long failedWebhookEventsLegacy,
            LedgerTotals ledgerTotals,
            List<ReconciliationMismatch> reconciliationMismatches,
            ProviderSpotCheck providerSpotCheck,
            OffsetDateTime computedAt) {}
}
