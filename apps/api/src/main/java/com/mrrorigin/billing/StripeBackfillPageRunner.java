package com.mrrorigin.billing;

import java.util.List;
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
            java.util.UUID connectionId,
            StripeBackfillPhase expectedPhase,
            List<JsonNode> items,
            boolean hasMore,
            Consumer<JsonNode> normalizeOne) {
        StripeConnection connection = connections
                .findByIdForUpdate(connectionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Stripe connection not found"));
        StripeBackfillCheckpoint checkpoint = StripeBackfillCheckpoint.parse(objectMapper, connection.syncCheckpoint());

        if (checkpoint.phase() != expectedPhase) {
            // The checkpoint moved past this page (already applied by a concurrent run, or the
            // phase already completed) between when the caller decided what to fetch and now.
            // Discard this page rather than reapplying/overwriting newer progress.
            return new PageApplyOutcome(checkpoint.phase(), checkpoint.isComplete());
        }

        for (JsonNode item : items) {
            normalizeOne.accept(item);
        }

        String lastId = lastIdOf(items);
        StripeBackfillCheckpoint advanced =
                hasMore && lastId != null ? checkpoint.advancedTo(lastId) : checkpoint.advancedToNextPhase();
        connection.applySyncCheckpoint(advanced.serialize(objectMapper));
        connections.saveAndFlush(connection);
        return new PageApplyOutcome(advanced.phase(), advanced.isComplete());
    }

    private static String lastIdOf(List<JsonNode> items) {
        if (items.isEmpty()) {
            return null;
        }
        JsonNode id = items.get(items.size() - 1).get("id");
        return id == null ? null : id.textValue();
    }

    record PageApplyOutcome(StripeBackfillPhase phase, boolean complete) {}
}
