package com.mrrorigin.tracking;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tracking module's own slice of #62's cross-module workspace deletion: hard-deletes every table
 * this module owns, across every project in the workspace at once, in the dependency-safe order their
 * foreign keys require (V2/V4's {@code ON DELETE RESTRICT}/{@code CASCADE} graph):
 * {@code tracking_event_envelopes} before {@code tracking_ingestion_batches} (an envelope restricts
 * its batch), then {@code touchpoints} before {@code tracking_sessions} before {@code visitors} (each
 * restricts or cascades from the one before it). Unlike the project-scoped
 * {@code ProjectDataDeletionService} (#8), this never needs to skip a row as still-referenced
 * attribution evidence: by the time the orchestrator reaches this phase, the attribution and identity
 * phases have already cleared {@code customer_attribution_results} and {@code visitor_aliases}, the
 * only rows that ever restrict a delete here, so every sweep below is unconditional.
 *
 * <p>One call sweeps at most one table: it tries each owned table in order and deletes from the first
 * one that still has rows for this workspace, so a caller repeatedly invoking {@link #deleteBatch}
 * drains every table in turn. {@code exhausted()} is true only once a call finds every owned table
 * already empty -- stateless and idempotent, matching every other module's deletion service (see
 * {@code ReportingWorkspaceDataDeletionService}'s Javadoc for why no separate checkpoint is needed).
 */
@Service
public class TrackingWorkspaceDataDeletionService {

    private static final List<String> TABLES_IN_ORDER = List.of(
            "tracking_event_envelopes",
            "tracking_ingestion_batches",
            "tracking_verification_attempts",
            "tracking_ingestion_failures",
            "touchpoints",
            "tracking_sessions",
            "visitors",
            "project_ingestion_keys",
            "project_allowed_domains",
            "project_tracking_retention_settings");

    private final JdbcClient jdbc;

    TrackingWorkspaceDataDeletionService(JdbcClient jdbc) {
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
