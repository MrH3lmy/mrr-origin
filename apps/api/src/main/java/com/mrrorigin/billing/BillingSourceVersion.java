package com.mrrorigin.billing;

import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Computes {@code billing_*.source_version}: a composite ordering key combining a Stripe-meaningful
 * epoch-second timestamp (which object state is objectively newer, across different seconds) with a
 * deterministic sub-second tie-breaker (which of two same-second deliveries wins). Second-resolution
 * alone is insufficient -- Stripe's {@code created} field on events and objects is second-precision,
 * but multiple distinct events for the same object can legitimately occur within one second, and a
 * plain second-level version guard cannot tell them apart or order them consistently.
 *
 * <p>The tie-breaker must be intrinsic to the event, not to processing order: replaying the same
 * stored events in a different order must always converge on the same final state. For webhook
 * events, {@code received_at} (V5, set once at insert time and never touched again) is exactly that
 * -- an immutable, deterministic proxy for real arrival order, independent of whatever order the
 * normalization worker later happens to drain the PENDING queue in.
 */
final class BillingSourceVersion {

    /**
     * Width of the low-order tie-breaker slot: wide enough for microsecond resolution (0..999,999)
     * plus the backfill sentinel above it, while `epochSeconds * SECOND_SCALE` stays comfortably
     * inside a signed 64-bit range for any realistic timestamp.
     */
    private static final long SECOND_SCALE = 2_000_000L;

    /**
     * A backfill snapshot always wins a same-second tie against a webhook event: a backfill GET
     * reflects Stripe's true live state as of the instant the request was issued, which is at or
     * after any change whose triggering webhook event's `created` second is <= that same second --
     * so treating it as maximally recent within that second is correct, not arbitrary.
     */
    private static final long BACKFILL_TIE_BREAKER = 1_000_000L;

    private BillingSourceVersion() {}

    static long forWebhookEvent(OffsetDateTime stripeCreatedAt, OffsetDateTime receivedAt) {
        long microsecondOfSecond = receivedAt.getNano() / 1_000;
        return stripeCreatedAt.toEpochSecond() * SECOND_SCALE + microsecondOfSecond;
    }

    static long forBackfillFetch(Instant fetchStartedAt) {
        return fetchStartedAt.getEpochSecond() * SECOND_SCALE + BACKFILL_TIE_BREAKER;
    }
}
