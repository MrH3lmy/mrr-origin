package com.mrrorigin.billing;

public enum StripeConnectionStatus {
    /** OAuth consent captured; first verification against Stripe has not completed yet. */
    PENDING,
    /** Most recent verification against Stripe succeeded. */
    ACTIVE,
    /** MRROrigin or the founder ended the connection in-product; historical data is retained. */
    DISCONNECTED,
    /** Stripe reported the connection is no longer authorized (invalid/failed verification). */
    REVOKED
}
