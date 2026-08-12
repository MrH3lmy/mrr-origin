package com.mrrorigin.billing;

/**
 * Classifies why a {@code stripe_webhook_events} row is currently {@code FAILED} (#15).
 *
 * <p>{@link #UNSUPPORTED} means {@link StripeBillingObjectParser} or {@link
 * StripeWebhookNormalizationService} rejected the event's Stripe object shape as unparseable
 * ({@link StripeBillingNormalizationException}) -- replaying it without a code change will fail
 * identically every time. {@link #TRANSIENT} covers everything else (a Stripe API call failure
 * during normalization, etc.), where the same event may succeed on a later replay alone.
 */
enum StripeWebhookFailureKind {
    UNSUPPORTED,
    TRANSIENT;

    static StripeWebhookFailureKind classify(RuntimeException failure) {
        return failure instanceof StripeBillingNormalizationException ? UNSUPPORTED : TRANSIENT;
    }
}
