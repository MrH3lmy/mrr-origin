package com.mrrorigin.billing;

/** Result of the most recent attempt to confirm the platform key can act on this connection's account. */
public enum StripeVerificationStatus {
    UNVERIFIED,
    VERIFIED,
    FAILED
}
