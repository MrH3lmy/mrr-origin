package com.mrrorigin.tracking;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Configurable per-project raw-tracking-data retention (#8). A project with no explicit row uses
 * {@link #DEFAULT_RETENTION_DAYS} -- a safe default (over a year, so month-over-month and simple
 * year-over-year comparisons keep working out of the box) rather than immediate deletion.
 */
@Service
public class TrackingRetentionSettingsService {

    static final int DEFAULT_RETENTION_DAYS = 400;
    static final int MIN_RETENTION_DAYS = 1;
    static final int MAX_RETENTION_DAYS = 3650;

    private final JdbcClient jdbc;
    private final Clock clock;

    TrackingRetentionSettingsService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public int retentionDays(UUID workspaceId, UUID projectId) {
        return jdbc.sql("""
                        SELECT retention_days FROM project_tracking_retention_settings
                        WHERE workspace_id = :workspaceId AND project_id = :projectId
                        """)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .query(Integer.class)
                .optional()
                .orElse(DEFAULT_RETENTION_DAYS);
    }

    @Transactional
    public int updateRetentionDays(UUID workspaceId, UUID projectId, int retentionDays) {
        if (retentionDays < MIN_RETENTION_DAYS || retentionDays > MAX_RETENTION_DAYS) {
            throw new IllegalArgumentException(
                    "retentionDays must be between " + MIN_RETENTION_DAYS + " and " + MAX_RETENTION_DAYS);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        jdbc.sql("""
                        INSERT INTO project_tracking_retention_settings
                            (workspace_id, project_id, retention_days, updated_at)
                        VALUES (:workspaceId, :projectId, :retentionDays, :now)
                        ON CONFLICT (project_id) DO UPDATE
                        SET retention_days = EXCLUDED.retention_days, updated_at = EXCLUDED.updated_at
                        """)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("retentionDays", retentionDays)
                .param("now", now)
                .update();
        return retentionDays;
    }
}
