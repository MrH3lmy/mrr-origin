package com.mrrorigin.billing;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Verifies and durably persists raw Stripe webhook deliveries, per ADR-0002/ADR-0003 and
 * ARCHITECTURE.md's reliability rules. Normalization into billing state is out of scope (#13);
 * this service only ever inserts a row in {@code PENDING} or {@code ORPHANED} state and never
 * updates one afterward. Persistence goes through a single atomic native upsert rather than JPA,
 * mirroring {@code StripeOauthStateService}'s pattern -- see {@link #ingest} for why.
 */
@Service
@Transactional
class StripeWebhookIngestionService {

    private static final List<StripeConnectionStatus> LIVE_STATUSES =
            List.of(StripeConnectionStatus.PENDING, StripeConnectionStatus.ACTIVE);

    // Schema limits (V5__create_stripe_webhook_events.sql); enforced here so a malformed or
    // maliciously oversized field is rejected with 400 before it ever reaches the database.
    private static final int MAX_STRIPE_EVENT_ID_LENGTH = 255;
    private static final int MAX_STRIPE_ACCOUNT_ID_LENGTH = 255;
    private static final int MAX_EVENT_TYPE_LENGTH = 255;
    private static final int MAX_API_VERSION_LENGTH = 32;

    /**
     * Raw-delivery receipt counter (P6 observability slice, #28). {@code mode} (test/live) and
     * {@code outcome} (stored/duplicate/orphaned) are the only tags -- both small fixed enums, never a
     * Stripe event/account id. {@code orphaned} means the event's Stripe account matched no known,
     * active connection (see the class Javadoc); {@code duplicate} means Stripe redelivered an event
     * already durably stored, detected by the {@code ON CONFLICT DO NOTHING} affecting zero rows.
     */
    private static final String RECEIVED_METRIC = "mrrorigin.stripe.webhook.received";

    private final StripeConnectionRepository connections;
    private final StripeConnectProperties properties;
    private final StripeWebhookSignatureVerifier verifier;
    private final ObjectMapper objectMapper;
    private final JdbcClient jdbc;
    private final MeterRegistry meterRegistry;

    StripeWebhookIngestionService(
            StripeConnectionRepository connections,
            StripeConnectProperties properties,
            StripeWebhookSignatureVerifier verifier,
            ObjectMapper objectMapper,
            JdbcClient jdbc,
            MeterRegistry meterRegistry) {
        this.connections = connections;
        this.properties = properties;
        this.verifier = verifier;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.meterRegistry = meterRegistry;
    }

    private void recordReceived(StripeConnectionMode mode, String outcome) {
        Counter.builder(RECEIVED_METRIC)
                .tag("mode", mode.name().toLowerCase())
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }

    void ingest(StripeConnectionMode mode, byte[] rawBody, String signatureHeader) {
        if (!properties.isWebhookConfigured(mode)) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Stripe webhooks are not configured for this mode");
        }
        // Signature verification (including Stripe's documented timestamp tolerance) always runs
        // against the exact, unmodified request bytes, and always before anything is parsed or
        // persisted -- an unverified request is never accepted.
        if (!verifier.isValid(rawBody, signatureHeader, properties.webhookSecret(mode))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stripe webhook signature could not be verified");
        }

        JsonNode event = parse(rawBody);
        String stripeEventId = requiredText(event, "id", MAX_STRIPE_EVENT_ID_LENGTH);
        String eventType = requiredText(event, "type", MAX_EVENT_TYPE_LENGTH);
        String stripeAccountId = requiredText(event, "account", MAX_STRIPE_ACCOUNT_ID_LENGTH);
        String apiVersion = optionalText(event, "api_version", MAX_API_VERSION_LENGTH);
        OffsetDateTime stripeCreatedAt = requiredCreatedTimestamp(event);
        boolean livemode = requiredBoolean(event, "livemode");

