package com.mrrorigin.billing;

/** A Stripe OAuth/API call failed. The message is always a safe, fixed reason -- never raw Stripe error text. */
class StripeConnectException extends RuntimeException {

    StripeConnectException(String message) {
        super(message);
    }
}
