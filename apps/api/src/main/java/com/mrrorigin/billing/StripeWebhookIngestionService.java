package com.mrrorigin.billing;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Verifies and durably persists raw Stripe webhook deliveries, per ADR-0002/ADR-0003 and
 * ARCHITECTURE.md's reliability rules. Normalization into billing state is out of scope (#13);
 * this service only ever inserts a row in {@code PENDING} or {@code ORPHANED} state and never
 * updates one afterward.
 */
@Service
@Transactional
class StripeWebhookIngestionService {

    private static final List<StripeConnectionStatus> LIVE_STATUSES =
            List.of(StripeConnectionStatus.PENDING, StripeConnectionStatus.ACTIVE);

    private final StripeWebhookEventRepository events;
    private final StripeConnectionRepository connections;
    private final StripeConnectProperties properties;
    private final StripeWebhookSignatureVerifier verifier;
    private final ObjectMapper objectMapper;

    StripeWebhookIngestionService(
            StripeWebhookEventRepository events,
            StripeConnectionRepository connections,
            StripeConnectProperties properties,
            StripeWebhookSignatureVerifier verifier,
            ObjectMapper objectMapper) {
        this.events = events;
        this.connections = connections;
        this.properties = properties;
        this.verifier = verifier;
        this.objectMapper = objectMapper;
    }

    void ingest(StripeConnectionMode mode, byte[] rawBody, String signatureHeader) {
        if (!properties.isWebhookConfigured(mode)) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Stripe webhooks are not configured for this mode");
        }
        // Signature verification always runs against the exact, unmodified request bytes, and
        // always before anything is parsed or persisted -- an unverified request is never accepted.
        if (!verifier.isValid(rawBody, signatureHeader, properties.webhookSecret(mode))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stripe webhook signature could not be verified");
        }

        JsonNode event = parse(rawBody);
        String stripeEventId = requiredText(event, "id");
        String eventType = requiredText(event, "type");
        String stripeAccountId = requiredText(event, "account");
        long createdEpochSeconds = requiredEpochSeconds(event, "created");
        String apiVersion = optionalText(event, "api_version");

        if (events.findByStripeEventId(stripeEventId).isPresent()) {
            // Already durably stored by an earlier delivery; acknowledge without reprocessing.
            return;
        }

        // Only a currently live (PENDING/ACTIVE) connection is a valid processing target. An
        // account with no connection at all, or one that is DISCONNECTED/REVOKED, is treated the
        // same way: the event is stored as an orphaned raw record and never processed into a
        // workspace, per ADR-0003.
        StripeConnection connection = connections
                .findByStripeAccountIdAndStatusIn(stripeAccountId, LIVE_STATUSES)
                .orElse(null);

        StripeWebhookEvent record = new StripeWebhookEvent(
                UUID.randomUUID(),
                stripeEventId,
                stripeAccountId,
                mode,
                connection != null ? connection.id() : null,
                connection != null ? connection.workspaceId() : null,
                eventType,
                apiVersion,
                Instant.ofEpochSecond(createdEpochSeconds).atOffset(ZoneOffset.UTC),
                connection != null ? StripeWebhookProcessingState.PENDING : StripeWebhookProcessingState.ORPHANED,
                new String(rawBody, StandardCharsets.UTF_8));

        try {
            events.saveAndFlush(record);
        } catch (DataIntegrityViolationException failure) {
            if (!isDuplicateEventIdViolation(failure)) {
                throw failure;
            }
            // A concurrent delivery of the same event won the race and is already durably stored.
        }
    }

    private static boolean isDuplicateEventIdViolation(DataIntegrityViolationException exception) {
        Throwable cause = exception.getMostSpecificCause();
        String message = cause.getMessage();
        return message != null && message.contains("uq_stripe_webhook_events_stripe_event_id");
    }

    private JsonNode parse(byte[] rawBody) {
        try {
            JsonNode event = objectMapper.readTree(rawBody);
            if (event == null || !event.isObject()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stripe webhook payload was not a JSON object");
            }
            return event;
        } catch (ResponseStatusException alreadyMapped) {
            throw alreadyMapped;
        } catch (RuntimeException malformed) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stripe webhook payload could not be parsed");
        }
    }

    private static String requiredText(JsonNode event, String field) {
        JsonNode value = event.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Stripe webhook payload is missing required field: " + field);
        }
        return value.textValue();
    }

    private static long requiredEpochSeconds(JsonNode event, String field) {
        JsonNode value = event.get(field);
        if (value == null || !value.isNumber()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Stripe webhook payload is missing required field: " + field);
        }
        return value.longValue();
    }

    private static String optionalText(JsonNode event, String field) {
        JsonNode value = event.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }
}
