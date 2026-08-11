package com.mrrorigin.billing;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Computes {@code billing_*.(source_version, source_sequence)}: a stable ordering pair compared as
 * a Postgres row value. The first component is a conservative provider-time boundary; the second
 * is a source-stable key, never local arrival or worker-processing order.
 *
 * <p>Stripe event {@code created} timestamps have only second precision. Webhooks therefore use
 * that provider second plus the immutable Stripe event ID. The event ID does not claim to encode
 * causal time; it is a deterministic ambiguity resolver when Stripe exposes no sub-second order.
 * Reversing delivery or processing order for the same event set still produces the same ledger.
 *
 * <p>A backfill request made during second {@code S} is deliberately placed at the end of second
 * {@code S-1}. It is guaranteed to include state committed before {@code S}, but it cannot safely
 * claim to include a webhook that Stripe creates later during {@code S}. This conservative lower
 * bound prevents an early, stale same-second backfill response from overwriting a newer webhook.
 * A later backfill in {@code S+1} becomes authoritative for all of second {@code S}.
 */
final class BillingSourceVersion {

    private static final String WEBHOOK_PREFIX = "W:";
    private static final String BACKFILL_PREFIX = "Z:";

    private BillingSourceVersion() {}

    static SourceVersion forWebhookEvent(OffsetDateTime stripeCreatedAt, String stripeEventId) {
        Objects.requireNonNull(stripeCreatedAt, "stripeCreatedAt");
        if (stripeEventId == null || stripeEventId.isBlank()) {
            throw new IllegalArgumentException("stripeEventId must not be blank");
        }
        return new SourceVersion(stripeCreatedAt.toEpochSecond(), WEBHOOK_PREFIX + stripeEventId);
    }

    static SourceVersion forBackfillFetch(Instant fetchStartedAt) {
        Objects.requireNonNull(fetchStartedAt, "fetchStartedAt");
        long lastFullyElapsedProviderSecond = Math.subtractExact(fetchStartedAt.getEpochSecond(), 1L);
        String stableWithinSecondOrder = BACKFILL_PREFIX + "%09d".formatted(fetchStartedAt.getNano());
        return new SourceVersion(lastFullyElapsedProviderSecond, stableWithinSecondOrder);
    }

    /** The ordering pair applied to every {@code billing_*} upsert's version guard. */
    record SourceVersion(long version, String sequence) {
        SourceVersion {
            Objects.requireNonNull(sequence, "sequence");
        }
    }
}