        boolean expectedLivemode = mode == StripeConnectionMode.LIVE;
        if (livemode != expectedLivemode) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Stripe webhook payload's livemode does not match the endpoint it was delivered to");
        }

        // Only a currently live (PENDING/ACTIVE) connection in the SAME mode as this endpoint is a
        // valid processing target. An account with no connection at all, one that is
        // DISCONNECTED/REVOKED, or one that is only live in the other mode (test vs. live are
        // separate Stripe environments) is treated the same way: the event is stored as an
        // orphaned raw record and never processed into a workspace, per ADR-0003.
        StripeConnection connection = connections
                .findByStripeAccountIdAndModeAndStatusIn(stripeAccountId, mode, LIVE_STATUSES)
                .orElse(null);
        StripeWebhookProcessingState processingState =
                connection != null ? StripeWebhookProcessingState.PENDING : StripeWebhookProcessingState.ORPHANED;

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        // A single atomic upsert, not a check-then-insert: two concurrent deliveries of the same
        // event race the database itself, not application code, so there is no JPA constraint
        // exception to catch (and no reason to -- catching one mid-transaction would leave the
        // persistence context/transaction in an unreliable state for anything after it). Exactly
        // one of any number of concurrent identical deliveries inserts a row; every other one sees
        // 0 rows affected and simply acknowledges without reprocessing.
        int inserted = jdbc.sql(
                        """
                        INSERT INTO stripe_webhook_events
                            (id, stripe_event_id, stripe_account_id, mode, connection_id, workspace_id,
                             event_type, api_version, stripe_created_at, received_at, raw_payload, payload,
                             processing_state, updated_at)
                        VALUES
                            (:id, :stripeEventId, :stripeAccountId, :mode, :connectionId, :workspaceId,
                             :eventType, :apiVersion, :stripeCreatedAt, :receivedAt, :rawPayload, :payload::jsonb,
                             :processingState, :updatedAt)
                        ON CONFLICT (mode, stripe_event_id) DO NOTHING
                        """)
                .param("id", UUID.randomUUID())
                .param("stripeEventId", stripeEventId)
                .param("stripeAccountId", stripeAccountId)
                .param("mode", mode.name())
                .param("connectionId", connection != null ? connection.id() : null)
                .param("workspaceId", connection != null ? connection.workspaceId() : null)
                .param("eventType", eventType)
                .param("apiVersion", apiVersion)
                .param("stripeCreatedAt", stripeCreatedAt)
                .param("receivedAt", now)
                .param("rawPayload", rawBody)
                .param("payload", new String(rawBody, StandardCharsets.UTF_8))
                .param("processingState", processingState.name())
                .param("updatedAt", now)
                .update();
        // Rows-affected (0 for an already-stored duplicate, 1 for a fresh insert) is intentionally
        // not distinguished in the response: both cases mean the event is durably stored, which is
        // all the caller (Stripe) needs to stop retrying. It IS distinguished in the metric below,
        // since "duplicate" vs. "stored" is operationally useful (a spike in duplicates suggests
        // Stripe-side retry storms) without affecting the durable-storage guarantee itself.
        recordReceived(mode, inserted == 0 ? "duplicate" : processingState == StripeWebhookProcessingState.ORPHANED ? "orphaned" : "stored");
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

    private static String requiredText(JsonNode event, String field, int maxLength) {
        JsonNode value = event.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Stripe webhook payload is missing required field: " + field);
        }
        String text = value.textValue();
        if (text.length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stripe webhook payload field too long: " + field);
        }
        return text;
    }

    /**
     * A missing field, or an explicit JSON {@code null}, leaves the value absent. Any other
     * present-but-wrong-typed value (number, boolean, object, array) is a malformed payload, not a
     * silently-ignored one -- an attacker or a buggy sender should get a 400, not have the field
     * quietly dropped.
     */
    private static String optionalText(JsonNode event, String field, int maxLength) {
        JsonNode value = event.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Stripe webhook payload field has the wrong type: " + field);
        }
        String text = value.textValue();
        if (text.length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stripe webhook payload field too long: " + field);
        }
        return text;
    }

    private static boolean requiredBoolean(JsonNode event, String field) {
        JsonNode value = event.get(field);
        if (value == null || !value.isBoolean()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Stripe webhook payload is missing required field: " + field);
        }
        return value.booleanValue();
    }

    private static OffsetDateTime requiredCreatedTimestamp(JsonNode event) {
        JsonNode value = event.get("created");
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Stripe webhook payload is missing required field: created");
        }
        try {
            return Instant.ofEpochSecond(value.longValue()).atOffset(ZoneOffset.UTC);
        } catch (DateTimeException | ArithmeticException outOfRange) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stripe webhook payload field out of range: created");
        }
    }
}
