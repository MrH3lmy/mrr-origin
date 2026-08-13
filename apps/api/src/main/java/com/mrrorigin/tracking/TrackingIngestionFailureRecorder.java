package com.mrrorigin.tracking;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records bounded, project-scoped public-ingestion rejections (#8) so {@link
 * TrackingProjectDiagnosticsService} can explain why a founder's tracker install looks like it is
 * producing no data. See V14's migration comment for exactly what each failure kind means and why no
 * column here can ever hold a raw ingestion key, its hash, a request body, or an event payload.
 *
 * <p>Recording runs in its own new transaction ({@link Propagation#REQUIRES_NEW}) so a diagnostic
 * write can never be rolled back by -- or itself affect -- the outcome of the request it is
 * describing; every call site here is about to reject that request anyway.
 */
@Service
class TrackingIngestionFailureRecorder {

    /** Keeps this table's growth bounded per (project, kind) even though ingestion has no rate limiting yet. */
    static final int MAX_ROWS_PER_PROJECT_AND_KIND = 500;

    private final JdbcClient jdbc;
    private final Clock clock;

    TrackingIngestionFailureRecorder(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void recordBlockedOrigin(UUID workspaceId, UUID projectId, String normalizedOriginOrNull) {
        record(workspaceId, projectId, "BLOCKED_ORIGIN", normalizedOriginOrNull);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void recordInvalidKey(UUID workspaceId, UUID projectId) {
        record(workspaceId, projectId, "INVALID_KEY", null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void recordInvalidPayload(UUID workspaceId, UUID projectId) {
        record(workspaceId, projectId, "INVALID_PAYLOAD", null);
    }

    private void record(UUID workspaceId, UUID projectId, String kind, String detail) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        jdbc.sql("""
                        INSERT INTO tracking_ingestion_failures
                            (id, workspace_id, project_id, kind, detail, occurred_at)
                        VALUES (:id, :workspaceId, :projectId, :kind, :detail, :occurredAt)
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("kind", kind)
                .param("detail", detail)
                .param("occurredAt", now)
                .update();
        trim(workspaceId, projectId, kind);
    }

    private void trim(UUID workspaceId, UUID projectId, String kind) {
        jdbc.sql("""
                        DELETE FROM tracking_ingestion_failures
                        WHERE id IN (
                            SELECT id FROM tracking_ingestion_failures
                            WHERE workspace_id = :workspaceId AND project_id = :projectId AND kind = :kind
                            ORDER BY occurred_at DESC
                            OFFSET :keep
                        )
                        """)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("kind", kind)
                .param("keep", MAX_ROWS_PER_PROJECT_AND_KIND)
                .update();
    }
}
