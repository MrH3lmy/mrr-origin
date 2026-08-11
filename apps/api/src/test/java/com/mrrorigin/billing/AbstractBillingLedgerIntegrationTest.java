package com.mrrorigin.billing;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Shared Postgres-backed fixture plumbing for #12's normalization tests: workspace/connection/
 * webhook-event setup and column-level ledger read helpers used to assert convergence between the
 * backfill and webhook pipelines.
 *
 * <p>Deliberately does NOT declare the {@code @Container} Postgres field itself: a {@code static}
 * container field declared here would be a single object shared by every subclass's own {@code
 * @Testcontainers} lifecycle (JUnit resolves the field via inheritance, but each subclass's
 * before/after-all hooks manage the SAME instance), which caused exactly the container-restart/
 * stale-port races this comment now warns against -- confirmed by this suite reliably passing in
 * isolation but intermittently failing with "connection refused" only when run alongside its
 * siblings. Each concrete test class below instead declares its own independent container, exactly
 * like every other integration test in this module.
 */
@SpringBootTest
abstract class AbstractBillingLedgerIntegrationTest {

    @DynamicPropertySource
    static void stripeProperties(DynamicPropertyRegistry registry) {
        registry.add("mrrorigin.stripe.connect.test-client-id", () -> "ca_test_123");
        registry.add("mrrorigin.stripe.connect.test-secret-key", () -> "sk_test_platform_secret");
        registry.add("mrrorigin.stripe.connect.live-client-id", () -> "ca_live_123");
        registry.add("mrrorigin.stripe.connect.live-secret-key", () -> "sk_live_platform_secret");
        registry.add("mrrorigin.stripe.connect.test-webhook-secret", () -> "whsec_test_platform_secret");
        registry.add("mrrorigin.stripe.connect.live-webhook-secret", () -> "whsec_live_platform_secret");
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    StripeBackfillService backfillService;

    @Autowired
    StripeWebhookNormalizationService normalizationService;

    @Autowired
    BillingLedgerUpsertService ledger;

    private JdbcClient jdbc;

    @Autowired
    void setJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    JdbcClient jdbc() {
        return jdbc;
    }

    @BeforeEach
    void resetState() {
        new JdbcTemplate(dataSource)
                .execute(
                        "TRUNCATE TABLE projects, workspace_members, workspaces, stripe_connections, "
                                + "stripe_oauth_states, stripe_webhook_events, billing_customers, billing_prices, "
                                + "billing_subscriptions, billing_subscription_items, billing_subscription_status_events, "
                                + "billing_invoices, billing_payments, billing_refunds, billing_discounts CASCADE");
    }

    UUID createWorkspace() {
        UUID workspaceId = UUID.randomUUID();
        jdbc.sql("INSERT INTO workspaces (id, name, slug, reporting_currency) VALUES (:id, :name, :slug, 'USD')")
                .param("id", workspaceId)
                .param("name", "Workspace " + workspaceId)
                .param("slug", "workspace-" + workspaceId)
                .update();
        return workspaceId;
    }

    UUID insertActiveConnection(UUID workspaceId, String stripeAccountId, StripeConnectionMode mode) {
        return insertConnection(workspaceId, stripeAccountId, mode, StripeConnectionStatus.ACTIVE, StripeVerificationStatus.VERIFIED);
    }

    UUID insertConnection(
            UUID workspaceId,
            String stripeAccountId,
            StripeConnectionMode mode,
            StripeConnectionStatus status,
            StripeVerificationStatus verificationStatus) {
        UUID id = UUID.randomUUID();
        jdbc.sql(
                        """
                        INSERT INTO stripe_connections
                            (id, workspace_id, stripe_account_id, mode, granted_scope, status,
                             verification_status, connected_at)
                        VALUES (:id, :workspaceId, :stripeAccountId, :mode, 'read_only', :status,
                                :verificationStatus, CURRENT_TIMESTAMP)
                        """)
                .param("id", id)
                .param("workspaceId", workspaceId)
                .param("stripeAccountId", stripeAccountId)
                .param("mode", mode.name())
                .param("status", status.name())
                .param("verificationStatus", verificationStatus.name())
                .update();
        return id;
    }

    /** Directly inserts a PENDING raw webhook event row, bypassing HTTP ingestion (#11's own concern). */
    void insertPendingWebhookEvent(
            UUID connectionId, UUID workspaceId, StripeConnectionMode mode, String eventId, String type, Instant created, String object) {
        insertPendingWebhookEvent(connectionId, workspaceId, mode, eventId, type, created, Instant.now(), object);
    }

    /**
     * As above, but with an explicit {@code received_at} instead of letting each call land at
     * whatever instant it happens to execute -- needed to deterministically force two rows to share
     * the exact same receipt instant (proving convergence still holds even then), rather than
     * relying on separate calls coincidentally landing in the same instant.
     */
    void insertPendingWebhookEvent(
            UUID connectionId,
            UUID workspaceId,
            StripeConnectionMode mode,
            String eventId,
            String type,
            Instant created,
            Instant receivedAt,
            String object) {
        String payload = BillingFixtures.webhookEnvelope(
                eventId, "acct_unused", type, created.getEpochSecond(), mode == StripeConnectionMode.LIVE, object);
        jdbc.sql(
                        """
                        INSERT INTO stripe_webhook_events
                            (id, stripe_event_id, stripe_account_id, mode, connection_id, workspace_id,
                             event_type, api_version, stripe_created_at, received_at, raw_payload, payload,
                             processing_state)
                        VALUES
                            (:id, :stripeEventId, 'acct_unused', :mode, :connectionId, :workspaceId, :eventType,
                             '2024-06-20', :stripeCreatedAt, :receivedAt, :rawPayload, :payload::jsonb, 'PENDING')
                        """)
                .param("id", UUID.randomUUID())
                .param("stripeEventId", eventId)
                .param("mode", mode.name())
                .param("connectionId", connectionId)
                .param("workspaceId", workspaceId)
                .param("eventType", type)
                .param("stripeCreatedAt", OffsetDateTime.ofInstant(created, ZoneOffset.UTC))
                .param("receivedAt", OffsetDateTime.ofInstant(receivedAt, ZoneOffset.UTC))
                .param("rawPayload", payload.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .param("payload", payload)
                .update();
    }

    int drainWebhookQueue() {
        int total = 0;
        while (true) {
            StripeWebhookNormalizationService.NormalizationRunOutcome outcome = normalizationService.processBatch(50);
            total += outcome.fetched();
            if (outcome.fetched() == 0) {
                return total;
            }
        }
    }

    void runBackfillToCompletion(UUID connectionId) {
        StripeBackfillService.BackfillRunOutcome outcome;
        int guard = 0;
        do {
            outcome = backfillService.runBatch(connectionId, 25);
            guard++;
            if (guard > 100) {
                throw new IllegalStateException("Backfill did not complete within a bounded number of batches");
            }
        } while (!outcome.complete());
    }

    // ---- comparable ledger snapshots (excludes id/workspace_id/source*/timestamps) --------------

    Optional<Map<String, Object>> customerSnapshot(UUID workspaceId, String stripeCustomerId) {
        return singleRow(
                "SELECT currency, deleted FROM billing_customers WHERE workspace_id = :w AND stripe_customer_id = :id",
                workspaceId,
                stripeCustomerId);
    }

    Optional<Map<String, Object>> priceSnapshot(UUID workspaceId, String stripePriceId) {
        return singleRow(
                """
                SELECT stripe_product_id, currency, unit_amount, billing_scheme, type, recurring_interval,
                       recurring_interval_count, active
                FROM billing_prices WHERE workspace_id = :w AND stripe_price_id = :id
                """,
                workspaceId,
                stripePriceId);
    }

    Optional<Map<String, Object>> subscriptionSnapshot(UUID workspaceId, String stripeSubscriptionId) {
        return singleRow(
                """
                SELECT stripe_customer_id, status, currency, current_period_start, current_period_end,
                       cancel_at_period_end, trial_start, trial_end, collection_method
                FROM billing_subscriptions WHERE workspace_id = :w AND stripe_subscription_id = :id
                """,
                workspaceId,
                stripeSubscriptionId);
    }

    List<Map<String, Object>> subscriptionItemSnapshots(UUID workspaceId, String stripeSubscriptionId) {
        UUID subscriptionId = jdbc.sql(
                        "SELECT id FROM billing_subscriptions WHERE workspace_id = :w AND stripe_subscription_id = :id")
                .param("w", workspaceId)
                .param("id", stripeSubscriptionId)
                .query(UUID.class)
                .single();
        return jdbc.sql(
                        """
                        SELECT stripe_price_id, quantity FROM billing_subscription_items
                        WHERE subscription_id = :subId ORDER BY stripe_price_id
                        """)
                .param("subId", subscriptionId)
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("stripe_price_id", rs.getString("stripe_price_id"));
                    row.put("quantity", rs.getInt("quantity"));
                    return row;
                })
                .list();
    }

