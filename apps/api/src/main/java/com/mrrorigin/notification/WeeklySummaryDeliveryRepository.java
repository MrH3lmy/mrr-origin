package com.mrrorigin.notification;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code weekly_summary_deliveries} access for #59's dispatch/retry loop (plan §4). Directly reuses
 * {@code StripeWebhookNormalizationService}'s claim-then-work-then-fenced-apply pattern: {@link
 * #claimBatch} is a single {@code SELECT ... FOR UPDATE SKIP LOCKED} + {@code UPDATE} transaction that
 * stamps the lease ({@code last_attempted_at}), and {@link #markSent}/{@link #markFailed} are each
 * fenced by that exact claimed value so a worker whose lease has since been reclaimed can never
 * overwrite a newer outcome.
 */
@Repository
class WeeklySummaryDeliveryRepository {

    static final int MAX_ATTEMPTS = 5;
    private static final int MAX_BATCH_SIZE = 100;

    /** Per plan §4c (accepted B5): 1m / 15m / 1h / 6h backoff after attempts 1-4; the 5th failure is terminal. */
    private static final Duration[] BACKOFF = {
        Duration.ofMinutes(1), Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofHours(6)
    };

    private final JdbcClient jdbc;
    private final TransactionTemplate transactionTemplate;

    WeeklySummaryDeliveryRepository(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        // TransactionTemplate, not @Transactional: this class is called both from a scheduled
        // background thread (no ambient transaction) and, in tests, directly -- programmatic
        // transactions guarantee a real committed transaction either way, matching
        // StripeWebhookNormalizationService's own reasoning for using TransactionTemplate.
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** Idempotent: a second call for the same (project, recipient, week) is a silent no-op. */
    void createIfAbsent(
            UUID workspaceId,
            UUID projectId,
            String recipientSubjectId,
            String recipientEmail,
            LocalDate weekStart,
            OffsetDateTime now) {
        jdbc.sql(
                        """
                        INSERT INTO weekly_summary_deliveries
                            (id, workspace_id, project_id, recipient_subject_id, recipient_email, week_start,
                             status, attempt_count, next_attempt_at, created_at, updated_at)
                        VALUES (:id, :workspaceId, :projectId, :recipientSubjectId, :recipientEmail, :weekStart,
                                'PENDING', 0, :now, :now, :now)
                        ON CONFLICT (project_id, recipient_subject_id, week_start) DO NOTHING
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("recipientSubjectId", recipientSubjectId)
                .param("recipientEmail", recipientEmail)
                .param("weekStart", weekStart)
                .param("now", now)
                .update();
    }

    List<ClaimedDelivery> claimBatch(int batchSize, OffsetDateTime now) {
        if (batchSize <= 0 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("batchSize must be between 1 and " + MAX_BATCH_SIZE);
        }
        List<ClaimedDelivery> claimed = transactionTemplate.execute(status -> jdbc.sql(
                        """
                        WITH claimable AS (
                            SELECT id FROM weekly_summary_deliveries
                            WHERE status IN ('PENDING', 'FAILED') AND next_attempt_at <= :now
                            ORDER BY next_attempt_at ASC
                            LIMIT :batchSize
                            FOR UPDATE SKIP LOCKED
                        ),
                        updated AS (
                            UPDATE weekly_summary_deliveries wsd
                            SET status = 'SENDING', last_attempted_at = :now, attempt_count = attempt_count + 1, updated_at = :now
                            FROM claimable
                            WHERE wsd.id = claimable.id
                            RETURNING wsd.id, wsd.workspace_id, wsd.project_id, wsd.recipient_subject_id,
                                      wsd.recipient_email, wsd.week_start, wsd.attempt_count, wsd.last_attempted_at
                        )
                        SELECT * FROM updated
                        """)
                .param("batchSize", batchSize)
                .param("now", now)
                .query((rs, rowNum) -> new ClaimedDelivery(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("workspace_id")),
                        UUID.fromString(rs.getString("project_id")),
                        rs.getString("recipient_subject_id"),
                        rs.getString("recipient_email"),
                        rs.getObject("week_start", LocalDate.class),
                        rs.getInt("attempt_count"),
                        rs.getObject("last_attempted_at", OffsetDateTime.class)))
                .list());
        return claimed == null ? List.of() : claimed;
    }

    /** Same claim semantics as {@link #claimBatch}, scoped to one project (manual send trigger, #59). */
    List<ClaimedDelivery> claimBatchForProject(UUID projectId, int batchSize, OffsetDateTime now) {
        if (batchSize <= 0 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("batchSize must be between 1 and " + MAX_BATCH_SIZE);
        }
        List<ClaimedDelivery> claimed = transactionTemplate.execute(status -> jdbc.sql(
                        """
                        WITH claimable AS (
                            SELECT id FROM weekly_summary_deliveries
                            WHERE project_id = :projectId AND status IN ('PENDING', 'FAILED') AND next_attempt_at <= :now
                            ORDER BY next_attempt_at ASC
                            LIMIT :batchSize
                            FOR UPDATE SKIP LOCKED
                        ),
                        updated AS (
                            UPDATE weekly_summary_deliveries wsd
                            SET status = 'SENDING', last_attempted_at = :now, attempt_count = attempt_count + 1, updated_at = :now
                            FROM claimable
                            WHERE wsd.id = claimable.id
                            RETURNING wsd.id, wsd.workspace_id, wsd.project_id, wsd.recipient_subject_id,
                                      wsd.recipient_email, wsd.week_start, wsd.attempt_count, wsd.last_attempted_at
                        )
                        SELECT * FROM updated
                        """)
                .param("projectId", projectId)
                .param("batchSize", batchSize)
                .param("now", now)
                .query((rs, rowNum) -> new ClaimedDelivery(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("workspace_id")),
                        UUID.fromString(rs.getString("project_id")),
                        rs.getString("recipient_subject_id"),
                        rs.getString("recipient_email"),
                        rs.getObject("week_start", LocalDate.class),
                        rs.getInt("attempt_count"),
                        rs.getObject("last_attempted_at", OffsetDateTime.class)))
                .list());
        return claimed == null ? List.of() : claimed;
    }

    boolean markSent(UUID id, OffsetDateTime claimedAt, String providerMessageId, OffsetDateTime now) {
        return jdbc.sql(
                        """
                        UPDATE weekly_summary_deliveries
                        SET status = 'SENT', provider_message_id = :providerMessageId, last_error = NULL, updated_at = :now
                        WHERE id = :id AND status = 'SENDING' AND last_attempted_at = :claimedAt
                        """)
                .param("id", id)
                .param("claimedAt", claimedAt)
                .param("providerMessageId", providerMessageId)
                .param("now", now)
                .update()
                == 1;
    }

    /** {@code attemptCount} is the post-claim count (already incremented by {@link #claimBatch}). */
    boolean markFailed(UUID id, OffsetDateTime claimedAt, String error, boolean permanent, int attemptCount, OffsetDateTime now) {
        boolean terminal = permanent || attemptCount >= MAX_ATTEMPTS;
        String nextStatus = terminal ? "PERMANENTLY_FAILED" : "FAILED";
        OffsetDateTime nextAttemptAt = terminal ? now : now.plus(BACKOFF[attemptCount - 1]);
        return jdbc.sql(
                        """
                        UPDATE weekly_summary_deliveries
                        SET status = :nextStatus, last_error = :error, next_attempt_at = :nextAttemptAt, updated_at = :now
                        WHERE id = :id AND status = 'SENDING' AND last_attempted_at = :claimedAt
                        """)
                .param("id", id)
                .param("claimedAt", claimedAt)
                .param("nextStatus", nextStatus)
                .param("error", error)
                .param("nextAttemptAt", nextAttemptAt)
                .param("now", now)
                .update()
                == 1;
    }

    /** Bounded recent-delivery view for the manager-facing status endpoint (#59, plan §4d). */
    List<DeliveryStatusRow> listRecent(UUID workspaceId, UUID projectId, int limit) {
        return jdbc.sql(
                        """
                        SELECT id, recipient_email, week_start, status, attempt_count, last_error,
                               provider_message_id, created_at, updated_at
                        FROM weekly_summary_deliveries
                        WHERE workspace_id = :workspaceId AND project_id = :projectId
                        ORDER BY created_at DESC, id DESC
                        LIMIT :limit
                        """)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("limit", limit)
                .query((rs, rowNum) -> new DeliveryStatusRow(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("recipient_email"),
                        rs.getObject("week_start", LocalDate.class),
                        rs.getString("status"),
                        rs.getInt("attempt_count"),
                        rs.getString("last_error"),
                        rs.getString("provider_message_id"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)))
                .list();
    }

    record DeliveryStatusRow(
            UUID id,
            String recipientEmail,
            LocalDate weekStart,
            String status,
            int attemptCount,
            String lastError,
            String providerMessageId,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    record ClaimedDelivery(
            UUID id,
            UUID workspaceId,
            UUID projectId,
            String recipientSubjectId,
            String recipientEmail,
            LocalDate weekStart,
            int attemptCount,
            OffsetDateTime lastAttemptedAt) {}
}
