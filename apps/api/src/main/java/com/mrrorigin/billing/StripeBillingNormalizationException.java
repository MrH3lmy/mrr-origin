package com.mrrorigin.billing;

/**
 * A Stripe object (from a backfill page or a webhook event's {@code data.object}) could not be
 * parsed into the normalized billing ledger shape. Callers treat this as a per-object/per-event
 * failure: a backfill page aborts without advancing its checkpoint, and a webhook event is marked
 * {@code FAILED} with the message recorded, rather than either being silently dropped.
 */
final class StripeBillingNormalizationException extends RuntimeException {

    StripeBillingNormalizationException(String message) {
        super(message);
    }
}
