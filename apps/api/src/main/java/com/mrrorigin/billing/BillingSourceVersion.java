package com.mrrorigin.billing;

import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Computes {@code billing_*.(source_version, source_sequence)}: an ordering PAIR, compared as a
 * Postgres row value so a tie on the coarse component always falls back to an independently
 * guaranteed-unique one instead of silently colliding.
 *
 * <p>{@code source_version} is the coarse, human-meaningful ordering: which object state is
 * objectively newer, across different seconds. Stripe's {@code created} field on events and
 * objects is only second-precision, and multiple distinct events for the same object can
 * legitimately occur within one second, so second-resolution alone is insufficient.
 *
 * <p>{@code source_sequence} is the tie-breaker: for webhook events, {@code
 * stripe_webhook_events.ingest_sequence} (a {@code GENERATED ALWAYS AS IDENTITY} column --
 * strictly increasing and unique per row by construction, assigned once at insert and immutable
 * thereafter). Because it is fixed at insert time, it is independent of whatever order the
 * normalization worker later happens to process PENDING rows in: replaying the same stored events
 * in a different order always converges on the same final state. This eliminates collisions
 * {@code source_version} alone cannot resolve -- including the adversarial case of two events with
 * identical {@code stripe_created_at} AND identical receipt-time microsecond -- which a
 * microsecond-only tie-breaker could not.
 */
final class BillingSourceVersion {

    /**
     * Width of the low-order sub-second slot within {@code source_version}: wide enough for
     * microsecond resolution (0..999,999) plus the backfill sentinel above it, while {@code
     * epochSeconds * SECOND_SCALE} stays comfortably inside a signed 64-bit range for any
     * realistic timestamp.
     */
    private static final long SECOND_SCALE = 2_000_000L;

    /**
     * A backfill snapshot always wins a same-second tie against a webhook event: a backfill GET
     * reflects Stripe's true live state as of the instant the request was issued, which is at or
     * after any change whose triggering webhook event's `created` second is <= that same second --
     * so treating it as maximally recent within that second is correct, not arbitrary.
     */
    private static final long BACKFILL_TIE_BREAKER = 1_000_000L;

    /**
     * Backfill rows never need {@code source_sequence} to distinguish anything: two backfill
     * fetches of the same object always carry identical content (both are "the current live
     * state"), so a same-version replay is a harmless no-op regardless of relative order.
     */
    private static final long BACKFILL_SEQUENCE = 0L;

    private BillingSourceVersion() {}

    static SourceVersion forWebhookEvent(OffsetDateTime stripeCreatedAt, OffsetDateTime receivedAt, long ingestSequence) {
        long microsecondOfSecond = receivedAt.getNano() / 1_000;
        long version = stripeCreatedAt.toEpochSecond() * SECOND_SCALE + microsecondOfSecond;
        return new SourceVersion(version, ingestSequence);
    }

    static SourceVersion forBackfillFetch(Instant fetchStartedAt) {
        long version = fetchStartedAt.getEpochSecond() * SECOND_SCALE + BACKFILL_TIE_BREAKER;
        return new SourceVersion(version, BACKFILL_SEQUENCE);
    }

    /** The ordering pair applied to every {@code billing_*} upsert's version guard. */
    record SourceVersion(long version, long sequence) {}
}
