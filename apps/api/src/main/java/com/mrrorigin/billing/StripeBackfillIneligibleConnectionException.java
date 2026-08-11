package com.mrrorigin.billing;

/**
 * A backfill was requested for a connection that is not currently ACTIVE and VERIFIED (e.g.
 * PENDING, DISCONNECTED, REVOKED, or verification-FAILED). Backfill must never run against a
 * connection in one of these states.
 */
final class StripeBackfillIneligibleConnectionException extends RuntimeException {

    StripeBackfillIneligibleConnectionException(String message) {
        super(message);
    }
}
