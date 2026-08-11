package com.mrrorigin.billing;

enum StripeVerificationOutcome {
    VERIFIED,
    /** Stripe returned an authorization error: the platform key no longer has access to this account. */
    UNAUTHORIZED,
    /** An ambiguous or transient failure (network error, 5xx); not evidence the account was revoked. */
    TRANSIENT_FAILURE
}
