package com.mrrorigin.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Exercises the raw Stripe webhook ingestion endpoint end to end against a real Postgres, per
 * #11: signature verification (including timestamp tolerance) against unmodified bytes, a bounded
 * request-body read, durable persistence before acknowledgement, and idempotent/transaction-safe
 * handling of duplicate/delayed/out-of-order/malformed/unrouteable/concurrent deliveries.
 *
 * <p>See {@link StripeWebhookChunkedRequestSizeIntegrationTests} for the real-HTTP (non-MockMvc)
 * chunked oversized-body case, which needs a genuine socket rather than a mocked request.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class StripeWebhookIngestionIntegrationTests {

    private static final String TEST_WEBHOOK_SECRET = "whsec_test_platform_secret";
    private static final String LIVE_WEBHOOK_SECRET = "whsec_live_platform_secret";

    /** Mirrors StripeWebhookController.MAX_REQUEST_BODY_BYTES (private there, so duplicated here). */
    private static final int MAX_REQUEST_BODY_BYTES = 1024 * 1024;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    @DynamicPropertySource
    static void stripeProperties(DynamicPropertyRegistry registry) {
        registry.add("mrrorigin.stripe.connect.test-client-id", () -> "ca_test_123");
        registry.add("mrrorigin.stripe.connect.test-secret-key", () -> "sk_test_platform_secret");
        registry.add("mrrorigin.stripe.connect.live-client-id", () -> "ca_live_123");
        registry.add("mrrorigin.stripe.connect.live-secret-key", () -> "sk_live_platform_secret");
        registry.add("mrrorigin.stripe.connect.test-webhook-secret", () -> TEST_WEBHOOK_SECRET);
        registry.add("mrrorigin.stripe.connect.live-webhook-secret", () -> LIVE_WEBHOOK_SECRET);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    private JdbcClient jdbc;

    @Autowired
    void setJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @BeforeEach
    void resetState() {
        new org.springframework.jdbc.core.JdbcTemplate(dataSource)
                .execute("TRUNCATE TABLE projects, workspace_members, workspaces, stripe_connections, "
                        + "stripe_oauth_states, stripe_webhook_events CASCADE");
    }

    // ---- Signature correctness -------------------------------------------------------------

    @Test
    void wrongSignatureIsRejectedAndNotPersisted() throws Exception {
        String payload = event("evt_invalid_sig", "acct_unknown", "invoice.paid", Instant.now());
        long freshTimestamp = Instant.now().getEpochSecond();

        mockMvc.perform(webhookRequest("test", payload, "t=" + freshTimestamp + ",v1=" + "0".repeat(64)))
                .andExpect(status().isBadRequest());

        assertThat(countEvents()).isZero();
    }

    @Test
    void missingSignatureHeaderIsRejected() throws Exception {
        String payload = event("evt_no_header", "acct_unknown", "invoice.paid", Instant.now());

        mockMvc.perform(post("/api/stripe/webhooks/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());

        assertThat(countEvents()).isZero();
    }

    @Test
    void signedWithTheWrongModeSecretIsRejected() throws Exception {
        String payload = event("evt_wrong_mode_secret", "acct_unknown", "invoice.paid", Instant.now());
        String signature = sign(payload, LIVE_WEBHOOK_SECRET);

        // Signed with the live secret but delivered to the test endpoint: per ADR-0003 the two are
        // never interchangeable.
        mockMvc.perform(webhookRequest("test", payload, signature)).andExpect(status().isBadRequest());
        assertThat(countEvents()).isZero();
    }

    // ---- Signature timestamp tolerance (Stripe's documented replay defense) ----------------

    @Test
    void staleSignatureTimestampIsRejectedEvenWithACorrectlyComputedSignature() throws Exception {
        String payload = event("evt_stale_timestamp", "acct_unknown", "invoice.paid", Instant.now());
        long staleTimestamp = Instant.now().minus(Duration.ofMinutes(10)).getEpochSecond();

        mockMvc.perform(webhookRequest("test", payload, sign(payload, TEST_WEBHOOK_SECRET, staleTimestamp)))
                .andExpect(status().isBadRequest());
        assertThat(countEvents()).isZero();
    }

    @Test
    void excessivelyFutureSignatureTimestampIsRejectedEvenWithACorrectlyComputedSignature() throws Exception {
        String payload = event("evt_future_timestamp", "acct_unknown", "invoice.paid", Instant.now());
        long futureTimestamp = Instant.now().plus(Duration.ofMinutes(10)).getEpochSecond();

        mockMvc.perform(webhookRequest("test", payload, sign(payload, TEST_WEBHOOK_SECRET, futureTimestamp)))
                .andExpect(status().isBadRequest());
        assertThat(countEvents()).isZero();
    }

    @Test
    void timestampWithinFiveMinutesEitherDirectionIsAccepted() throws Exception {
        UUID workspaceId = createWorkspace();
        insertActiveConnection(workspaceId, "acct_tolerance", StripeConnectionMode.TEST);

        String pastPayload = event("evt_tolerance_past", "acct_tolerance", "invoice.paid", Instant.now());
        long justInsidePast = Instant.now().minus(Duration.ofMinutes(4)).minusSeconds(30).getEpochSecond();
        mockMvc.perform(webhookRequest("test", pastPayload, sign(pastPayload, TEST_WEBHOOK_SECRET, justInsidePast)))
                .andExpect(status().isOk());

        String futurePayload = event("evt_tolerance_future", "acct_tolerance", "invoice.paid", Instant.now());
        long justInsideFuture = Instant.now().plus(Duration.ofMinutes(4)).plusSeconds(30).getEpochSecond();
        mockMvc.perform(
                        webhookRequest("test", futurePayload, sign(futurePayload, TEST_WEBHOOK_SECRET, justInsideFuture)))
                .andExpect(status().isOk());

        assertThat(countEvents()).isEqualTo(2);
    }

    @Test
    void failedStaleDeliveryFollowedByARetryWithAFreshSignatureSucceeds() throws Exception {
        UUID workspaceId = createWorkspace();
        insertActiveConnection(workspaceId, "acct_retry_fresh_sig", StripeConnectionMode.TEST);
        // Same underlying event object (same id/body shape) as Stripe would retry -- only the
        // Stripe-Signature header changes between attempts, since Stripe re-signs on every retry.
        String payload = event("evt_retry_fresh_sig", "acct_retry_fresh_sig", "invoice.paid", Instant.now());
        long staleTimestamp = Instant.now().minus(Duration.ofMinutes(20)).getEpochSecond();

        mockMvc.perform(webhookRequest("test", payload, sign(payload, TEST_WEBHOOK_SECRET, staleTimestamp)))
                .andExpect(status().isBadRequest());
        assertThat(countEvents()).isZero();

        mockMvc.perform(webhookRequest("test", payload, sign(payload, TEST_WEBHOOK_SECRET)))
                .andExpect(status().isOk());
        assertThat(countEvents()).isOne();
    }

    // ---- Body parsing / malformed payloads ---------------------------------------------------

    @Test
    void malformedJsonBodyIsRejectedAndNotPersistedEvenWithAValidSignature() throws Exception {
        String payload = "{not-valid-json";
        String signature = sign(payload, TEST_WEBHOOK_SECRET);

        mockMvc.perform(webhookRequest("test", payload, signature)).andExpect(status().isBadRequest());

        assertThat(countEvents()).isZero();
    }

    @Test
    void payloadMissingRequiredFieldsIsRejectedAndNotPersisted() throws Exception {
        String payload = """
                {"object":"event","type":"invoice.paid"}""";
        String signature = sign(payload, TEST_WEBHOOK_SECRET);

        mockMvc.perform(webhookRequest("test", payload, signature)).andExpect(status().isBadRequest());

        assertThat(countEvents()).isZero();
    }

    @Test
    void payloadMissingLivemodeIsRejectedAndNotPersisted() throws Exception {
        String payload = """
                {"id":"evt_no_livemode","object":"event","api_version":"2024-06-20","created":%d,
                 "type":"invoice.paid","account":"acct_unknown","data":{"object":{}}}"""
                .formatted(Instant.now().getEpochSecond());
        String signature = sign(payload, TEST_WEBHOOK_SECRET);

        mockMvc.perform(webhookRequest("test", payload, signature)).andExpect(status().isBadRequest());
        assertThat(countEvents()).isZero();
    }

    @Test
    void nonIntegralCreatedFieldIsRejectedAndNotPersisted() throws Exception {
        String payload = """
                {"id":"evt_non_integral_created","object":"event","api_version":"2024-06-20",
                 "created":1690000000.5,"type":"invoice.paid","account":"acct_unknown","livemode":false,
                 "data":{"object":{}}}""";
        String signature = sign(payload, TEST_WEBHOOK_SECRET);

        mockMvc.perform(webhookRequest("test", payload, signature)).andExpect(status().isBadRequest());
        assertThat(countEvents()).isZero();
    }

    @Test
    void createdFieldFarBeyondInstantRangeIsRejectedWith400NotADatabaseError() throws Exception {
        String payload = """
                {"id":"evt_created_out_of_range","object":"event","api_version":"2024-06-20",
                 "created":99999999999999999999999999999999,"type":"invoice.paid","account":"acct_unknown",
                 "livemode":false,"data":{"object":{}}}""";
        String signature = sign(payload, TEST_WEBHOOK_SECRET);

        mockMvc.perform(webhookRequest("test", payload, signature)).andExpect(status().isBadRequest());
        assertThat(countEvents()).isZero();
    }

    @Test
    void stripeEventIdExceedingSchemaLimitIsRejectedAndNotPersisted() throws Exception {
        String tooLongId = "evt_" + "x".repeat(300);
        String payload = event(tooLongId, "acct_unknown", "invoice.paid", Instant.now());
        String signature = sign(payload, TEST_WEBHOOK_SECRET);

        mockMvc.perform(webhookRequest("test", payload, signature)).andExpect(status().isBadRequest());
        assertThat(countEvents()).isZero();
    }

    @Test
    void stripeAccountIdExceedingSchemaLimitIsRejectedAndNotPersisted() throws Exception {
        String tooLongAccount = "acct_" + "x".repeat(300);
        String payload = event("evt_account_too_long", tooLongAccount, "invoice.paid", Instant.now());
        String signature = sign(payload, TEST_WEBHOOK_SECRET);

        mockMvc.perform(webhookRequest("test", payload, signature)).andExpect(status().isBadRequest());
        assertThat(countEvents()).isZero();
    }

    @Test
    void eventTypeExceedingSchemaLimitIsRejectedAndNotPersisted() throws Exception {
        String tooLongType = "invoice." + "x".repeat(300);
        String payload = event("evt_type_too_long", "acct_unknown", tooLongType, Instant.now());
        String signature = sign(payload, TEST_WEBHOOK_SECRET);

        mockMvc.perform(webhookRequest("test", payload, signature)).andExpect(status().isBadRequest());
        assertThat(countEvents()).isZero();
    }

    @Test
    void apiVersionExceedingSchemaLimitIsRejectedAndNotPersisted() throws Exception {
        String payload = """
                {"id":"evt_api_version_too_long","object":"event","api_version":"%s",
                 "created":%d,"type":"invoice.paid","account":"acct_unknown","livemode":false,
                 "data":{"object":{}}}"""
                .formatted("2024-06-20-" + "x".repeat(40), Instant.now().getEpochSecond());
        String signature = sign(payload, TEST_WEBHOOK_SECRET);

        mockMvc.perform(webhookRequest("test", payload, signature)).andExpect(status().isBadRequest());
        assertThat(countEvents()).isZero();
    }

    // ---- livemode must match the endpoint ----------------------------------------------------

    @Test
    void livemodeTrueDeliveredToTestEndpointIsRejectedAndNotPersisted() throws Exception {
        String payload = event("evt_livemode_true_on_test", "acct_unknown", "invoice.paid", Instant.now(), true);
        String signature = sign(payload, TEST_WEBHOOK_SECRET);

        mockMvc.perform(webhookRequest("test", payload, signature)).andExpect(status().isBadRequest());
        assertThat(countEvents()).isZero();
    }

    @Test
    void livemodeFalseDeliveredToLiveEndpointIsRejectedAndNotPersisted() throws Exception {
        String payload = event("evt_livemode_false_on_live", "acct_unknown", "invoice.paid", Instant.now(), false);
        String signature = sign(payload, LIVE_WEBHOOK_SECRET);

        mockMvc.perform(webhookRequest("live", payload, signature)).andExpect(status().isBadRequest());
        assertThat(countEvents()).isZero();
    }

    @Test
    void livemodeTrueDeliveredToLiveEndpointIsAcceptedAndLinked() throws Exception {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_live_mode_ok", StripeConnectionMode.LIVE);
        String payload = event("evt_livemode_true_on_live", "acct_live_mode_ok", "invoice.paid", Instant.now(), true);
        String signature = sign(payload, LIVE_WEBHOOK_SECRET);

        mockMvc.perform(webhookRequest("live", payload, signature)).andExpect(status().isOk());

        assertThat(countEvents()).isOne();
        String row = "SELECT %s FROM stripe_webhook_events WHERE stripe_event_id = 'evt_livemode_true_on_live'";
        assertThat(jdbc.sql(row.formatted("connection_id")).query(UUID.class).single()).isEqualTo(connectionId);
        assertThat(jdbc.sql(row.formatted("workspace_id")).query(UUID.class).single()).isEqualTo(workspaceId);
    }

    // ---- Duplicate / concurrent delivery -----------------------------------------------------

    @Test
    void duplicateDeliveryIsAcknowledgedWithoutDuplicateProcessing() throws Exception {
        UUID workspaceId = createWorkspace();
        insertActiveConnection(workspaceId, "acct_duplicate", StripeConnectionMode.TEST);
        String payload = event("evt_duplicate", "acct_duplicate", "invoice.paid", Instant.now());
        String signature = sign(payload, TEST_WEBHOOK_SECRET);

        mockMvc.perform(webhookRequest("test", payload, signature)).andExpect(status().isOk());
        mockMvc.perform(webhookRequest("test", payload, signature)).andExpect(status().isOk());
        mockMvc.perform(webhookRequest("test", payload, signature)).andExpect(status().isOk());

        assertThat(countEvents()).isOne();
        assertThat(jdbc.sql("SELECT processing_state FROM stripe_webhook_events WHERE stripe_event_id = 'evt_duplicate'")
                        .query(String.class)
                        .single())
                .isEqualTo("PENDING");
    }

    @Test
    void concurrentDuplicateDeliveriesBothAcknowledgeWithExactlyOneRowStored() throws Exception {
        UUID workspaceId = createWorkspace();
        insertActiveConnection(workspaceId, "acct_concurrent", StripeConnectionMode.TEST);
        String payload = event("evt_concurrent", "acct_concurrent", "invoice.paid", Instant.now());
        String signature = sign(payload, TEST_WEBHOOK_SECRET);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Callable<Integer> deliver = () -> {
                barrier.await();
                return mockMvc.perform(webhookRequest("test", payload, signature))
                        .andReturn()
                        .getResponse()
                        .getStatus();
            };
            List<Future<Integer>> results = executor.invokeAll(List.of(deliver, deliver));
            for (Future<Integer> result : results) {
                assertThat(result.get()).isEqualTo(200);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(countEvents()).isOne();
    }

    // ---- Delayed / out-of-order events --------------------------------------------------------

    @Test
    void delayedEventIsAcceptedAndRetainsItsOriginalStripeTimestamp() throws Exception {
        UUID workspaceId = createWorkspace();
        insertActiveConnection(workspaceId, "acct_delayed", StripeConnectionMode.TEST);
        Instant originalCreation = Instant.now().minusSeconds(6 * 3600);
        String payload = event("evt_delayed", "acct_delayed", "invoice.paid", originalCreation);
        // A fresh Stripe-Signature timestamp, exactly as a real delayed/retried delivery arrives --
        // the event's own `created` is hours old, but the delivery attempt itself is signed now.
        String signature = sign(payload, TEST_WEBHOOK_SECRET);

        mockMvc.perform(webhookRequest("test", payload, signature)).andExpect(status().isOk());

        OffsetDateTime storedCreatedAt = jdbc.sql(
                        "SELECT stripe_created_at FROM stripe_webhook_events WHERE stripe_event_id = 'evt_delayed'")
                .query(OffsetDateTime.class)
                .single();
        OffsetDateTime storedReceivedAt = jdbc.sql(
                        "SELECT received_at FROM stripe_webhook_events WHERE stripe_event_id = 'evt_delayed'")
                .query(OffsetDateTime.class)
                .single();
        assertThat(storedCreatedAt.toInstant()).isCloseTo(originalCreation, within(Duration.ofSeconds(2)));
        // received_at (our receipt time, now) stays clearly distinct from the event's own,
        // hours-old creation time -- the delayed delivery is accepted and both timestamps are kept.
        assertThat(storedReceivedAt).isAfter(storedCreatedAt.plusHours(1));
    }

    @Test
    void outOfOrderEventsAreEachPersistedIndependentlyOfArrivalOrder() throws Exception {
        UUID workspaceId = createWorkspace();
        insertActiveConnection(workspaceId, "acct_ordering", StripeConnectionMode.TEST);
        Instant earlier = Instant.now().minusSeconds(600);
        Instant later = Instant.now();

        String laterPayload = event("evt_later", "acct_ordering", "invoice.paid", later);
        String earlierPayload = event("evt_earlier", "acct_ordering", "invoice.created", earlier);

        // The later-created event is delivered first; the earlier one arrives second.
        mockMvc.perform(webhookRequest("test", laterPayload, sign(laterPayload, TEST_WEBHOOK_SECRET)))
                .andExpect(status().isOk());
        mockMvc.perform(webhookRequest("test", earlierPayload, sign(earlierPayload, TEST_WEBHOOK_SECRET)))
                .andExpect(status().isOk());

        assertThat(countEvents()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT event_type FROM stripe_webhook_events WHERE stripe_event_id = 'evt_later'")
                        .query(String.class)
                        .single())
                .isEqualTo("invoice.paid");
        assertThat(jdbc.sql("SELECT event_type FROM stripe_webhook_events WHERE stripe_event_id = 'evt_earlier'")
                        .query(String.class)
                        .single())
                .isEqualTo("invoice.created");
    }

    // ---- Account routing (unknown / disconnected / active) ------------------------------------

    @Test
    void unknownAccountEventIsAcknowledgedAndStoredAsOrphaned() throws Exception {
        String payload = event("evt_unknown_account", "acct_never_connected", "invoice.paid", Instant.now());
        String signature = sign(payload, TEST_WEBHOOK_SECRET);

        mockMvc.perform(webhookRequest("test", payload, signature)).andExpect(status().isOk());

        assertThat(jdbc.sql(
                        "SELECT processing_state FROM stripe_webhook_events WHERE stripe_event_id = 'evt_unknown_account'")
                        .query(String.class)
                        .single())
                .isEqualTo("ORPHANED");
        Map<String, Object> stored = jdbc.sql(
                        "SELECT connection_id, workspace_id FROM stripe_webhook_events "
                                + "WHERE stripe_event_id = 'evt_unknown_account'")
                .query()
                .singleRow();
        assertThat(stored.get("connection_id")).isNull();
        assertThat(stored.get("workspace_id")).isNull();
    }

    @Test
    void disconnectedAccountEventIsAcknowledgedAndStoredAsOrphaned() throws Exception {
        UUID workspaceId = createWorkspace();
        insertConnection(workspaceId, "acct_disconnected", StripeConnectionMode.TEST, StripeConnectionStatus.DISCONNECTED);
        String payload = event("evt_disconnected_account", "acct_disconnected", "invoice.paid", Instant.now());
        String signature = sign(payload, TEST_WEBHOOK_SECRET);

        mockMvc.perform(webhookRequest("test", payload, signature)).andExpect(status().isOk());

        assertThat(jdbc.sql(
                        "SELECT processing_state FROM stripe_webhook_events WHERE stripe_event_id = 'evt_disconnected_account'")
                        .query(String.class)
                        .single())
                .isEqualTo("ORPHANED");
        Map<String, Object> stored = jdbc.sql(
                        "SELECT connection_id, workspace_id FROM stripe_webhook_events "
                                + "WHERE stripe_event_id = 'evt_disconnected_account'")
                .query()
                .singleRow();
        assertThat(stored.get("connection_id")).isNull();
        assertThat(stored.get("workspace_id")).isNull();
    }

    @Test
    void activeAccountEventIsLinkedToItsWorkspaceAndLeftPending() throws Exception {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_active", StripeConnectionMode.TEST);
        String payload = event("evt_active_account", "acct_active", "customer.subscription.updated", Instant.now());
        String signature = sign(payload, TEST_WEBHOOK_SECRET);

        mockMvc.perform(webhookRequest("test", payload, signature)).andExpect(status().isOk());

        String eventTableRow = "SELECT %s FROM stripe_webhook_events WHERE stripe_event_id = 'evt_active_account'";
        assertThat(jdbc.sql(eventTableRow.formatted("processing_state")).query(String.class).single())
                .isEqualTo("PENDING");
        assertThat(jdbc.sql(eventTableRow.formatted("connection_id")).query(UUID.class).single())
                .isEqualTo(connectionId);
        assertThat(jdbc.sql(eventTableRow.formatted("workspace_id")).query(UUID.class).single())
                .isEqualTo(workspaceId);
        assertThat(jdbc.sql(eventTableRow.formatted("event_type")).query(String.class).single())
                .isEqualTo("customer.subscription.updated");
    }

    // ---- Raw bytes are preserved immutably, separate from the parsed JSONB mirror -------------

    @Test
    void rawPayloadBytesAreStoredExactlyAsReceived() throws Exception {
        UUID workspaceId = createWorkspace();
        insertActiveConnection(workspaceId, "acct_raw_bytes", StripeConnectionMode.TEST);
        String payload = event("evt_raw_bytes", "acct_raw_bytes", "invoice.paid", Instant.now());
        String signature = sign(payload, TEST_WEBHOOK_SECRET);

        mockMvc.perform(webhookRequest("test", payload, signature)).andExpect(status().isOk());

        byte[] storedRawPayload = jdbc.sql(
                        "SELECT raw_payload FROM stripe_webhook_events WHERE stripe_event_id = 'evt_raw_bytes'")
                .query(byte[].class)
                .single();
        assertThat(storedRawPayload).isEqualTo(payload.getBytes(StandardCharsets.UTF_8));
    }

    // ---- Oversized / malicious bodies ---------------------------------------------------------

    @Test
    void oversizedBodyIsRejectedWithPayloadTooLarge() throws Exception {
        byte[] oversized = new byte[MAX_REQUEST_BODY_BYTES + 1];
        Arrays.fill(oversized, (byte) 'a');

        mockMvc.perform(post("/api/stripe/webhooks/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", "t=0,v1=deadbeef")
                        .content(oversized))
                .andExpect(status().isPayloadTooLarge());

        assertThat(countEvents()).isZero();
    }

    @Test
    void oversizedBodyIsRejectedRegardlessOfAnIncorrectDeclaredContentLength() throws Exception {
        byte[] oversized = new byte[MAX_REQUEST_BODY_BYTES + 1];
        Arrays.fill(oversized, (byte) 'a');

        // The reader never trusts Content-Length -- it measures actual bytes streamed -- so a
        // header that lies about a small size must not let an oversized body slip through.
        mockMvc.perform(post("/api/stripe/webhooks/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", "t=0,v1=deadbeef")
                        .header(HttpHeaders.CONTENT_LENGTH, "10")
                        .content(oversized))
                .andExpect(status().isPayloadTooLarge());

        assertThat(countEvents()).isZero();
    }

    // ---- Database failure before acknowledgement ----------------------------------------------

    @Test
    void databaseFailureBeforeAcknowledgementIsNeverAcknowledgedAndTheRetrySucceeds() throws Exception {
        UUID workspaceId = createWorkspace();
        insertActiveConnection(workspaceId, "acct_db_failure", StripeConnectionMode.TEST);
        String payload = event("evt_db_failure", "acct_db_failure", "invoice.paid", Instant.now());
        String signature = sign(payload, TEST_WEBHOOK_SECRET);

        jdbc.sql("ALTER TABLE stripe_webhook_events RENAME TO stripe_webhook_events_disabled_for_test").update();
        try {
            // The underlying table is unavailable, so persistence fails before any acknowledgement
            // can be returned. MockMvc surfaces this as the servlet-dispatch exception itself
            // (rather than a captured response) since nothing in this path maps it to a
            // ResponseStatusException; a real deployment's default error handling still returns a
            // 5xx to Stripe, which is exactly what must happen -- the request is never a silent 200.
            Throwable failure = catchThrowable(() -> mockMvc.perform(webhookRequest("test", payload, signature)));
            assertThat(failure).isNotNull();
        } finally {
            jdbc.sql("ALTER TABLE stripe_webhook_events_disabled_for_test RENAME TO stripe_webhook_events")
                    .update();
        }

        assertThat(countEvents()).isZero();

        // Stripe retries an event whose delivery failed; the retry with the same event ID succeeds
        // and is durably stored exactly once.
        mockMvc.perform(webhookRequest("test", payload, signature)).andExpect(status().isOk());
        assertThat(countEvents()).isOne();
    }

    // ---- Helpers --------------------------------------------------------------------------

    private MockHttpServletRequestBuilder webhookRequest(String mode, String payload, String signature) {
        return post("/api/stripe/webhooks/{mode}", mode)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", signature)
                .content(payload);
    }

    private String sign(String payload, String secret) {
        return sign(payload, secret, Instant.now().getEpochSecond());
    }

    private String sign(String payload, String secret, long timestamp) {
        try {
            String signedPayload = timestamp + "." + payload;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hmac = mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8));
            return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(hmac);
        } catch (NoSuchAlgorithmException | InvalidKeyException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String event(String id, String accountId, String type, Instant created) {
        return event(id, accountId, type, created, false);
    }

    private String event(String id, String accountId, String type, Instant created, boolean livemode) {
        return """
                {"id":"%s","object":"event","api_version":"2024-06-20","created":%d,"type":"%s",
                 "account":"%s","livemode":%s,"data":{"object":{"id":"obj_%s"}}}"""
                .formatted(id, created.getEpochSecond(), type, accountId, livemode, id);
    }

    private int countEvents() {
        return jdbc.sql("SELECT COUNT(*) FROM stripe_webhook_events").query(Integer.class).single();
    }

    private UUID insertActiveConnection(UUID workspaceId, String stripeAccountId, StripeConnectionMode mode) {
        return insertConnection(workspaceId, stripeAccountId, mode, StripeConnectionStatus.ACTIVE);
    }

    private UUID insertConnection(
            UUID workspaceId, String stripeAccountId, StripeConnectionMode mode, StripeConnectionStatus status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO stripe_connections
                            (id, workspace_id, stripe_account_id, mode, granted_scope, status,
                             verification_status, connected_at)
                        VALUES (:id, :workspaceId, :stripeAccountId, :mode, 'read_only', :status,
                                'VERIFIED', CURRENT_TIMESTAMP)
                        """)
                .param("id", id)
                .param("workspaceId", workspaceId)
                .param("stripeAccountId", stripeAccountId)
                .param("mode", mode.name())
                .param("status", status.name())
                .update();
        return id;
    }

    private UUID createWorkspace() {
        UUID workspaceId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO workspaces (id, name, slug, reporting_currency)
                        VALUES (:id, :name, :slug, 'USD')
                        """)
                .param("id", workspaceId)
                .param("name", "Workspace " + workspaceId)
                .param("slug", "workspace-" + workspaceId)
                .update();
        return workspaceId;
    }
}
