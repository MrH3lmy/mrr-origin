package com.mrrorigin.revenue;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The revenue module's own slice of #62's cross-module workspace deletion: hard-deletes every table
 * this module owns. Deleting {@code revenue_subscription_states} also cascades its owned
 * {@code revenue_subscription_state_items} and {@code revenue_subscription_state_discounts} rows
 * (both {@code ON DELETE CASCADE} from it, per V9). {@code customer_mrr_movements} is safe to delete
 * here: the attribution phase, which runs before this one in the orchestrator's phase order, has
 * already cleared every {@code customer_attribution_results} row that would otherwise restrict or
 * cascade from it.
 *
 * <p>One call sweeps at most one table: it tries each owned table in order and deletes from the first
 * one that still has rows for this workspace, so a caller repeatedly invoking {@link #deleteBatch}
 * drains every table in turn. {@code exhausted()} is true only once a call finds every owned table
 * already empty -- stateless and idempotent, matching every other module's deletion service (see
 * {@code ReportingWorkspaceDataDeletionService}'s Javadoc for why no separate checkpoint is needed).
 */
@Service
public class RevenueWorkspaceDataDeletionService {

    private static final List<String> TABLES_IN_ORDER =
            List.of("revenue_subscription_states", "customer_mrr_snapshots", "customer_mrr_movements");

    private final JdbcClient jdbc;

    RevenueWorkspaceDataDeletionService(JdbcClient jdbc) {
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
