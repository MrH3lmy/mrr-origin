package com.mrrorigin.billing;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The billing module's own slice of #62's cross-module workspace deletion: hard-deletes every table
 * this module owns. Per V7's migration comment, cross-object references between the billing ledger
 * tables are plain Stripe ID columns, not foreign keys, so there is no dependency ordering among
 * {@code billing_invoices}/{@code billing_payments}/{@code billing_refunds}/{@code billing_discounts}/
 * {@code billing_subscriptions}/{@code billing_customers}/{@code billing_prices} -- deleting
 * {@code billing_subscriptions} also cascades its owned {@code billing_subscription_items} and
 * {@code billing_subscription_status_events} rows (both {@code ON DELETE CASCADE} from it).
 *
 * <p>{@code stripe_webhook_events} is hard-deleted here rather than left to its {@code ON DELETE SET
 * NULL} orphaning when {@code stripe_connections} is removed: the accepted #62 contract is explicit
 * that no copy of a Stripe invoice/event payload is retained once a workspace is deleted -- Stripe
 * remains the billing system of record -- so this table's raw payloads must be actively purged, not
 * merely disassociated from the workspace.
 *
 * <p>One call sweeps at most one table: it tries each owned table in order and deletes from the first
 * one that still has rows for this workspace, so a caller repeatedly invoking {@link #deleteBatch}
 * drains every table in turn. {@code exhausted()} is true only once a call finds every owned table
 * already empty -- stateless and idempotent, matching every other module's deletion service (see
 * {@code ReportingWorkspaceDataDeletionService}'s Javadoc for why no separate checkpoint is needed).
 */
@Service
public class BillingWorkspaceDataDeletionService {

    private static final List<String> TABLES_IN_ORDER = List.of(
            "billing_invoices",
            "billing_payments",
            "billing_refunds",
            "billing_discounts",
            "billing_subscriptions",
            "billing_customers",
            "billing_prices",
            "stripe_webhook_events",
            "stripe_connections",
            "stripe_oauth_states");

    private final JdbcClient jdbc;

    BillingWorkspaceDataDeletionService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public BatchResult deleteBatch(UUID workspaceId, int maxRows) {
        for (String table : TABLES_IN_ORDER) {
            int deleted = deleteBounded(table, workspaceId, maxRows);
            if (deleted > 0) {
                return new BatchResult(deleted, false);
            }
        }
        return new BatchResult(0, true);
    }

    private int deleteBounded(String table, UUID workspaceId, int maxRows) {
        return jdbc.sql("""
                        DELETE FROM %s
                        WHERE ctid IN (SELECT ctid FROM %s WHERE workspace_id = :workspaceId LIMIT :maxRows)
                        """.formatted(table, table))
                .param("workspaceId", workspaceId)
                .param("maxRows", maxRows)
                .update();
    }

    public record BatchResult(int rowsDeleted, boolean exhausted) {}
}
