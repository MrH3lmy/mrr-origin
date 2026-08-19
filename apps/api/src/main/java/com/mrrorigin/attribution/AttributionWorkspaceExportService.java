package com.mrrorigin.attribution;

import java.io.IOException;
import java.io.Writer;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.mrrorigin.workspace.WorkspaceExportStreaming;

import tools.jackson.databind.ObjectMapper;

/**
 * The attribution module's own slice of #64's cross-module workspace export: streams every row this
 * module owns as NDJSON, one JSON object per line. Mirrors {@code
 * AttributionWorkspaceDataDeletionService}'s owned-table list ({@code customer_attribution_results},
 * {@code attribution_recalculation_runs}) but reads instead of deletes.
 *
 * <p>Streams via {@link WorkspaceExportStreaming#streamByColumn}: bounded keyset pagination on {@code
 * id} (the same shape {@code CustomersCsvExportService} already established for #26's customers
 * export) so memory stays bounded to one page of rows at a time regardless of workspace size -- never
 * the full result set.
 *
 * <p>Every column is named explicitly rather than {@code SELECT *}, so a future column added to
 * either table is excluded by default. Neither owned table holds any credential, secret digest, or
 * lease/checkpoint token -- {@code cursor_customer_id} is a plain Stripe customer ID marking batch
 * resume position, not a security token.
 */
@Service
public class AttributionWorkspaceExportService {

    private static final int PAGE_SIZE = 500;

    private static final String RESULT_COLUMNS = """
            id, workspace_id, project_id, movement_id, acquisition_movement_id, model_version,
            first_touchpoint_id, last_touchpoint_id, customer_link_evidence_id, first_source,
            first_campaign, first_landing_page, last_source, last_campaign, last_landing_page,
            confidence, unattributed_reason, source_references, calculated_at
            """;

    private static final String RUN_COLUMNS = """
            id, workspace_id, project_id, model_version, status, cursor_customer_id,
            customers_processed, started_at, updated_at, completed_at
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    AttributionWorkspaceExportService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public long writeNdjson(UUID workspaceId, Writer out) throws IOException {
        var mapper = WorkspaceExportStreaming.genericMapper(objectMapper);
        long count = 0;
        count += WorkspaceExportStreaming.streamByColumn(
                jdbc, objectMapper, out, workspaceId, "customer_attribution_results", RESULT_COLUMNS, "id", PAGE_SIZE,
                mapper);
        count += WorkspaceExportStreaming.streamByColumn(
                jdbc, objectMapper, out, workspaceId, "attribution_recalculation_runs", RUN_COLUMNS, "id", PAGE_SIZE,
                mapper);
        return count;
    }
}
