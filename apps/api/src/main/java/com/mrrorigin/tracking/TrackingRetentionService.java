package com.mrrorigin.tracking;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bounded, cutoff-based retention deletion for a project's raw tracking data (#8): {@code
 * tracking_event_envelopes}, the {@code tracking_ingestion_batches} they were received in, and {@code
 * tracking_ingestion_failures} diagnostics. See V15's migration comment for why visitors, sessions,
 * touchpoints, and identity links are deliberately out of scope -- they double as acquisition
 * evidence that {@code customer_attribution_results} references with {@code ON DELETE RESTRICT}.
 *
 * <p><b>Resumability and idempotency.</b> Each call deletes at most {@code maxRows} matching rows per
 * table using a plain cutoff predicate (no persisted checkpoint is needed: "older than the cutoff"
 * is itself the resume position). A crash or exception mid-batch rolls back the whole transaction,
 * leaving every row exactly where it was; the next call re-evaluates the same predicate and simply
 * deletes what still qualifies. Calling this after nothing more qualifies is a safe no-op ({@link
 * RetentionRunOutcome#complete()} {@code true}, all counts zero).
 *
 * <p>Envelopes are deleted before batches because {@code fk_event_envelopes_ingestion_batch} (V4) is
 * {@code ON DELETE RESTRICT}: a batch can only be removed once every envelope that references it is
 * gone, so only batches with zero remaining envelopes are ever deleted here.
 */
@Service
public class TrackingRetentionService {

    private final JdbcClient jdbc;
    private final TrackingRetentionSettingsService settings;
    private final Clock clock;

    TrackingRetentionService(JdbcClient jdbc, TrackingRetentionSettingsService settings, Clock clock) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional
    public RetentionRunOutcome runBatch(UUID workspaceId, UUID projectId, int maxRows) {
        if (maxRows <= 0) {
            throw new IllegalArgumentException("maxRows must be positive");
        }
        int retentionDays = settings.retentionDays(workspaceId, projectId);
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minusDays(retentionDays);

        int envelopesDeleted = deleteBounded(
                "tracking_event_envelopes", "received_at", workspaceId, projectId, cutoff, maxRows, null);
        int batchesDeleted = deleteBounded(
                "tracking_ingestion_batches", "received_at", workspaceId, projectId, cutoff, maxRows,
                "NOT EXISTS (SELECT 1 FROM tracking_event_envelopes e WHERE e.ingestion_batch_id = t.id) ");
        int failuresDeleted = deleteBounded(
                "tracking_ingestion_failures", "occurred_at", workspaceId, projectId, cutoff, maxRows, null);

        boolean complete = envelopesDeleted < maxRows && batchesDeleted < maxRows && failuresDeleted < maxRows;
        return new RetentionRunOutcome(retentionDays, cutoff, envelopesDeleted, batchesDeleted, failuresDeleted, complete);
    }

    private int deleteBounded(String table, String timestampColumn, UUID workspaceId, UUID projectId,
            OffsetDateTime cutoff, int maxRows, String extraCondition) {
        String extra = extraCondition == null ? "" : " AND " + extraCondition;
        return jdbc.sql("""
                        DELETE FROM %s
                        WHERE id IN (
                            SELECT t.id FROM %s t
                            WHERE t.workspace_id = :workspaceId AND t.project_id = :projectId AND t.%s < :cutoff%s
                            LIMIT :maxRows
                        )
                        """.formatted(table, table, timestampColumn, extra))
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("cutoff", cutoff)
                .param("maxRows", maxRows)
                .update();
    }

    public record RetentionRunOutcome(
            int retentionDays,
            OffsetDateTime cutoff,
            int envelopesDeleted,
            int batchesDeleted,
            int failuresDeleted,
            boolean complete) {}
}
