package com.mrrorigin.reporting;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

/**
 * Append-only audit trail (V18) for #26's CSV exports, modeled directly on {@code
 * CustomerLinkRepairAuditService}/V17: one row per export call recording who exported what view with
 * which filters and how many rows -- never the exported row content itself. {@code filters} is a
 * JSONB blob of query parameters only (period, dimension, source/campaign, retention age); no
 * customer identifier, monetary total, or other exported data is ever written here.
 */
@Service
class ExportAuditService {
    private final JdbcClient db;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    ExportAuditService(JdbcClient db, ObjectMapper objectMapper, Clock clock) {
        this.db = db;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    void record(
            UUID workspaceId,
            UUID projectId,
            String exportType,
            String schemaVersion,
            String actorSubjectId,
            Map<String, Object> filters,
            long rowCount) {
        db.sql(
                        """
                        INSERT INTO export_audit_log
                            (id, workspace_id, project_id, export_type, schema_version, actor_subject_id,
                             filters, row_count, created_at)
                        VALUES (:id, :w, :p, :type, :schemaVersion, :actor, :filters::jsonb, :rowCount, :at)
                        """)
                .param("id", UUID.randomUUID())
                .param("w", workspaceId)
                .param("p", projectId)
                .param("type", exportType)
                .param("schemaVersion", schemaVersion)
                .param("actor", actorSubjectId)
                .param("filters", objectMapper.writeValueAsString(filters))
                .param("rowCount", rowCount)
                .param("at", OffsetDateTime.now(clock))
                .update();
    }
}
