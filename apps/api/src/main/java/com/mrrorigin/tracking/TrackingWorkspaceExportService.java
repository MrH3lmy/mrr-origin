package com.mrrorigin.tracking;

import java.io.IOException;
import java.io.Writer;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.mrrorigin.workspace.WorkspaceExportStreaming;

import tools.jackson.databind.ObjectMapper;

/**
 * The tracking module's own slice of #64's cross-module workspace export: streams every row this
 * module owns, across every project in the workspace, as NDJSON. Mirrors {@code
 * TrackingWorkspaceDataDeletionService}'s owned-table list but reads instead of deletes.
 *
 * <p>Deliberately excludes, per #64's accepted contract and ADR-0009:
 * <ul>
 *   <li>{@code project_ingestion_keys.secret_hash} -- the ingestion key secret's digest
 *       ("ingestion-key secrets"/"secret digests"); {@code key_prefix} (public, non-secret) is kept.
 *   <li>{@code tracking_verification_attempts.token} -- a single-use bearer credential used to prove
 *       an install-verification event belongs to this exact attempt ("credentials").
 * </ul>
 *
 * <p>{@code tracking_ingestion_batches.request_hash} is kept: it is a content fingerprint of the
 * batch body for idempotency/dedup, not a digest of any credential or secret.
 * {@code project_tracking_retention_settings} has no {@code id} column ({@code PRIMARY KEY
 * (project_id)}), so it streams keyed on {@code project_id} instead -- still a single UUID column,
 * so {@link WorkspaceExportStreaming#streamByColumn} applies unchanged.
 */
@Service
public class TrackingWorkspaceExportService {

    private static final int PAGE_SIZE = 500;

    private static final String VISITOR_COLUMNS = """
            id, workspace_id, project_id, external_visitor_id, first_seen_at, last_seen_at, created_at
            """;
    private static final String SESSION_COLUMNS = """
            id, workspace_id, project_id, visitor_id, external_session_id, started_at, ended_at, created_at
            """;
    private static final String TOUCHPOINT_COLUMNS = """
            id, workspace_id, project_id, visitor_id, session_id, occurred_at, landing_url, referrer_url,
            utm_source, utm_medium, utm_campaign, utm_term, utm_content, created_at
            """;
    private static final String EVENT_ENVELOPE_COLUMNS = """
            id, workspace_id, project_id, visitor_id, session_id, external_event_id, event_type,
            occurred_at, received_at, payload
            """;
    private static final String INGESTION_BATCH_COLUMNS = """
            id, workspace_id, project_id, external_batch_id, envelope_version, request_hash,
            event_results, received_at
            """;
    // token deliberately omitted -- see class Javadoc.
    private static final String VERIFICATION_ATTEMPT_COLUMNS = """
            id, workspace_id, project_id, status, created_at, expires_at, succeeded_at,
            received_external_event_id
            """;
    private static final String INGESTION_FAILURE_COLUMNS = """
            id, workspace_id, project_id, kind, detail, occurred_at
            """;
    // secret_hash deliberately omitted -- see class Javadoc.
    private static final String INGESTION_KEY_COLUMNS = """
            id, workspace_id, project_id, key_prefix, created_at, revoked_at
            """;
    private static final String ALLOWED_DOMAIN_COLUMNS = """
            id, workspace_id, project_id, domain, created_at
            """;
    private static final String RETENTION_SETTINGS_COLUMNS = """
            workspace_id, project_id, retention_days, updated_at
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    TrackingWorkspaceExportService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public long writeNdjson(UUID workspaceId, Writer out) throws IOException {
        var mapper = WorkspaceExportStreaming.genericMapper(objectMapper);
        long count = 0;
        count += stream(workspaceId, out, "visitors", VISITOR_COLUMNS, "id", mapper);
        count += stream(workspaceId, out, "tracking_sessions", SESSION_COLUMNS, "id", mapper);
        count += stream(workspaceId, out, "touchpoints", TOUCHPOINT_COLUMNS, "id", mapper);
        count += stream(workspaceId, out, "tracking_event_envelopes", EVENT_ENVELOPE_COLUMNS, "id", mapper);
        count += stream(workspaceId, out, "tracking_ingestion_batches", INGESTION_BATCH_COLUMNS, "id", mapper);
        count += stream(workspaceId, out, "tracking_verification_attempts", VERIFICATION_ATTEMPT_COLUMNS, "id", mapper);
        count += stream(workspaceId, out, "tracking_ingestion_failures", INGESTION_FAILURE_COLUMNS, "id", mapper);
        count += stream(workspaceId, out, "project_ingestion_keys", INGESTION_KEY_COLUMNS, "id", mapper);
        count += stream(workspaceId, out, "project_allowed_domains", ALLOWED_DOMAIN_COLUMNS, "id", mapper);
        count += stream(
                workspaceId, out, "project_tracking_retention_settings", RETENTION_SETTINGS_COLUMNS, "project_id",
                mapper);
        return count;
    }

    private long stream(
            UUID workspaceId,
            Writer out,
            String table,
            String columns,
            String cursorColumn,
            org.springframework.jdbc.core.RowMapper<java.util.LinkedHashMap<String, Object>> mapper)
            throws IOException {
        return WorkspaceExportStreaming.streamByColumn(
                jdbc, objectMapper, out, workspaceId, table, columns, cursorColumn, PAGE_SIZE, mapper);
    }
}
