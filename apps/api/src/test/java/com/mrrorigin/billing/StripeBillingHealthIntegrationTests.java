package com.mrrorigin.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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

    /**
     * STRIPE_LIST_STUB is a single static instance shared by every test in this class (mirroring
     * {@code StripeBackfillCheckpointIntegrationTests}' convention), so a path one test seeds would
     * otherwise leak into a later test that never touches it. Reset every path to empty before each
     * test; tests that need specific provider-side data seed it explicitly afterward.
     */
    @BeforeEach
    void resetProviderCatalog() {
        STRIPE_LIST_STUB.seed("/v1/customers", List.of());
        STRIPE_LIST_STUB.seed("/v1/prices", List.of());
        STRIPE_LIST_STUB.seed("/v1/subscriptions", List.of());
        STRIPE_LIST_STUB.seed("/v1/invoices", List.of());
        STRIPE_LIST_STUB.seed("/v1/charges", List.of());
        STRIPE_LIST_STUB.seed("/v1/refunds", List.of());
    }

    @Test
    void healthyWhenConnectionActiveAndFullySyncedWithNoIssues() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_health_happy", StripeConnectionMode.TEST);
        runBackfillToCompletion(connectionId);
        insertSyncedCustomer(connectionId, workspaceId, "cus_health_happy");

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

    /**
     * Regression: staleness must reflect an actual processing backlog, never mere business-data
     * quiet. A fully backfilled, non-empty account whose ledger simply hasn't changed in over a day
     * (nothing happened on Stripe) has no pending or failed work, so it must stay HEALTHY -- it is
     * quiet, not broken. {@link #lastSyncAt} still reports the old timestamp for visibility, but it
     * must not by itself produce {@link StripeBillingHealthReason#SYNC_LAG_EXCEEDED}.
     */
    @Test
    void quietAccountWithOldLedgerActivityButNoBacklogStaysHealthy() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_health_quiet", StripeConnectionMode.TEST);
        runBackfillToCompletion(connectionId);
        insertSyncedCustomer(connectionId, workspaceId, "cus_health_quiet");
        backdateLedgerRowUpdatedAt("billing_customers", workspaceId, "cus_health_quiet", Duration.ofHours(30));

        StripeBillingHealthService.StripeBillingHealthReport report = healthService.health(workspaceId);

        assertThat(report.status()).isEqualTo(StripeBillingHealthStatus.HEALTHY);
        assertThat(report.reasons()).doesNotContain(StripeBillingHealthReason.SYNC_LAG_EXCEEDED);
        assertThat(report.pendingWebhookEvents()).isZero();
        // Informational only: the old ledger timestamp is still visible, it just doesn't drive status.
        assertThat(report.syncLagSeconds()).isGreaterThan(Duration.ofHours(24).toSeconds());
        assertThat(report.oldestPendingEventAgeSeconds()).isNull();
    }

    /**
     * Regression: a genuine processing backlog -- a webhook event that has sat PENDING far longer
     * than any normal processing delay -- must be caught even though the connection itself is
     * perfectly healthy (ACTIVE/VERIFIED) and backfill is complete.
     */
    @Test
    void staleWhenAPendingWebhookEventHasBeenWaitingLongerThanTheThreshold() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_health_backlog", StripeConnectionMode.TEST);
        runBackfillToCompletion(connectionId);
        insertStalePendingEvent(connectionId, workspaceId, "evt_health_backlog", Duration.ofHours(30));

        StripeBillingHealthService.StripeBillingHealthReport report = healthService.health(workspaceId);

        assertThat(report.status()).isEqualTo(StripeBillingHealthStatus.STALE);
        assertThat(report.reasons()).contains(StripeBillingHealthReason.SYNC_LAG_EXCEEDED);
        assertThat(report.pendingWebhookEvents()).isEqualTo(1);
        assertThat(report.oldestPendingEventAgeSeconds()).isGreaterThan(Duration.ofHours(24).toSeconds());
    }

    @Test
    void notYetStaleWhenAPendingWebhookEventIsRecent() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_health_backlog_recent", StripeConnectionMode.TEST);
        runBackfillToCompletion(connectionId);
        insertStalePendingEvent(connectionId, workspaceId, "evt_health_backlog_recent", Duration.ofMinutes(5));

        StripeBillingHealthService.StripeBillingHealthReport report = healthService.health(workspaceId);

        assertThat(report.status()).isEqualTo(StripeBillingHealthStatus.HEALTHY);
        assertThat(report.reasons()).doesNotContain(StripeBillingHealthReason.SYNC_LAG_EXCEEDED);
    }

    @Test
    void anEmptyButFullySyncedAccountIsHealthyRatherThanPermanentlyStale() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_health_empty", StripeConnectionMode.TEST);
        runBackfillToCompletion(connectionId);

        StripeBillingHealthService.StripeBillingHealthReport report = healthService.health(workspaceId);

        assertThat(report.status()).isEqualTo(StripeBillingHealthStatus.HEALTHY);
        assertThat(report.reasons()).doesNotContain(StripeBillingHealthReason.SYNC_LAG_EXCEEDED);
        assertThat(report.lastSyncAt()).isNull();
        assertThat(report.ledgerTotals().customers()).isZero();
    }

    // ---- Provider spot-check (live comparison against Stripe) ---------------------------------------

    /**
     * Regression: intra-ledger reconciliation ({@link #reconciliationMismatchIsDetectedThenResolvedOnceTheMissingCustomerArrives})
     * can only see a gap between two local records; it cannot see a Stripe object with no dependent
     * local row at all. This seeds a customer directly on Stripe's (stubbed) side, past a completed
     * backfill and with no webhook ever received for it, and asserts the bounded live spot-check
     * catches it.
     */
    @Test
    void providerSpotCheckDetectsACustomerThatExistsOnStripeButNotLocallyAtAll() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_health_provider_gap", StripeConnectionMode.TEST);
        runBackfillToCompletion(connectionId);

        STRIPE_LIST_STUB.seed(
                "/v1/customers",
                List.of(BillingFixtures.customer("cus_provider_gap", "usd", BASE.getEpochSecond(), false, null)));

        StripeBillingHealthService.StripeBillingHealthReport report = healthService.health(workspaceId);

        assertThat(report.status()).isEqualTo(StripeBillingHealthStatus.DEGRADED);
        assertThat(report.reasons()).contains(StripeBillingHealthReason.PROVIDER_RECONCILIATION_MISMATCH_PRESENT);
        assertThat(report.providerSpotCheck().available()).isTrue();
        assertThat(report.providerSpotCheck().missingCustomerIds()).containsExactly("cus_provider_gap");
    }

    @Test
    void providerSpotCheckDoesNotRunAndDoesNotFalselyDegradeWhileInitialBackfillIsIncomplete() {
        UUID workspaceId = createWorkspace();
        insertActiveConnection(workspaceId, "acct_health_provider_incomplete", StripeConnectionMode.TEST);
        STRIPE_LIST_STUB.seed(
                "/v1/customers",
                List.of(BillingFixtures.customer("cus_provider_incomplete", "usd", BASE.getEpochSecond(), false, null)));

        StripeBillingHealthService.StripeBillingHealthReport report = healthService.health(workspaceId);

        assertThat(report.providerSpotCheck().available()).isFalse();
        assertThat(report.reasons()).contains(StripeBillingHealthReason.PROVIDER_CHECK_UNAVAILABLE);
        assertThat(report.reasons()).doesNotContain(StripeBillingHealthReason.PROVIDER_RECONCILIATION_MISMATCH_PRESENT);
        // No billing data has synced at all yet (backfill still on its first phase), so this is
        // correctly STALE ("not yet trusted as current"), never a false DEGRADED.
        assertThat(report.status()).isEqualTo(StripeBillingHealthStatus.STALE);
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
        seedPrice(workspaceId, "price_health_mismatch");

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

    private void insertSyncedCustomer(UUID connectionId, UUID workspaceId, String stripeCustomerId) {
        String customer = BillingFixtures.customer(stripeCustomerId, "usd", BASE.getEpochSecond(), false, null);
        insertPendingWebhookEvent(
                connectionId, workspaceId, StripeConnectionMode.TEST, "evt_sync_" + stripeCustomerId, "customer.created",
                BASE, customer);
        drainWebhookQueue();
    }

    private void backdateLedgerRowUpdatedAt(String table, UUID workspaceId, String stripeCustomerId, Duration age) {
        jdbc().sql("UPDATE " + table + " SET updated_at = :updatedAt WHERE workspace_id = :w AND stripe_customer_id = :id")
                .param("updatedAt", OffsetDateTime.now(ZoneOffset.UTC).minus(age))
                .param("w", workspaceId)
                .param("id", stripeCustomerId)
                .update();
    }

    /** Inserts a raw PENDING webhook event backdated by {@code age}, deliberately never drained. */
    private void insertStalePendingEvent(UUID connectionId, UUID workspaceId, String eventId, Duration age) {
        Instant receivedAt = Instant.now().minus(age);
        String customer = BillingFixtures.customer("cus_" + eventId, "usd", receivedAt.getEpochSecond(), false, null);
        insertPendingWebhookEvent(
                connectionId, workspaceId, StripeConnectionMode.TEST, eventId, "customer.created", receivedAt, receivedAt,
                customer);
    }
}
