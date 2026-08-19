package com.mrrorigin.revenue;

import java.io.IOException;
import java.io.Writer;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.mrrorigin.workspace.WorkspaceExportStreaming;

import tools.jackson.databind.ObjectMapper;

/**
 * The revenue module's own slice of #64's cross-module workspace export: streams every row this
 * module owns as NDJSON. Mirrors {@code RevenueWorkspaceDataDeletionService}'s owned-table list --
 * {@code revenue_subscription_states} (+ items, discounts), {@code customer_mrr_snapshots}, {@code
 * customer_mrr_movements} -- but reads instead of deletes. No owned table holds any credential,
 * secret, or lease/checkpoint token.
 */
@Service
public class RevenueWorkspaceExportService {

    private static final int PAGE_SIZE = 500;

    private static final String STATE_COLUMNS = """
            id, workspace_id, stripe_customer_id, stripe_subscription_id, effective_at, status,
            source_billing_reference, created_at
            """;
    private static final String STATE_ITEM_COLUMNS = """
            id, workspace_id, state_id, source_item_reference, currency, unit_amount_minor, quantity,
            recurring_interval, interval_count, usage_pricing
            """;
    private static final String STATE_DISCOUNT_COLUMNS = """
            id, workspace_id, state_id, source_discount_reference, source_item_reference, percent_off,
            amount_off_minor, currency, start_at, end_at
            """;
    private static final String SNAPSHOT_COLUMNS = """
            id, workspace_id, stripe_customer_id, currency, amount_minor, effective_at,
            calculation_version, supported, unsupported_reason, source_billing_references, calculated_at
            """;
    private static final String MOVEMENT_COLUMNS = """
            id, workspace_id, stripe_customer_id, currency, amount_minor, movement_type, effective_at,
            calculation_version, source_billing_references, calculated_at
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    RevenueWorkspaceExportService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public long writeNdjson(UUID workspaceId, Writer out) throws IOException {
        var mapper = WorkspaceExportStreaming.genericMapper(objectMapper);
        long count = 0;
        count += stream(workspaceId, out, "revenue_subscription_states", STATE_COLUMNS, mapper);
        count += stream(workspaceId, out, "revenue_subscription_state_items", STATE_ITEM_COLUMNS, mapper);
        count += stream(workspaceId, out, "revenue_subscription_state_discounts", STATE_DISCOUNT_COLUMNS, mapper);
        count += stream(workspaceId, out, "customer_mrr_snapshots", SNAPSHOT_COLUMNS, mapper);
        count += stream(workspaceId, out, "customer_mrr_movements", MOVEMENT_COLUMNS, mapper);
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
