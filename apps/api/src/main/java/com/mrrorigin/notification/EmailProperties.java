package com.mrrorigin.notification;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Weekly-summary email configuration (#59, ADR-0007). Mirrors {@code StripeConnectProperties}
 * exactly: env-var-backed, blank-safe defaults, no credential ever hardcoded or committed. Sender and
 * reply-to addresses are intentionally left with no default -- no domain/mailbox convention exists
 * anywhere in this repo (plan §5/B6) -- each deployment supplies its own.
 */
@ConfigurationProperties(prefix = "mrrorigin.notification.email")
public record EmailProperties(
        String postmarkServerToken, String senderAddress, String replyToAddress, String messageStream, Duration requestTimeout) {

    public EmailProperties {
        messageStream = blankToDefault(messageStream, "outbound");
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(10) : requestTimeout;
    }

    /** True only when every value dispatch actually needs to send is present. */
    boolean isConfigured() {
        return isPresent(postmarkServerToken) && isPresent(senderAddress);
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static String blankToDefault(String value, String fallback) {
        return isPresent(value) ? value : fallback;
    }
}
