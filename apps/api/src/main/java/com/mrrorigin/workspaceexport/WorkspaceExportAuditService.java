package com.mrrorigin.workspaceexport;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

/**
 * Append-only audit trail (V23) for #64's workspace data export, modeled directly on {@code
 * ExportAuditService} (V18): one row per successful export recording who exported the workspace, the
 * schema version, and how many rows per domain -- never the exported row content itself. {@code
 * rowCounts} is a JSONB map of domain name to row count only (e.g. {@code {"billing": 12, ...}}); no
 * customer identifier, monetary amount, or other exported field is ever written here.
 *
 * <p>A new table rather than a reuse of {@code export_audit_log}: that table's {@code project_id} is
 * {@code NOT NULL} and its {@code export_type} CHECK enumerates only the three project-scoped CSV
 * exports (#26) -- both wrong for a workspace-wide, non-project-scoped, six-domain export. See
 * ADR-0009.
 */
@Service
class WorkspaceExportAuditService {
    private final JdbcClient db;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    WorkspaceExportAuditService(JdbcClient db, ObjectMapper objectMapper, Clock clock) {
        this.db = db;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    void record(UUID workspaceId, String schemaVersion, String actorSubjectId, Map<String, Long> rowCounts, long totalRowCount) {
        db.sql(
                        """
                        INSERT INTO workspace_export_audit_log
                            (id, workspace_id, schema_version, actor_subject_id, row_counts, total_row_count, created_at)
                        VALUES (:id, :w, :schemaVersion, :actor, :rowCounts::jsonb, :totalRowCount, :at)
                        """)
                .param("id", UUID.randomUUID())
                .param("w", workspaceId)
                .param("schemaVersion", schemaVersion)
                .param("actor", actorSubjectId)
                .param("rowCounts", objectMapper.writeValueAsString(rowCounts))
                .param("totalRowCount", totalRowCount)
                .param("at", OffsetDateTime.now(clock))
                .update();
    }
}
