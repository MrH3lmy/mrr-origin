package com.mrrorigin.billing;

import java.io.IOException;
import java.io.Writer;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.mrrorigin.workspace.WorkspaceExportStreaming;

import tools.jackson.databind.ObjectMapper;

/**
 * The billing module's own slice of #64's cross-module workspace export: streams every row of the
 * normalized billing ledger this module owns as NDJSON. Scoped to
 * {@code billing_customers}/{@code billing_prices}/{@code billing_subscriptions} (+ items, status
 * events)/{@code billing_invoices}/{@code billing_payments}/{@code billing_refunds}/{@code
 * billing_discounts} plus {@code stripe_connections}' non-secret connection metadata -- the
 * workspace's normalized billing state, matching what {@code BillingWorkspaceDataDeletionService}
 * calls "the billing ledger."
 *
 * <p>Deliberately excludes, per #64's accepted contract and ADR-0009:
 * <ul>
 *   <li>{@code stripe_connections.sync_checkpoint} -- a reserved backfill checkpoint field
 *       ("checkpoint tokens").
 *   <li>{@code stripe_oauth_states} entirely -- ephemeral OAuth CSRF state, keyed by a hash of a
 *       one-time secret ("secret digests"), never workspace business data.
 *   <li>{@code stripe_webhook_events} entirely -- raw/parsed Stripe webhook ingestion bookkeeping
 *       (replay/attempt counters, the exact signed bytes used for cryptographic verification), not
 *       the normalized billing ledger; see ADR-0009 for the scoping rationale.
 * </ul>
 *
 * No per-workspace Stripe credential is ever stored in this database at all (ADR-0003): the platform
 * secret key and webhook signing secrets are process environment configuration, never a row here, so
 * there is nothing to redact for "Stripe/webhook secrets" beyond the OAuth-state exclusion above.
 */
@Service
public class BillingWorkspaceExportService {

    private static final int PAGE_SIZE = 500;

    private static final String CUSTOMER_COLUMNS = """
            id, workspace_id, stripe_customer_id, currency, deleted, provider_created_at, source,
            source_version, source_sequence, created_at, updated_at
            """;
    private static final String PRICE_COLUMNS = """
            id, workspace_id, stripe_price_id, stripe_product_id, currency, unit_amount, billing_scheme,
            type, recurring_interval, recurring_interval_count, active, source, source_version,
            source_sequence, created_at, updated_at
            """;
    private static final String SUBSCRIPTION_COLUMNS = """
            id, workspace_id, stripe_subscription_id, stripe_customer_id, status, currency,
            current_period_start, current_period_end, cancel_at_period_end, cancel_at, canceled_at,
            ended_at, trial_start, trial_end, collection_method, source, source_version, source_sequence,
            created_at, updated_at
            """;
    private static final String SUBSCRIPTION_ITEM_COLUMNS = """
            id, workspace_id, subscription_id, stripe_subscription_item_id, stripe_price_id, quantity,
            source_version, source_sequence, created_at, updated_at
            """;
    private static final String SUBSCRIPTION_STATUS_EVENT_COLUMNS = """
            id, workspace_id, subscription_id, stripe_subscription_id, previous_status, new_status,
            source, source_version, source_sequence, created_at
            """;
    private static final String INVOICE_COLUMNS = """
            id, workspace_id, stripe_invoice_id, stripe_customer_id, stripe_subscription_id, status,
            currency, amount_due, amount_paid, amount_remaining, period_start, period_end,
            provider_created_at, source, source_version, source_sequence, created_at, updated_at
            """;
    private static final String PAYMENT_COLUMNS = """
            id, workspace_id, stripe_charge_id, stripe_customer_id, stripe_invoice_id, amount, currency,
            status, paid, refunded, amount_refunded, provider_created_at, source, source_version,
            source_sequence, created_at, updated_at
            """;
    private static final String REFUND_COLUMNS = """
            id, workspace_id, stripe_refund_id, stripe_charge_id, amount, currency, status, reason,
            provider_created_at, source, source_version, source_sequence, created_at, updated_at
            """;
    private static final String DISCOUNT_COLUMNS = """
            id, workspace_id, stripe_discount_id, stripe_customer_id, stripe_subscription_id,
            stripe_subscription_item_id, stripe_coupon_id, percent_off, amount_off, currency, start_at,
            end_at, deleted, source, source_version, source_sequence, created_at, updated_at
            """;
    // sync_checkpoint deliberately omitted -- see class Javadoc.
    private static final String STRIPE_CONNECTION_COLUMNS = """
            id, workspace_id, stripe_account_id, mode, granted_scope, status, verification_status,
            created_at, updated_at, connected_at, disconnected_at, last_verified_at,
            last_verification_failed_at
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    BillingWorkspaceExportService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public long writeNdjson(UUID workspaceId, Writer out) throws IOException {
        var mapper = WorkspaceExportStreaming.genericMapper(objectMapper);
        long count = 0;
        count += stream(workspaceId, out, "billing_customers", CUSTOMER_COLUMNS, mapper);
        count += stream(workspaceId, out, "billing_prices", PRICE_COLUMNS, mapper);
        count += stream(workspaceId, out, "billing_subscriptions", SUBSCRIPTION_COLUMNS, mapper);
        count += stream(workspaceId, out, "billing_subscription_items", SUBSCRIPTION_ITEM_COLUMNS, mapper);
        count += stream(workspaceId, out, "billing_subscription_status_events", SUBSCRIPTION_STATUS_EVENT_COLUMNS, mapper);
        count += stream(workspaceId, out, "billing_invoices", INVOICE_COLUMNS, mapper);
        count += stream(workspaceId, out, "billing_payments", PAYMENT_COLUMNS, mapper);
        count += stream(workspaceId, out, "billing_refunds", REFUND_COLUMNS, mapper);
        count += stream(workspaceId, out, "billing_discounts", DISCOUNT_COLUMNS, mapper);
        count += stream(workspaceId, out, "stripe_connections", STRIPE_CONNECTION_COLUMNS, mapper);
        return count;
    }

    private long stream(
            UUID workspaceId,
            Writer out,
            String table,
            String columns,
            org.springframework.jdbc.core.RowMapper<java.util.LinkedHashMap<String, Object>> mapper)
            throws IOException {
        return WorkspaceExportStreaming.streamByColumn(jdbc, objectMapper, out, workspaceId, table, columns, "id", PAGE_SIZE, mapper);
    }
}
