package com.mrrorigin.identity;

import org.springframework.http.HttpStatus;

/**
 * Public because #20's {@code attribution.CustomerLinkRepairController} also needs to translate
 * this into an HTTP response -- {@link com.mrrorigin.identity.StripeCustomerLinkingService#repair}
 * throws the same exception type {@link com.mrrorigin.identity.StripeCustomerLinkingService#link}
 * does, for the same request-shape errors (unknown identity, unknown Stripe customer, unauthorized).
 */
public final class StripeCustomerLinkException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    StripeCustomerLinkException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
