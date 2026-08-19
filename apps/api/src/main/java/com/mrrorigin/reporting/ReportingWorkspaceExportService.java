package com.mrrorigin.reporting;

import java.io.IOException;
import java.io.Writer;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.mrrorigin.workspace.WorkspaceExportStreaming;

import tools.jackson.databind.ObjectMapper;

/**
 * The reporting module's own slice of #64's cross-module workspace export: streams {@code
 * export_audit_log}, the only table this module owns (mirroring {@code
 * ReportingWorkspaceDataDeletionService}), as NDJSON. Reporting has no other stored read models --
 * every dashboard/report view is computed on demand from attribution and revenue data, never
 * materialized into its own table -- so this is reporting's complete "reporting/read models" domain
 * export. {@code filters} is already restricted to query-parameter JSON (period, dimension,
 * source/campaign, retention age), never row-level or customer data, per V18's own contract.
 */
@Service
public class ReportingWorkspaceExportService {

    private static final int PAGE_SIZE = 500;

    private static final String COLUMNS = """
            id, workspace_id, project_id, export_type, schema_version, actor_subject_id, filters,
            row_count, created_at
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    ReportingWorkspaceExportService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public long writeNdjson(UUID workspaceId, Writer out) throws IOException {
        return WorkspaceExportStreaming.streamByColumn(
                jdbc, objectMapper, out, workspaceId, "export_audit_log", COLUMNS, "id", PAGE_SIZE,
                WorkspaceExportStreaming.genericMapper(objectMapper));
    }
}
