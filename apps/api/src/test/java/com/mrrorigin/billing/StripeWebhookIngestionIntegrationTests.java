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
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
 * #11: signature verification against unmodified bytes, durable persistence before
 * acknowledgement, and idempotent handling of duplicate/delayed/out-of-order/malformed/unrouteable
 * deliveries.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class StripeWebhookIngestionIntegrationTests {

    private static final String TEST_WEBHOOK_SECRET = "whsec_test_platform_secret";
    private static final String LIVE_WEBHOOK_SECRET = "whsec_live_platform_secret";

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

    @Test
    void invalidSignatureIsRejectedAndNotPersisted() throws Exception {
        String payload = event("evt_invalid_sig", "acct_unknown", "invoice.paid", Instant.now());

        mockMvc.perform(webhookRequest("test", payload, "t=1700000000,v1=" + "0".repeat(64)))
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
    void delayedEventIsAcceptedAndRetainsItsOriginalStripeTimestamp() throws Exception {
        UUID workspaceId = createWorkspace();
        insertActiveConnection(workspaceId, "acct_delayed", StripeConnectionMode.TEST);
        Instant originalCreation = Instant.now().minusSeconds(6 * 3600);
        String payload = event("evt_delayed", "acct_delayed", "invoice.paid", originalCreation);
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

    private MockHttpServletRequestBuilder webhookRequest(String mode, String payload, String signature) {
        return post("/api/stripe/webhooks/{mode}", mode)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", signature)
                .content(payload);
    }

    private String sign(String payload, String secret) {
        try {
            long timestamp = Instant.now().getEpochSecond();
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
        return """
                {"id":"%s","object":"event","api_version":"2024-06-20","created":%d,"type":"%s",
                 "account":"%s","livemode":false,"data":{"object":{"id":"obj_%s"}}}"""
                .formatted(id, created.getEpochSecond(), type, accountId, id);
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
