package com.mrrorigin.notification;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code weekly_summary_deliveries} access for #59's dispatch/retry loop (plan §4). Directly reuses
 * {@code StripeWebhookNormalizationService}'s claim-then-work-then-fenced-apply pattern, corrected to
 * fence by an explicit random {@code lease_token} rather than a timestamp: {@link #claimBatch} is a
 * single {@code SELECT ... FOR UPDATE SKIP LOCKED} + {@code UPDATE} transaction that stamps a fresh
 * lease ({@code lease_token}/{@code lease_until}), and {@link #markSent}/{@link #markFailed} are each
 * fenced by that exact claimed token so a worker whose lease has since been reclaimed can never
 * overwrite a newer outcome. An expired {@code SENDING} lease (worker died/restarted mid-attempt) is
 * itself reclaimable by {@link #claimBatch} once {@code lease_until} has passed.
 */
@Repository
class WeeklySummaryDeliveryRepository {

    /** Accepted B5 correction: 6 total attempts (was 5), same 1m/15m/1h/6h/24h backoff shape. */
    static final int MAX_ATTEMPTS = 6;
    private static final int MAX_BATCH_SIZE = 100;

    /** Per plan §4c (accepted B5): 1m / 15m / 1h / 6h / 24h backoff after attempts 1-5; the 6th failure is terminal. */
    private static final Duration[] BACKOFF = {
        Duration.ofMinutes(1), Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofHours(6), Duration.ofHours(24)
    };

    /** Generous relative to Postmark's default 10s request timeout; bounds how long a dead worker can hold a lease. */
    private static final Duration LEASE_DURATION = Duration.ofMinutes(10);

    /** Bounds {@code last_error} length; never stores credentials, message bodies, or raw provider responses (plan §6). */
    private static final int MAX_ERROR_LENGTH = 500;

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

    /**
     * Idempotent: a second call for the same (project, recipient, week) is a silent no-op.
     * {@code recipientEmail} may be null -- accepted B3 correction: a recipient with no verified email
     * yet is recorded as an auditable {@code BLOCKED_MISSING_EMAIL} row, not silently skipped.
     */
    void createIfAbsent(
            UUID workspaceId,
            UUID projectId,
            String recipientSubjectId,
            String recipientEmail,
            LocalDate weekStart,
            OffsetDateTime now) {
        boolean hasEmail = recipientEmail != null && !recipientEmail.isBlank();
        jdbc.sql(
                        """
                        INSERT INTO weekly_summary_deliveries
                            (id, workspace_id, project_id, recipient_subject_id, recipient_email, week_start,
                             status, attempt_count, next_attempt_at, created_at, updated_at)
                        VALUES (:id, :workspaceId, :projectId, :recipientSubjectId, :recipientEmail, :weekStart,
                                :status, 0, :now, :now, :now)
                        ON CONFLICT (project_id, recipient_subject_id, week_start) DO NOTHING
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("recipientSubjectId", recipientSubjectId)
                .param("recipientEmail", hasEmail ? recipientEmail : null)
                .param("weekStart", weekStart)
                .param("status", hasEmail ? "PENDING" : "BLOCKED_MISSING_EMAIL")
                .param("now", now)
                .update();
    }

    List<ClaimedDelivery> claimBatch(int batchSize, OffsetDateTime now) {
        return claim(null, batchSize, now);
    }

    /** Same claim semantics as {@link #claimBatch}, scoped to one project (manual send trigger, #59). */
    List<ClaimedDelivery> claimBatchForProject(UUID projectId, int batchSize, OffsetDateTime now) {
        return claim(projectId, batchSize, now);
    }

    /** {@code projectId} null means unscoped (the scheduler's own tick); non-null scopes to one project (manual trigger). */
    private List<ClaimedDelivery> claim(UUID projectId, int batchSize, OffsetDateTime now) {
        if (batchSize <= 0 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("batchSize must be between 1 and " + MAX_BATCH_SIZE);
        }
        boolean scopedToProject = projectId != null;
        UUID leaseToken = UUID.randomUUID();
        OffsetDateTime leaseUntil = now.plus(LEASE_DURATION);
        String projectFilter = scopedToProject ? "project_id = :projectId AND " : "";
        String sql =
                """
                WITH claimable AS (
                    SELECT id FROM weekly_summary_deliveries
                    WHERE %s (
                        (status IN ('PENDING', 'FAILED') AND next_attempt_at <= :now)
                        OR (status = 'SENDING' AND lease_until <= :now)
                    )
                    ORDER BY next_attempt_at ASC
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED
                ),
                updated AS (
                    UPDATE weekly_summary_deliveries wsd
                    SET status = 'SENDING', lease_token = :leaseToken, lease_until = :leaseUntil,
                        last_attempted_at = :now, attempt_count = attempt_count + 1, updated_at = :now
                    FROM claimable
                    WHERE wsd.id = claimable.id
                    RETURNING wsd.id, wsd.workspace_id, wsd.project_id, wsd.recipient_subject_id,
                              wsd.recipient_email, wsd.week_start, wsd.attempt_count, wsd.lease_token
                )
                SELECT * FROM updated
                """
                        .formatted(projectFilter);
        var unscopedSpec = jdbc.sql(sql)
                .param("batchSize", batchSize)
                .param("leaseToken", leaseToken)
                .param("leaseUntil", leaseUntil)
                .param("now", now);
        var spec = scopedToProject ? unscopedSpec.param("projectId", projectId) : unscopedSpec;
        List<ClaimedDelivery> claimed = transactionTemplate.execute(status -> spec.query((rs, rowNum) -> new ClaimedDelivery(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("workspace_id")),
                        UUID.fromString(rs.getString("project_id")),
                        rs.getString("recipient_subject_id"),
                        rs.getString("recipient_email"),
                        rs.getObject("week_start", LocalDate.class),
                        rs.getInt("attempt_count"),
                        UUID.fromString(rs.getString("lease_token"))))
                .list());
        return claimed == null ? List.of() : claimed;
    }

    boolean markSent(UUID id, UUID leaseToken, String providerMessageId, OffsetDateTime now) {
        return jdbc.sql(
                        """
                        UPDATE weekly_summary_deliveries
                        SET status = 'SENT', provider_message_id = :providerMessageId, last_error = NULL,
                            last_outcome_ambiguous = FALSE, lease_token = NULL, lease_until = NULL, updated_at = :now
                        WHERE id = :id AND status = 'SENDING' AND lease_token = :leaseToken
                        """)
                .param("id", id)
                .param("leaseToken", leaseToken)
                .param("providerMessageId", providerMessageId)
                .param("now", now)
                .update()
                == 1;
    }

    /** {@code attemptCount} is the post-claim count (already incremented by {@link #claimBatch}). */
    boolean markFailed(
            UUID id,
            UUID leaseToken,
            String error,
            boolean permanent,
            boolean ambiguous,
            int attemptCount,
            OffsetDateTime now) {
        boolean terminal = permanent || attemptCount >= MAX_ATTEMPTS;
        String nextStatus = terminal ? "PERMANENTLY_FAILED" : "FAILED";
        OffsetDateTime nextAttemptAt = terminal ? now : now.plus(BACKOFF[attemptCount - 1]);
        return jdbc.sql(
                        """
                        UPDATE weekly_summary_deliveries
                        SET status = :nextStatus, last_error = :error, last_outcome_ambiguous = :ambiguous,
                            next_attempt_at = :nextAttemptAt, lease_token = NULL, lease_until = NULL, updated_at = :now
                        WHERE id = :id AND status = 'SENDING' AND lease_token = :leaseToken
                        """)
                .param("id", id)
                .param("leaseToken", leaseToken)
                .param("nextStatus", nextStatus)
                .param("error", sanitize(error))
                .param("ambiguous", ambiguous)
                .param("nextAttemptAt", nextAttemptAt)
                .param("now", now)
                .update()
                == 1;
    }

    private static String sanitize(String error) {
        if (error == null) {
            return null;
        }
        String singleLine = error.replaceAll("[\\r\\n\\t]+", " ").strip();
        return singleLine.length() > MAX_ERROR_LENGTH ? singleLine.substring(0, MAX_ERROR_LENGTH) : singleLine;
    }

    /** For the manager-facing replay endpoint (#59, plan §4d): tenant-scoped lookup by id. */
    Optional<DeliveryRef> findForReplay(UUID workspaceId, UUID projectId, UUID id) {
        return jdbc.sql(
                        """
                        SELECT id, workspace_id, project_id, recipient_subject_id, status
                        FROM weekly_summary_deliveries
                        WHERE id = :id AND workspace_id = :workspaceId AND project_id = :projectId
                        """)
                .param("id", id)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .query((rs, rowNum) -> new DeliveryRef(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("workspace_id")),
                        UUID.fromString(rs.getString("project_id")),
                        rs.getString("recipient_subject_id"),
                        rs.getString("status")))
                .list()
                .stream()
                .findFirst();
    }

    /** Replays a terminal {@code PERMANENTLY_FAILED} row: fresh attempt budget, cleared error/lease. */
    boolean resetPermanentlyFailedForReplay(UUID id, OffsetDateTime now) {
        return jdbc.sql(
                        """
                        UPDATE weekly_summary_deliveries
                        SET status = 'PENDING', attempt_count = 0, next_attempt_at = :now,
                            last_error = NULL, last_outcome_ambiguous = FALSE,
                            lease_token = NULL, lease_until = NULL, updated_at = :now
                        WHERE id = :id AND status = 'PERMANENTLY_FAILED'
                        """)
                .param("id", id)
                .param("now", now)
                .update()
                == 1;
    }

    /** Replays a {@code BLOCKED_MISSING_EMAIL} row once a verified email is available (#59, plan §4d). */
    boolean resolveBlockedMissingEmailForReplay(UUID id, String recipientEmail, OffsetDateTime now) {
        return jdbc.sql(
                        """
                        UPDATE weekly_summary_deliveries
                        SET status = 'PENDING', recipient_email = :recipientEmail, next_attempt_at = :now, updated_at = :now
                        WHERE id = :id AND status = 'BLOCKED_MISSING_EMAIL'
                        """)
                .param("id", id)
                .param("recipientEmail", recipientEmail)
                .param("now", now)
                .update()
                == 1;
    }

    /**
     * 400-day terminal-row retention cleanup (#59, accepted B7 correction, plan §6): deletes {@code
     * SENT}/{@code PERMANENTLY_FAILED}/{@code BLOCKED_MISSING_EMAIL} rows older than {@code cutoff}.
     * Never touches {@code PENDING}/{@code SENDING}/retryable {@code FAILED} rows regardless of age.
     */
    int deleteExpiredTerminal(OffsetDateTime cutoff) {
        return jdbc.sql(
                        """
                        DELETE FROM weekly_summary_deliveries
                        WHERE status IN ('SENT', 'PERMANENTLY_FAILED', 'BLOCKED_MISSING_EMAIL')
                          AND created_at < :cutoff
                        """)
                .param("cutoff", cutoff)
                .update();
    }

    /** Bounded recent-delivery view for the manager-facing status endpoint (#59, plan §4d). */
    List<DeliveryStatusRow> listRecent(UUID workspaceId, UUID projectId, int limit) {
        return jdbc.sql(
                        """
                        SELECT id, recipient_email, week_start, status, attempt_count, last_error,
                               last_outcome_ambiguous, provider_message_id, created_at, updated_at
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
                        rs.getBoolean("last_outcome_ambiguous"),
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
            boolean lastOutcomeAmbiguous,
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
            UUID leaseToken) {}

    record DeliveryRef(UUID id, UUID workspaceId, UUID projectId, String recipientSubjectId, String status) {}
}
