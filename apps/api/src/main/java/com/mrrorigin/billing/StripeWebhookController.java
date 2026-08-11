package com.mrrorigin.billing;

import java.io.IOException;

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
        byte[] rawBody = request.getInputStream().readAllBytes();
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
}
