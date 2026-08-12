package com.mrrorigin.billing;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Bounded, idempotent, workspace-scoped replay of {@code FAILED} raw Stripe webhook events (#15).
 *
 * <p>Replay never rewrites {@code raw_payload}/{@code payload} -- the immutable evidence Stripe
 * originally sent. It only resets the same bookkeeping columns {@link
 * StripeWebhookNormalizationService} itself owns ({@code processing_state}, {@code failure_kind},
 * {@code last_error}, {@code last_attempted_at}) back to their pre-attempt state, and stamps {@code
 * replay_count}/{@code last_replayed_at} (reserved for exactly this purpose since V5). A replayed
 * event is picked back up and reprocessed by the ordinary claim/apply pipeline the next time it
 * runs -- replay never reprocesses inline -- so every crash-safety and idempotency guarantee {@link
 * StripeWebhookNormalizationService} already has for that pipeline (lease fencing, at-most-once
 * ledger application) applies unchanged to a replayed event. This is also what makes an interrupted
 * replay safe to retry: nothing about replay itself can be "half done" beyond the single atomic
 * UPDATE that requeues the row, and reprocessing after that point is the pipeline's own concern.
 *
 * <p>Every transition is a single atomic conditional UPDATE guarded by {@code processing_state =
 * 'FAILED'} (single event, or a {@code FOR UPDATE SKIP LOCKED} candidate CTE for a bounded batch),
 * so concurrent replay attempts against the same event(s) can never double-count {@code
 * replay_count} or race each other: at most one caller's UPDATE ever matches a given row, and every
 * other concurrent caller simply sees it as already-not-FAILED.
 */
@Service
class StripeWebhookReplayService {

    /** Mirrors StripeWebhookNormalizationService.MAX_BATCH_SIZE; kept independent since these are separate bounds. */
    private static final int MAX_BATCH_SIZE = 100;

    private final JdbcClient jdbc;

    StripeWebhookReplayService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Replays exactly one event, scoped to {@code workspaceId} so a caller can never replay (or even
     * discover the existence of) another workspace's event. Idempotent: replaying an event that is
     * not currently {@code FAILED} (already replayed by a concurrent caller, already reprocessed, or
     * never failed) is a no-op that reports {@link ReplayOutcome#NOT_ELIGIBLE} rather than an error.
     *
     * @throws ResponseStatusException 404 if no event with this id exists in this workspace at all
     */
    @Transactional
    ReplayOutcome replayEvent(UUID workspaceId, UUID eventId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        boolean replayed = jdbc.sql(
                        """
                        UPDATE stripe_webhook_events
                        SET processing_state = 'PENDING', failure_kind = NULL, last_error = NULL,
                            last_attempted_at = NULL, replay_count = replay_count + 1,
                            last_replayed_at = :now, updated_at = :now
                        WHERE id = :id AND workspace_id = :workspaceId AND processing_state = 'FAILED'
                        """)
                .param("id", eventId)
                .param("workspaceId", workspaceId)
                .param("now", now)
                .update()
                == 1;
        if (replayed) {
            return ReplayOutcome.REPLAYED;
        }

        boolean existsInWorkspace = jdbc.sql(
                        "SELECT 1 FROM stripe_webhook_events WHERE id = :id AND workspace_id = :workspaceId")
                .param("id", eventId)
                .param("workspaceId", workspaceId)
                .query(Integer.class)
                .optional()
                .isPresent();
        if (!existsInWorkspace) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Stripe webhook event not found");
        }
        return ReplayOutcome.NOT_ELIGIBLE;
    }

    /**
     * Replays up to {@code maxEvents} of this workspace's currently-{@code FAILED} events, oldest
     * ({@code received_at}) first. Safe to call repeatedly and concurrently: each call only ever
     * claims and requeues events it itself locks via {@code SKIP LOCKED}, so two overlapping batch
     * replays for the same workspace partition the failed backlog between them rather than racing.
     */
    @Transactional
    BatchReplayOutcome replayFailed(UUID workspaceId, int maxEvents) {
        if (maxEvents <= 0 || maxEvents > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("maxEvents must be between 1 and " + MAX_BATCH_SIZE);
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<UUID> replayedIds = jdbc.sql(
                        """
                        WITH candidates AS (
                            SELECT id FROM stripe_webhook_events
                            WHERE workspace_id = :workspaceId AND processing_state = 'FAILED'
                            ORDER BY received_at ASC
                            LIMIT :maxEvents
                            FOR UPDATE SKIP LOCKED
                        )
                        UPDATE stripe_webhook_events swe
                        SET processing_state = 'PENDING', failure_kind = NULL, last_error = NULL,
                            last_attempted_at = NULL, replay_count = replay_count + 1,
                            last_replayed_at = :now, updated_at = :now
                        FROM candidates
                        WHERE swe.id = candidates.id
                        RETURNING swe.id
                        """)
                .param("workspaceId", workspaceId)
                .param("maxEvents", maxEvents)
                .param("now", now)
                .query((rs, rowNum) -> UUID.fromString(rs.getString("id")))
                .list();
        return new BatchReplayOutcome(replayedIds);
    }

    enum ReplayOutcome {
        REPLAYED,
        NOT_ELIGIBLE
    }

    record BatchReplayOutcome(List<UUID> replayedEventIds) {
        int count() {
            return replayedEventIds.size();
        }
    }
}
