package com.mrrorigin.billing;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * Verifies a {@code Stripe-Signature} header per Stripe's documented scheme
 * (https://docs.stripe.com/webhooks?lang=node): HMAC-SHA256 of {@code "<timestamp>.<raw body>"}
 * using the endpoint's signing secret, compared against every {@code v1} signature present (Stripe
 * can include more than one during its own secret rotation). Always called with the exact,
 * unmodified request bytes -- never a re-serialized or re-parsed representation of the body.
 *
 * <p>Enforces Stripe's documented replay defense: the {@code t} timestamp must be within a
 * 5-minute tolerance of the current time, in either direction. This is safe for delayed events --
 * Stripe generates a fresh {@code t} and signature for every delivery attempt, including retries,
 * so an event whose own {@code created} is hours old still arrives with a signature timestamped at
 * delivery time. What this rejects is a captured, stale request being replayed verbatim later.
 */
@Component
class StripeWebhookSignatureVerifier {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** Stripe's documented tolerance: https://docs.stripe.com/webhooks?lang=node#verify-official-libraries */
    static final Duration TOLERANCE = Duration.ofMinutes(5);

    private final Clock clock;

    StripeWebhookSignatureVerifier(Clock clock) {
        this.clock = clock;
    }

    boolean isValid(byte[] rawBody, String signatureHeader, String signingSecret) {
        if (rawBody == null
                || signatureHeader == null
                || signatureHeader.isBlank()
                || signingSecret == null
                || signingSecret.isBlank()) {
            return false;
        }

        String timestamp = null;
        List<String> v1Signatures = new ArrayList<>();
        for (String element : signatureHeader.split(",")) {
            int separator = element.indexOf('=');
            if (separator < 0) {
                continue;
            }
            String key = element.substring(0, separator).trim();
            String value = element.substring(separator + 1).trim();
            if ("t".equals(key)) {
                timestamp = value;
            } else if ("v1".equals(key) && !value.isEmpty()) {
                v1Signatures.add(value);
            }
        }
        if (timestamp == null || timestamp.isBlank() || v1Signatures.isEmpty()) {
            return false;
        }

        if (!isWithinTolerance(timestamp)) {
            return false;
        }

        byte[] expected = hmacSha256(signingSecret, timestamp, rawBody);
        for (String candidate : v1Signatures) {
            byte[] candidateBytes;
            try {
                candidateBytes = HexFormat.of().parseHex(candidate);
            } catch (IllegalArgumentException invalidHex) {
                continue;
            }
            if (MessageDigest.isEqual(expected, candidateBytes)) {
                return true;
            }
        }
        return false;
    }

    private boolean isWithinTolerance(String timestamp) {
        long epochSeconds;
        try {
            epochSeconds = Long.parseLong(timestamp);
        } catch (NumberFormatException notANumber) {
            return false;
        }

        Instant signedAt;
        try {
            signedAt = Instant.ofEpochSecond(epochSeconds);
        } catch (DateTimeException | ArithmeticException outOfRange) {
            return false;
        }

        Duration drift = Duration.between(signedAt, Instant.now(clock)).abs();
        return drift.compareTo(TOLERANCE) <= 0;
    }

    private static byte[] hmacSha256(String secret, String timestamp, byte[] rawBody) {
        byte[] prefix = (timestamp + ".").getBytes(StandardCharsets.US_ASCII);
        byte[] signedPayload = new byte[prefix.length + rawBody.length];
        System.arraycopy(prefix, 0, signedPayload, 0, prefix.length);
        System.arraycopy(rawBody, 0, signedPayload, prefix.length, rawBody.length);

        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return mac.doFinal(signedPayload);
        } catch (NoSuchAlgorithmException | InvalidKeyException impossible) {
            throw new IllegalStateException("HmacSHA256 must be available on the JVM", impossible);
        }
    }
}
