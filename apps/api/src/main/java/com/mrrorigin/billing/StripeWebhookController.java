package com.mrrorigin.billing;

import java.io.IOException;
import java.io.InputStream;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Receives Stripe's platform-level Connect webhook deliveries. Per ADR-0003, sandbox and
 * production are separate endpoints with separate signing secrets, never shared. The request body
 * is read here as raw bytes -- before any JSON conversion -- so signature verification always runs
 * against the exact bytes Stripe signed, per ARCHITECTURE.md's reliability rules.
 *
 * <p>A 2xx response is returned only once the event is durably persisted (or already was, for a
 * duplicate delivery); any failure to verify, parse, or persist propagates as a non-2xx status so
 * Stripe retries the delivery instead of it being silently dropped.
 */
@RestController
class StripeWebhookController {

    /**
     * Documented maximum accepted webhook body size (1 MiB). Stripe's own events are typically well
     * under this; the limit exists to bound memory for the raw-byte read below regardless of what a
     * request declares via {@code Content-Length} or whether it uses chunked transfer encoding --
     * at most this many bytes plus one are ever buffered before an oversized request is rejected.
     */
    private static final int MAX_REQUEST_BODY_BYTES = 1024 * 1024;

    private final StripeWebhookIngestionService ingestionService;

    StripeWebhookController(StripeWebhookIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/api/stripe/webhooks/{mode}")
    void receive(
            @PathVariable String mode,
            @RequestHeader("Stripe-Signature") String signatureHeader,
            HttpServletRequest request)
            throws IOException {
        StripeConnectionMode resolvedMode = resolveMode(mode);
        byte[] rawBody = readBoundedBody(request.getInputStream());
        ingestionService.ingest(resolvedMode, rawBody, signatureHeader);
    }

    private static StripeConnectionMode resolveMode(String mode) {
        if ("test".equals(mode)) {
            return StripeConnectionMode.TEST;
        }
        if ("live".equals(mode)) {
            return StripeConnectionMode.LIVE;
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown Stripe webhook endpoint");
    }

    /**
     * Reads at most {@link #MAX_REQUEST_BODY_BYTES} + 1 bytes regardless of any declared
     * {@code Content-Length} (missing, wrong, or otherwise) and regardless of transfer encoding
     * (fixed-length or chunked) -- the cap is enforced against actual bytes streamed, never a
     * client-supplied header, so memory use is bounded before allocation, not after.
     */
    private static byte[] readBoundedBody(InputStream input) throws IOException {
        byte[] buffer = input.readNBytes(MAX_REQUEST_BODY_BYTES + 1);
        if (buffer.length > MAX_REQUEST_BODY_BYTES) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "Stripe webhook payload exceeds the maximum accepted size");
        }
        return buffer;
    }
}
