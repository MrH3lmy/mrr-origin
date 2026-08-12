package com.mrrorigin.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * #15's workspace-scoped Stripe billing health: HEALTHY/STALE/DEGRADED with deterministic reasons,
 * stale-checkpoint detection, failed-webhook visibility, reconciliation mismatches + recovery, and
 * cross-workspace isolation of the report itself.
 */
@Testcontainers
class StripeBillingHealthIntegrationTests extends AbstractBillingLedgerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    private static final StripeBillingListApiStub STRIPE_LIST_STUB = new StripeBillingListApiStub();

    @DynamicPropertySource
    static void stripeListApi(DynamicPropertyRegistry registry) {
        registry.add("mrrorigin.stripe.connect.api-base-uri", STRIPE_LIST_STUB::apiBaseUri);
    }

    @Autowired
    private StripeBillingHealthService healthService;

    private static final Instant BASE = Instant.parse("2026-03-01T00:00:00Z");

    @Test
    void healthyWhenConnectionActiveAndFullySyncedWithNoIssues() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_health_happy", StripeConnectionMode.TEST);
        runBackfillToCompletion(connectionId);

        StripeBillingHealthService.StripeBillingHealthReport report = healthService.health(workspaceId);

        assertThat(report.status()).isEqualTo(StripeBillingHealthStatus.HEALTHY);
        assertThat(report.reasons()).isEmpty();
        assertThat(report.connectionPresent()).isTrue();
        assertThat(report.connectionStatus()).isEqualTo(StripeConnectionStatus.ACTIVE);
        assertThat(report.verificationStatus()).isEqualTo(StripeVerificationStatus.VERIFIED);
        assertThat(report.backfillComplete()).isTrue();
        assertThat(report.reconciliationMismatches()).isEmpty();
        assertThat(report.syncLagSeconds()).isNotNull().isLessThan(60L);
    }

    @Test
    void degradedWhenNoConnectionHasEverBeenAuthorized() {
        UUID workspaceId = createWorkspace();

        StripeBillingHealthService.StripeBillingHealthReport report = healthService.health(workspaceId);

        assertThat(report.status()).isEqualTo(StripeBillingHealthStatus.DEGRADED);
        assertThat(report.reasons()).containsExactly(StripeBillingHealthReason.NO_ACTIVE_CONNECTION);
        assertThat(report.connectionPresent()).isFalse();
        assertThat(report.connectionStatus()).isNull();
        assertThat(report.lastSyncAt()).isNull();
        assertThat(report.syncLagSeconds()).isNull();
    }

    // ---- Stale checkpoint detection ---------------------------------------------------------------

    @Test
    void staleWhenSyncActivityIsOlderThanTheThreshold() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_health_stale", StripeConnectionMode.TEST);
        runBackfillToCompletion(connectionId);
        backdateConnectionUpdatedAt(connectionId, Duration.ofHours(30));

        StripeBillingHealthService.StripeBillingHealthReport report = healthService.health(workspaceId);

        assertThat(report.status()).isEqualTo(StripeBillingHealthStatus.STALE);
        assertThat(report.reasons()).contains(StripeBillingHealthReason.SYNC_LAG_EXCEEDED);
        assertThat(report.syncLagSeconds()).isGreaterThan(Duration.ofHours(24).toSeconds());
    }

    @Test
    void notYetStaleWhenSyncActivityIsRecent() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_health_recent", StripeConnectionMode.TEST);
        runBackfillToCompletion(connectionId);
        backdateConnectionUpdatedAt(connectionId, Duration.ofHours(1));

        StripeBillingHealthService.StripeBillingHealthReport report = healthService.health(workspaceId);

        assertThat(report.status()).isEqualTo(StripeBillingHealthStatus.HEALTHY);
        assertThat(report.reasons()).doesNotContain(StripeBillingHealthReason.SYNC_LAG_EXCEEDED);
    }

    // ---- Failed webhook recovery / visibility -----------------------------------------------------

    @Test
    void degradedWhenFailedWebhookEventsArePresentAndDiagnosticsExposeNoPayload() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_health_failed", StripeConnectionMode.TEST);
        insertPendingWebhookEvent(
                connectionId, workspaceId, StripeConnectionMode.TEST, "evt_health_unsupported", "customer.discount.created",
                BASE, "{\"id\":\"di_missing_coupon_health\",\"object\":\"discount\"}");
        drainWebhookQueue();

        StripeBillingHealthService.StripeBillingHealthReport report = healthService.health(workspaceId);
        assertThat(report.status()).isEqualTo(StripeBillingHealthStatus.DEGRADED);
        assertThat(report.reasons()).contains(StripeBillingHealthReason.WEBHOOK_FAILURES_PRESENT);
        assertThat(report.failedWebhookEventsUnsupported()).isEqualTo(1);
        assertThat(report.failedWebhookEventsTransient()).isZero();

        List<StripeBillingHealthService.FailedEventDiagnostic> failed = healthService.failedEvents(workspaceId, 10);
        assertThat(failed).hasSize(1);
        StripeBillingHealthService.FailedEventDiagnostic diagnostic = failed.get(0);
        assertThat(diagnostic.stripeEventId()).isEqualTo("evt_health_unsupported");
        assertThat(diagnostic.failureKind()).isEqualTo("UNSUPPORTED");
        assertThat(diagnostic.lastError()).isNotBlank();
        // No payload/raw_payload is ever surfaced through this diagnostic record's fields.
        assertThat(diagnostic.toString()).doesNotContain("di_missing_coupon_health");
    }

    // ---- Reconciliation mismatches and recovery ---------------------------------------------------

    @Test
    void reconciliationMismatchIsDetectedThenResolvedOnceTheMissingCustomerArrives() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_health_mismatch", StripeConnectionMode.TEST);

        String item = BillingFixtures.subscriptionItem("si_health_mismatch", "price_health_mismatch", 1);
        String subscription = BillingFixtures.subscription(
                "sub_health_mismatch", "cus_health_missing", "active", "usd", BASE.getEpochSecond(),
                BASE.plusSeconds(2_592_000).getEpochSecond(), false, null, null, item, null);
        insertPendingWebhookEvent(
                connectionId, workspaceId, StripeConnectionMode.TEST, "evt_health_mismatch_sub",
                "customer.subscription.created", BASE, subscription);
        drainWebhookQueue();
        assertThat(subscriptionSnapshot(workspaceId, "sub_health_mismatch")).isPresent();

        StripeBillingHealthService.StripeBillingHealthReport before = healthService.health(workspaceId);
        assertThat(before.status()).isEqualTo(StripeBillingHealthStatus.DEGRADED);
        assertThat(before.reasons()).contains(StripeBillingHealthReason.RECONCILIATION_MISMATCH_PRESENT);
        assertThat(before.reconciliationMismatches())
                .extracting(StripeBillingHealthService.ReconciliationMismatch::kind)
                .contains("SUBSCRIPTION_MISSING_CUSTOMER");
        StripeBillingHealthService.ReconciliationMismatch mismatch = before.reconciliationMismatches().stream()
                .filter(m -> m.kind().equals("SUBSCRIPTION_MISSING_CUSTOMER"))
                .findFirst()
                .orElseThrow();
        assertThat(mismatch.sampleStripeIds()).contains("sub_health_mismatch");
        assertThat(mismatch.count()).isEqualTo(1);
        assertThat(mismatch.truncated()).isFalse();

        String customer = BillingFixtures.customer("cus_health_missing", "usd", BASE.getEpochSecond(), false, null);
        insertPendingWebhookEvent(
                connectionId, workspaceId, StripeConnectionMode.TEST, "evt_health_mismatch_cust", "customer.created",
                BASE.plusSeconds(1), customer);
        drainWebhookQueue();

        StripeBillingHealthService.StripeBillingHealthReport after = healthService.health(workspaceId);
        assertThat(after.reconciliationMismatches()).isEmpty();
        assertThat(after.reasons()).doesNotContain(StripeBillingHealthReason.RECONCILIATION_MISMATCH_PRESENT);
    }

    // ---- Cross-workspace isolation -----------------------------------------------------------------

    @Test
    void healthAndDiagnosticsNeverLeakAcrossWorkspaces() {
        UUID workspaceA = createWorkspace();
        UUID connectionA = insertActiveConnection(workspaceA, "acct_health_iso_a", StripeConnectionMode.TEST);
        insertPendingWebhookEvent(
                connectionA, workspaceA, StripeConnectionMode.TEST, "evt_health_iso_a", "customer.discount.created",
                BASE, "{\"id\":\"di_iso_a\",\"object\":\"discount\"}");
        drainWebhookQueue();

        UUID workspaceB = createWorkspace();
        StripeBillingHealthService.StripeBillingHealthReport reportB = healthService.health(workspaceB);

        assertThat(reportB.status()).isEqualTo(StripeBillingHealthStatus.DEGRADED);
        assertThat(reportB.reasons()).containsExactly(StripeBillingHealthReason.NO_ACTIVE_CONNECTION);
        assertThat(reportB.failedWebhookEventsUnsupported()).isZero();
        assertThat(reportB.ledgerTotals().customers()).isZero();
        assertThat(healthService.failedEvents(workspaceB, 10)).isEmpty();
    }

    private void backdateConnectionUpdatedAt(UUID connectionId, Duration age) {
        jdbc().sql("UPDATE stripe_connections SET updated_at = :updatedAt WHERE id = :id")
                .param("updatedAt", OffsetDateTime.now(ZoneOffset.UTC).minus(age))
                .param("id", connectionId)
                .update();
    }
}
