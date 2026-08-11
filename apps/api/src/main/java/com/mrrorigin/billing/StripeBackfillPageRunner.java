package com.mrrorigin.billing;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Applies exactly one already-fetched backfill page and advances the connection's checkpoint, as
 * a single atomic transaction: every normalized row from the page and the checkpoint move commit
 * together, or neither does. This is what makes "restart after failure" safe -- a crash (or an
 * exception thrown while normalizing an item) before this transaction commits leaves the
 * checkpoint exactly where it was, so the next run re-fetches and reapplies the same page, which
 * is harmless because every upsert in {@link BillingLedgerUpsertService} is itself idempotent.
 *
 * <p>The connection row is locked for the transaction ({@link
 * StripeConnectionRepository#findByIdForUpdate}) so two concurrent runs for the same connection
 * cannot both advance the checkpoint from the same starting point and silently lose one page's
 * progress.
 */
@Service
class StripeBackfillPageRunner {

    private final StripeConnectionRepository connections;
    private final ObjectMapper objectMapper;

    StripeBackfillPageRunner(StripeConnectionRepository connections, ObjectMapper objectMapper) {
        this.connections = connections;
        this.objectMapper = objectMapper;
    }

    @Transactional
    PageApplyOutcome applyPage(
            UUID connectionId,
            StripeBackfillPhase expectedPhase,
            String expectedCursor,
            List<JsonNode> items,
            boolean hasMore,
            Consumer<JsonNode> normalizeOne) {
        StripeConnection connection = connections
                .findByIdForUpdate(connectionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Stripe connection not found"));

        // Re-checked under the same lock that guards the checkpoint write: a page fetched while the
        // connection was ACTIVE/VERIFIED must never apply after it was disconnected or revoked out
        // from under an in-flight backfill run.
        if (connection.status() != StripeConnectionStatus.ACTIVE || connection.verificationStatus() != StripeVerificationStatus.VERIFIED) {
            StripeBackfillCheckpoint current = StripeBackfillCheckpoint.parse(objectMapper, connection.syncCheckpoint());
            return PageApplyOutcome.connectionIneligible(current.phase(), current.isComplete());
        }

        StripeBackfillCheckpoint checkpoint = StripeBackfillCheckpoint.parse(objectMapper, connection.syncCheckpoint());

        // Both dimensions must match the position this page was fetched from -- phase alone is not
        // enough: a slow/retried fetch of an earlier page, arriving after a faster concurrent fetch
        // has already advanced the cursor within the SAME phase, must never be allowed to move the
        // checkpoint backwards (which would silently discard forward progress and could re-open an
        // already-completed page range indefinitely).
        if (checkpoint.phase() != expectedPhase || !Objects.equals(checkpoint.cursor(), expectedCursor)) {
            return PageApplyOutcome.stale(checkpoint.phase(), checkpoint.isComplete());
        }

        if (hasMore && lastIdOf(items) == null) {
            // Stripe said more pages exist but gave us nothing to resume from (an empty page, or a
            // trailing item with no usable id): treating this as phase-complete would silently skip
            // the remainder of the phase, and treating it as "no cursor change" would infinite-loop.
            // Reject the whole page instead -- checkpoint and normalized rows both stay untouched --
            // so the caller sees a clear failure rather than either silent outcome.
            throw new StripeBackfillException(
                    "Stripe page reported has_more=true but supplied no valid cursor for phase " + expectedPhase);
        }

        for (JsonNode item : items) {
            normalizeOne.accept(item);
        }

        String lastId = lastIdOf(items);
        StripeBackfillCheckpoint advanced =
                hasMore && lastId != null ? checkpoint.advancedTo(lastId) : checkpoint.advancedToNextPhase();
        connection.applySyncCheckpoint(advanced.serialize(objectMapper));
        connections.saveAndFlush(connection);
        return PageApplyOutcome.applied(advanced.phase(), advanced.isComplete());
    }

    private static String lastIdOf(List<JsonNode> items) {
        if (items.isEmpty()) {
            return null;
        }
        JsonNode id = items.get(items.size() - 1).get("id");
        return id == null ? null : id.textValue();
    }

    enum PageApplyStatus {
        APPLIED,
        STALE,
        CONNECTION_INELIGIBLE
    }

    record PageApplyOutcome(PageApplyStatus status, StripeBackfillPhase phase, boolean complete) {

        static PageApplyOutcome applied(StripeBackfillPhase phase, boolean complete) {
            return new PageApplyOutcome(PageApplyStatus.APPLIED, phase, complete);
        }

        static PageApplyOutcome stale(StripeBackfillPhase phase, boolean complete) {
            return new PageApplyOutcome(PageApplyStatus.STALE, phase, complete);
        }

        static PageApplyOutcome connectionIneligible(StripeBackfillPhase phase, boolean complete) {
            return new PageApplyOutcome(PageApplyStatus.CONNECTION_INELIGIBLE, phase, complete);
        }
    }
}
