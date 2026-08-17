package com.mrrorigin.notification;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-(project, member) weekly-summary opt-out (#59, plan §3). Absent row means subscribed (if
 * otherwise eligible); a row means this member does not want the summary for this specific project.
 * Modeled directly on {@code TrackingRetentionSettingsService} (V15).
 */
@Service
public class WeeklySummaryOptOutService {

    private final JdbcClient jdbc;
    private final Clock clock;

    WeeklySummaryOptOutService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public boolean isOptedOut(UUID workspaceId, UUID projectId, String subjectId) {
        return jdbc.sql("""
                        SELECT 1 FROM weekly_summary_opt_outs
                        WHERE workspace_id = :workspaceId AND project_id = :projectId AND subject_id = :subjectId
                        """)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("subjectId", subjectId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /** For the dispatch scheduler (#59), which needs the full opted-out set for one project, not a single check. */
    @Transactional(readOnly = true)
    Set<String> optedOutSubjectIds(UUID workspaceId, UUID projectId) {
        return Set.copyOf(jdbc.sql("""
                        SELECT subject_id FROM weekly_summary_opt_outs
                        WHERE workspace_id = :workspaceId AND project_id = :projectId
                        """)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .query(String.class)
                .list());
    }

    @Transactional
    public boolean setOptedOut(UUID workspaceId, UUID projectId, String subjectId, boolean optedOut) {
        if (optedOut) {
            OffsetDateTime now = OffsetDateTime.now(clock);
            jdbc.sql("""
                            INSERT INTO weekly_summary_opt_outs (workspace_id, project_id, subject_id, opted_out_at)
                            VALUES (:workspaceId, :projectId, :subjectId, :now)
                            ON CONFLICT (project_id, subject_id) DO NOTHING
                            """)
                    .param("workspaceId", workspaceId)
                    .param("projectId", projectId)
                    .param("subjectId", subjectId)
                    .param("now", now)
                    .update();
        } else {
            jdbc.sql("""
                            DELETE FROM weekly_summary_opt_outs
                            WHERE workspace_id = :workspaceId AND project_id = :projectId AND subject_id = :subjectId
                            """)
                    .param("workspaceId", workspaceId)
                    .param("projectId", projectId)
                    .param("subjectId", subjectId)
                    .update();
        }
        return optedOut;
    }
}
