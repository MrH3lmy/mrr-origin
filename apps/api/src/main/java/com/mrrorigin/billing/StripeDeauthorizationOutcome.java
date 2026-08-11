package com.mrrorigin.billing;

/** Result of asking Stripe to end our platform's OAuth grant on a connected account. */
enum StripeDeauthorizationOutcome {
    /** Stripe returned a successful response: the grant is confirmed ended. */
    CONFIRMED,
    /** Stripe returned an error response. Not confirmed; the caller must not assume disconnection. */
    REJECTED,
    /** Stripe could not be reached (network/IO failure). Not confirmed; the caller must retry. */
    UNREACHABLE
}
