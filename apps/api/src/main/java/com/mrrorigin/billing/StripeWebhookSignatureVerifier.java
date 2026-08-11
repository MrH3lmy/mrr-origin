package com.mrrorigin.billing;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * Verifies a {@code Stripe-Signature} header per Stripe's documented scheme
 * (https://docs.stripe.com/webhooks#verify-manually): HMAC-SHA256 of {@code "<timestamp>.<raw body>"}
 * using the endpoint's signing secret, compared against every {@code v1} signature present (Stripe
 * can include more than one during its own secret rotation). Always called with the exact,
 * unmodified request bytes -- never a re-serialized or re-parsed representation of the body.
 *
 * <p>Deliberately does not enforce a timestamp-tolerance window: Stripe can retry delivery for a
 * delayed event well after its original signing time, and duplicate/replayed deliveries are
 * already made safe by the unique constraint on the event ID, so an age check would only reject
 * legitimate delayed retries without adding real protection.
 */
@Component
class StripeWebhookSignatureVerifier {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

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
