package com.mrrorigin.billing;

/**
 * Classifies why a {@code stripe_webhook_events} row is currently {@code FAILED} (#15).
 *
 * <p>{@link #UNSUPPORTED} means {@link StripeBillingObjectParser} or {@link
 * StripeWebhookNormalizationService} rejected the event's Stripe object shape as unparseable
 * ({@link StripeBillingNormalizationException}) -- replaying it without a code change will fail
 * identically every time. {@link #TRANSIENT} covers everything else (a Stripe API call failure
 * during normalization, etc.), where the same event may succeed on a later replay alone.
 *
 * <p>{@link #LEGACY} is never produced by {@link #classify}; it is assigned only by V12's migration,
 * as a backfilled value for rows that were already FAILED (the normalization worker has been able to
 * mark a row FAILED since V5, before this classification existed) when that migration ran. It
 * deliberately does not guess UNSUPPORTED or TRANSIENT for those rows without evidence.
 */
enum StripeWebhookFailureKind {
    UNSUPPORTED,
    TRANSIENT,
    LEGACY;

    static StripeWebhookFailureKind classify(RuntimeException failure) {
        return failure instanceof StripeBillingNormalizationException ? UNSUPPORTED : TRANSIENT;
    }
}
