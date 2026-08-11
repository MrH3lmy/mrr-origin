package com.mrrorigin.billing;

/** A Stripe backfill list request failed (network error, non-2xx, or malformed response). */
final class StripeBackfillException extends RuntimeException {

    StripeBackfillException(String message) {
        super(message);
    }
}
