package com.mrrorigin.billing;

import tools.jackson.databind.ObjectMapper;

/**
 * Durable resume position for one connection's backfill, persisted verbatim as JSON in the
 * existing {@code stripe_connections.sync_checkpoint} column (reserved for #12 since V3). {@code
 * cursor} is the last successfully imported Stripe object ID within {@code phase} -- the next
 * page resumes with {@code starting_after=cursor}, or starts the phase over from the beginning if
 * {@code cursor} is null.
 */
record StripeBackfillCheckpoint(StripeBackfillPhase phase, String cursor) {

    static final StripeBackfillCheckpoint INITIAL = new StripeBackfillCheckpoint(StripeBackfillPhase.CUSTOMERS, null);

    static StripeBackfillCheckpoint parse(ObjectMapper objectMapper, String raw) {
        if (raw == null || raw.isBlank()) {
            return INITIAL;
        }
        try {
            return objectMapper.readValue(raw, StripeBackfillCheckpoint.class);
        } catch (RuntimeException malformed) {
            // A checkpoint written by a future/incompatible format should never silently restart
            // an otherwise-healthy backfill from scratch (which would look like data loss followed
            // by full re-import); surface it instead.
            throw new StripeBackfillException("Stored backfill checkpoint could not be parsed");
        }
    }

    String serialize(ObjectMapper objectMapper) {
        return objectMapper.writeValueAsString(this);
    }

    StripeBackfillCheckpoint advancedTo(String newCursor) {
        return new StripeBackfillCheckpoint(phase, newCursor);
    }

    StripeBackfillCheckpoint advancedToNextPhase() {
        return new StripeBackfillCheckpoint(phase.next(), null);
    }

    boolean isComplete() {
        return phase == StripeBackfillPhase.DONE;
    }
}
