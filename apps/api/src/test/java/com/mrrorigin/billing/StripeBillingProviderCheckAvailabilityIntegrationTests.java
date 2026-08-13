package com.mrrorigin.billing;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Regression coverage for #15's provider reconciliation availability: a completed local backfill
 * must not report HEALTHY when the live Stripe spot-check cannot be completed, because local/provider
 * agreement is unknown until that check succeeds.
 */
@Testcontainers
class StripeBillingProviderCheckAvailabilityIntegrationTests extends AbstractBillingLedgerIntegrationTest {

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
    void completedWorkspaceIsStaleWhenProviderReconciliationCannotRun() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_health_provider_unavailable", StripeConnectionMode.TEST);
        runBackfillToCompletion(connectionId);

        // Make the next live customer-list response malformed after the backfill has completed.
        // StripeBackfillClient maps this to a provider-request failure and health must preserve that
        // uncertainty instead of claiming the workspace is HEALTHY.
        STRIPE_LIST_STUB.seed("/v1/customers", List.of("{not-json"));

        StripeBillingHealthService.StripeBillingHealthReport report = healthService.health(workspaceId);

        assertThat(report.providerSpotCheck().available()).isFalse();
        assertThat(report.providerSpotCheck().unavailableReason()).isEqualTo("Stripe request failed");
        assertThat(report.reasons()).contains(StripeBillingHealthReason.PROVIDER_CHECK_UNAVAILABLE);
        assertThat(report.status()).isEqualTo(StripeBillingHealthStatus.STALE);
    }
}
