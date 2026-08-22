package com.mrrorigin.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * P6 observability slice (#28/#90): proves the Stripe webhook received/processed/failed/replay
 * counters and the pending-backlog/oldest-age gauges reflect real HTTP deliveries and real persisted
 * {@code stripe_webhook_events} state, across two workspaces at once (aggregation, never a
 * per-tenant series).
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class StripeWebhookMetricsIntegrationTests {

    private static final String TEST_WEBHOOK_SECRET = "whsec_test_platform_secret";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    @DynamicPropertySource
    static void stripeProperties(DynamicPropertyRegistry registry) {
        registry.add("mrrorigin.stripe.connect.test-client-id", () -> "ca_test_123");
        registry.add("mrrorigin.stripe.connect.test-secret-key", () -> "sk_test_platform_secret");
        registry.add("mrrorigin.stripe.connect.live-client-id", () -> "ca_live_123");
        registry.add("mrrorigin.stripe.connect.live-secret-key", () -> "sk_live_platform_secret");
        registry.add("mrrorigin.stripe.connect.test-webhook-secret", () -> TEST_WEBHOOK_SECRET);
        registry.add("mrrorigin.stripe.connect.live-webhook-secret", () -> "whsec_live_platform_secret");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private StripeWebhookNormalizationService normalizationService;

    @Autowired
    private StripeWebhookReplayService replayService;

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

    private double counter(String name, String... tags) {
        Counter c = meterRegistry.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    private double gauge(String name, String... tags) {
        var g = meterRegistry.find(name).tags(tags).gauge();
        return g == null ? 0 : g.value();
    }

    @Test
    void aFreshDeliveryToAConnectedWorkspaceIncrementsStoredReceivedCounter() throws Exception {
        UUID workspaceId = createWorkspace();
        insertActiveConnection(workspaceId, "acct_metrics_stored", StripeConnectionMode.TEST);
        String payload = event("evt_metrics_stored", "acct_metrics_stored", "invoice.paid", Instant.now());

        double before = counter("mrrorigin.stripe.webhook.received", "mode", "test", "outcome", "stored");
        mockMvc.perform(webhookRequest("test", payload, sign(payload, TEST_WEBHOOK_SECRET))).andExpect(status().isOk());
        double after = counter("mrrorigin.stripe.webhook.received", "mode", "test", "outcome", "stored");

        assertThat(after).isGreaterThan(before);
    }

    @Test
    void aRetriedDeliveryIncrementsDuplicateReceivedCounter() throws Exception {
        UUID workspaceId = createWorkspace();
        insertActiveConnection(workspaceId, "acct_metrics_dup", StripeConnectionMode.TEST);
        String payload = event("evt_metrics_dup", "acct_metrics_dup", "invoice.paid", Instant.now());
        String signature = sign(payload, TEST_WEBHOOK_SECRET);
        mockMvc.perform(webhookRequest("test", payload, signature)).andExpect(status().isOk());

        double before = counter("mrrorigin.stripe.webhook.received", "mode", "test", "outcome", "duplicate");
        mockMvc.perform(webhookRequest("test", payload, signature)).andExpect(status().isOk());
        double after = counter("mrrorigin.stripe.webhook.received", "mode", "test", "outcome", "duplicate");

        assertThat(after).isGreaterThan(before);
    }

    @Test
    void anUnroutableAccountIncrementsOrphanedReceivedCounter() throws Exception {
        String payload = event("evt_metrics_orphan", "acct_unknown_metrics", "invoice.paid", Instant.now());

        double before = counter("mrrorigin.stripe.webhook.received", "mode", "test", "outcome", "orphaned");
        mockMvc.perform(webhookRequest("test", payload, sign(payload, TEST_WEBHOOK_SECRET))).andExpect(status().isOk());
        double after = counter("mrrorigin.stripe.webhook.received", "mode", "test", "outcome", "orphaned");

        assertThat(after).isGreaterThan(before);
    }

    @Test
    void pendingBacklogAndOldestAgeGaugesReflectPersistedStateAcrossTwoWorkspaces() throws Exception {
        UUID workspaceA = createWorkspace();
        insertActiveConnection(workspaceA, "acct_metrics_backlog_a", StripeConnectionMode.TEST);
        UUID workspaceB = createWorkspace();
        insertActiveConnection(workspaceB, "acct_metrics_backlog_b", StripeConnectionMode.TEST);

        double pendingBefore = gauge("mrrorigin.stripe.webhook.pending", "mode", "test");

        String payloadA = event("evt_backlog_a", "acct_metrics_backlog_a", "invoice.paid", Instant.now());
        mockMvc.perform(webhookRequest("test", payloadA, sign(payloadA, TEST_WEBHOOK_SECRET))).andExpect(status().isOk());
        String payloadB = event("evt_backlog_b", "acct_metrics_backlog_b", "invoice.paid", Instant.now());
        mockMvc.perform(webhookRequest("test", payloadB, sign(payloadB, TEST_WEBHOOK_SECRET))).andExpect(status().isOk());

        // Both workspaces' PENDING rows contribute to the SAME aggregate gauge -- never a per-tenant series.
        double pendingAfter = gauge("mrrorigin.stripe.webhook.pending", "mode", "test");
        assertThat(pendingAfter).isGreaterThanOrEqualTo(pendingBefore + 2);
        assertThat(gauge("mrrorigin.stripe.webhook.oldest_pending_age_seconds", "mode", "test")).isGreaterThanOrEqualTo(0);

        // Draining removes them from PENDING and the gauge reflects that live, on the next scrape.
        drainAll();
        assertThat(gauge("mrrorigin.stripe.webhook.pending", "mode", "test")).isEqualTo(pendingBefore);
    }

    @Test
    void oldestPendingAgeGaugeReflectsAnExplicitlyBackdatedEvent() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_metrics_oldest_age", StripeConnectionMode.TEST);
        java.time.OffsetDateTime receivedAt = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusHours(2);
        jdbc.sql("""
                        INSERT INTO stripe_webhook_events
                            (id, stripe_event_id, stripe_account_id, mode, connection_id, workspace_id,
                             event_type, stripe_created_at, received_at, raw_payload, payload, processing_state)
                        VALUES (:id, 'evt_oldest_age', 'acct_metrics_oldest_age', 'TEST', :connectionId, :workspaceId,
                                'invoice.paid', :receivedAt, :receivedAt, :raw, '{}', 'PENDING')
                        """)
                .param("id", UUID.randomUUID())
                .param("connectionId", connectionId)
                .param("workspaceId", workspaceId)
                .param("receivedAt", receivedAt)
                .param("raw", "{}".getBytes(StandardCharsets.UTF_8))
                .update();

        double ageSeconds = gauge("mrrorigin.stripe.webhook.oldest_pending_age_seconds", "mode", "test");
        assertThat(ageSeconds).isGreaterThanOrEqualTo(java.time.Duration.ofHours(2).minusMinutes(1).toSeconds());
    }

    @Test
    void webhookNormalizationSuccessIncrementsProcessedCounter() throws Exception {
        UUID workspaceId = createWorkspace();
        insertActiveConnection(workspaceId, "acct_metrics_processed", StripeConnectionMode.TEST);
        long createdEpoch = Instant.now().getEpochSecond();
        String customerObject = """
                {"id":"cus_metrics_processed","object":"customer","created":%d,"currency":"usd","deleted":false}"""
                .formatted(createdEpoch);
        String payload = """
                {"id":"evt_metrics_processed","object":"event","api_version":"2024-06-20","created":%d,
                 "type":"customer.created","account":"acct_metrics_processed","livemode":false,
                 "data":{"object":%s}}"""
                .formatted(createdEpoch, customerObject);
        mockMvc.perform(webhookRequest("test", payload, sign(payload, TEST_WEBHOOK_SECRET))).andExpect(status().isOk());

        double before = counter("mrrorigin.stripe.webhook.processed", "result", "processed");
        drainAll();
        double after = counter("mrrorigin.stripe.webhook.processed", "result", "processed");

        assertThat(after).isGreaterThan(before);
    }

    @Test
    void aFailedUnsupportedEventIncrementsFailedCounterAndAReplaySucceeds() throws Exception {
        UUID workspaceId = createWorkspace();
        insertActiveConnection(workspaceId, "acct_metrics_failure", StripeConnectionMode.TEST);
        // A "customer.subscription.created" event whose object has no usable id/items is UNSUPPORTED
        // (StripeBillingObjectParser rejects the shape), not TRANSIENT.
        String payload = """
                {"id":"evt_metrics_unsupported","object":"event","api_version":"2024-06-20","created":%d,
                 "type":"customer.subscription.created","account":"acct_metrics_failure","livemode":false,
                 "data":{"object":{}}}"""
                .formatted(Instant.now().getEpochSecond());

        double failedBefore = counter("mrrorigin.stripe.webhook.failed", "failure_kind", "unsupported");
        mockMvc.perform(webhookRequest("test", payload, sign(payload, TEST_WEBHOOK_SECRET))).andExpect(status().isOk());
        drainAll();
        assertThat(counter("mrrorigin.stripe.webhook.failed", "failure_kind", "unsupported")).isGreaterThan(failedBefore);

        double replayBefore = counter("mrrorigin.stripe.webhook.replay");
        replayService.replayFailed(workspaceId, 10);
        assertThat(counter("mrrorigin.stripe.webhook.replay")).isGreaterThan(replayBefore);
    }

    // ---- Helpers --------------------------------------------------------------------------

    private void drainAll() {
        StripeWebhookNormalizationService.NormalizationRunOutcome outcome;
        do {
            outcome = normalizationService.processBatch(50);
        } while (outcome.fetched() > 0);
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

    private UUID insertActiveConnection(UUID workspaceId, String stripeAccountId, StripeConnectionMode mode) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO stripe_connections
                            (id, workspace_id, stripe_account_id, mode, granted_scope, status,
                             verification_status, connected_at)
                        VALUES (:id, :workspaceId, :stripeAccountId, :mode, 'read_only', 'ACTIVE',
                                'VERIFIED', CURRENT_TIMESTAMP)
                        """)
                .param("id", id)
                .param("workspaceId", workspaceId)
                .param("stripeAccountId", stripeAccountId)
                .param("mode", mode.name())
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
