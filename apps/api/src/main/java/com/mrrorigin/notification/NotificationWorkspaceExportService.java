package com.mrrorigin.notification;

import java.io.IOException;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.mrrorigin.workspace.WorkspaceExportStreaming;

import tools.jackson.databind.ObjectMapper;

/**
 * The notification module's own slice of #64's cross-module workspace export: streams every row this
 * module owns as NDJSON. Mirrors {@code NotificationWorkspaceDataDeletionService}'s owned-table list
 * ({@code weekly_summary_deliveries}, {@code weekly_summary_opt_outs}) but reads instead of deletes.
 *
 * <p>{@code weekly_summary_deliveries.lease_token}/{@code .lease_until} are deliberately excluded per
 * #64's accepted contract ("lease tokens") -- the exact fencing token/expiry
 * {@code WeeklySummaryDeliveryRepository#claim} uses to serialize delivery attempts across instances,
 * not workspace business data.
 *
 * <p>{@code weekly_summary_opt_outs} has no single-column primary key ({@code PRIMARY KEY (project_id,
 * subject_id)}), so it streams with its own composite-keyset cursor rather than {@link
 * WorkspaceExportStreaming#streamByColumn}, which only supports a single UUID cursor column.
 */
@Service
public class NotificationWorkspaceExportService {

    private static final int PAGE_SIZE = 500;

    // lease_token / lease_until deliberately omitted -- see class Javadoc.
    private static final String DELIVERY_COLUMNS = """
            id, workspace_id, project_id, recipient_subject_id, recipient_email, week_start, status,
            attempt_count, last_attempted_at, last_outcome_ambiguous, next_attempt_at, last_error,
            provider_message_id, created_at, updated_at
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    NotificationWorkspaceExportService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public long writeNdjson(UUID workspaceId, Writer out) throws IOException {
        long count = WorkspaceExportStreaming.streamByColumn(
                jdbc, objectMapper, out, workspaceId, "weekly_summary_deliveries", DELIVERY_COLUMNS, "id", PAGE_SIZE,
                WorkspaceExportStreaming.genericMapper(objectMapper));
        count += streamOptOuts(workspaceId, out);
        return count;
    }

    private long streamOptOuts(UUID workspaceId, Writer out) throws IOException {
        long total = 0;
        UUID cursorProjectId = null;
        String cursorSubjectId = null;
        while (true) {
            List<LinkedHashMap<String, Object>> page = cursorProjectId == null
                    ? jdbc.sql("""
                            SELECT workspace_id, project_id, subject_id, opted_out_at
                            FROM weekly_summary_opt_outs
                            WHERE workspace_id = :w
                            ORDER BY project_id, subject_id
                            LIMIT :size
                            """)
                            .param("w", workspaceId)
                            .param("size", PAGE_SIZE)
                            .query(WorkspaceExportStreaming.genericMapper(objectMapper))
                            .list()
                    : jdbc.sql("""
                            SELECT workspace_id, project_id, subject_id, opted_out_at
                            FROM weekly_summary_opt_outs
                            WHERE workspace_id = :w AND (project_id, subject_id) > (:cp, :cs)
                            ORDER BY project_id, subject_id
                            LIMIT :size
                            """)
                            .param("w", workspaceId)
                            .param("cp", cursorProjectId)
                            .param("cs", cursorSubjectId)
                            .param("size", PAGE_SIZE)
                            .query(WorkspaceExportStreaming.genericMapper(objectMapper))
                            .list();
            for (LinkedHashMap<String, Object> row : page) {
                row.put("table", "weekly_summary_opt_outs");
                out.write(objectMapper.writeValueAsString(row));
                out.write('\n');
            }
            total += page.size();
            if (page.size() < PAGE_SIZE) {
                return total;
            }
            LinkedHashMap<String, Object> last = page.get(page.size() - 1);
            cursorProjectId = (UUID) last.get("project_id");
            cursorSubjectId = (String) last.get("subject_id");
        }
    }
}