    Optional<Map<String, Object>> invoiceSnapshot(UUID workspaceId, String stripeInvoiceId) {
        return singleRow(
                """
                SELECT stripe_customer_id, stripe_subscription_id, status, currency, amount_due, amount_paid,
                       amount_remaining, period_start, period_end
                FROM billing_invoices WHERE workspace_id = :w AND stripe_invoice_id = :id
                """,
                workspaceId,
                stripeInvoiceId);
    }

    Optional<Map<String, Object>> paymentSnapshot(UUID workspaceId, String stripeChargeId) {
        return singleRow(
                """
                SELECT stripe_customer_id, stripe_invoice_id, amount, currency, status, paid, refunded,
                       amount_refunded
                FROM billing_payments WHERE workspace_id = :w AND stripe_charge_id = :id
                """,
                workspaceId,
                stripeChargeId);
    }

    Optional<Map<String, Object>> refundSnapshot(UUID workspaceId, String stripeRefundId) {
        return singleRow(
                "SELECT stripe_charge_id, amount, currency, status, reason FROM billing_refunds WHERE workspace_id = :w AND stripe_refund_id = :id",
                workspaceId,
                stripeRefundId);
    }

    Optional<Map<String, Object>> discountSnapshot(UUID workspaceId, String stripeDiscountId) {
        return singleRow(
                """
                SELECT stripe_customer_id, stripe_subscription_id, stripe_coupon_id, percent_off, amount_off,
                       currency, deleted
                FROM billing_discounts WHERE workspace_id = :w AND stripe_discount_id = :id
                """,
                workspaceId,
                stripeDiscountId);
    }

    int subscriptionStatusEventCount(UUID workspaceId, String stripeSubscriptionId) {
        return jdbc.sql(
                        "SELECT COUNT(*) FROM billing_subscription_status_events WHERE workspace_id = :w AND stripe_subscription_id = :id")
                .param("w", workspaceId)
                .param("id", stripeSubscriptionId)
                .query(Integer.class)
                .single();
    }

    List<String> subscriptionStatusHistory(UUID workspaceId, String stripeSubscriptionId) {
        return jdbc.sql(
                        """
                        SELECT new_status FROM billing_subscription_status_events
                        WHERE workspace_id = :w AND stripe_subscription_id = :id
                        ORDER BY source_version, source_sequence
                        """)
                .param("w", workspaceId)
                .param("id", stripeSubscriptionId)
                .query(String.class)
                .list();
    }

    private Optional<Map<String, Object>> singleRow(String sql, UUID workspaceId, String stripeId) {
        return jdbc.sql(sql)
                .param("w", workspaceId)
                .param("id", stripeId)
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    int columns = rs.getMetaData().getColumnCount();
                    for (int i = 1; i <= columns; i++) {
                        row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                    }
                    return row;
                })
                .optional();
    }
}
