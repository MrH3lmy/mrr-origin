package com.mrrorigin.billing;

/** Which Stripe environment a connection operates in; test and live credentials are never interchangeable. */
public enum StripeConnectionMode {
    TEST,
    LIVE
}
