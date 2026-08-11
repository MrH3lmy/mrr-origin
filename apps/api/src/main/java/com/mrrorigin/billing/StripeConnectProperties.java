package com.mrrorigin.billing;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Platform-level Stripe Connect configuration. Per ADR-0003, the secret keys here are the only
 * Stripe credentials MRROrigin holds; they are centrally configured (environment/secret store),
 * never stored in a workspace database row.
 */
@ConfigurationProperties(prefix = "mrrorigin.stripe.connect")
public record StripeConnectProperties(
        String testClientId,
        String testSecretKey,
        String liveClientId,
        String liveSecretKey,
        String testWebhookSecret,
        String liveWebhookSecret,
        String redirectUri,
        Duration stateTtl,
        String authorizeUri,
        String tokenUri,
        String deauthorizeUri,
        String apiBaseUri) {

    public StripeConnectProperties {
        stateTtl = stateTtl == null ? Duration.ofMinutes(15) : stateTtl;
        authorizeUri = blankToDefault(authorizeUri, "https://connect.stripe.com/oauth/authorize");
        tokenUri = blankToDefault(tokenUri, "https://connect.stripe.com/oauth/token");
        deauthorizeUri = blankToDefault(deauthorizeUri, "https://connect.stripe.com/oauth/deauthorize");
        apiBaseUri = blankToDefault(apiBaseUri, "https://api.stripe.com");
    }

    String clientId(StripeConnectionMode mode) {
        return mode == StripeConnectionMode.LIVE ? liveClientId : testClientId;
    }

    String secretKey(StripeConnectionMode mode) {
        return mode == StripeConnectionMode.LIVE ? liveSecretKey : testSecretKey;
    }

    /** Per ADR-0003, test and live webhook endpoints each have their own signing secret; never interchangeable. */
    String webhookSecret(StripeConnectionMode mode) {
        return mode == StripeConnectionMode.LIVE ? liveWebhookSecret : testWebhookSecret;
    }

    boolean isWebhookConfigured(StripeConnectionMode mode) {
        return isPresent(webhookSecret(mode));
    }

    boolean isConfigured(StripeConnectionMode mode) {
        return isPresent(clientId(mode)) && isPresent(secretKey(mode));
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static String blankToDefault(String value, String fallback) {
        return isPresent(value) ? value : fallback;
    }
}
