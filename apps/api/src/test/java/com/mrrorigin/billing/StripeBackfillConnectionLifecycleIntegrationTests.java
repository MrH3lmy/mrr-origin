package com.mrrorigin.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * #12's connection-lifecycle gating: backfill only ever runs for a connection that is currently
 * ACTIVE and VERIFIED, and a page fetched while eligible can never apply after the connection stops
 * being eligible mid-run.
 */
@Testcontainers
class StripeBackfillConnectionLifecycleIntegrationTests extends AbstractBillingLedgerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    private static final StripeBillingListApiStub STRIPE_LIST_STUB = new StripeBillingListApiStub();

    @DynamicPropertySource
    static void stripeListApi(DynamicPropertyRegistry registry) {
        registry.add("mrrorigin.stripe.connect.api-base-uri", STRIPE_LIST_STUB::apiBaseUri);
    }

    @Autowired
    private StripeBackfillPageRunner pageRunner;

    @Autowired
    private ObjectMapper objectMapper;

    static Stream<org.junit.jupiter.params.provider.Arguments> ineligibleStates() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(StripeConnectionStatus.PENDING, StripeVerificationStatus.UNVERIFIED),
                org.junit.jupiter.params.provider.Arguments.of(StripeConnectionStatus.DISCONNECTED, StripeVerificationStatus.VERIFIED),
                org.junit.jupiter.params.provider.Arguments.of(StripeConnectionStatus.REVOKED, StripeVerificationStatus.FAILED),
                org.junit.jupiter.params.provider.Arguments.of(StripeConnectionStatus.ACTIVE, StripeVerificationStatus.FAILED));
    }

    @ParameterizedTest(name = "status={0} verificationStatus={1}")
    @MethodSource("ineligibleStates")
    void backfillRefusesToRunForAnIneligibleConnection(StripeConnectionStatus status, StripeVerificationStatus verificationStatus) {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertConnection(workspaceId, "acct_ineligible_" + status, StripeConnectionMode.TEST, status, verificationStatus);
        STRIPE_LIST_STUB.seed("/v1/customers", List.of(BillingFixtures.customer("cus_x", "usd", Instant.now().getEpochSecond(), false, null)));

        assertThatThrownBy(() -> backfillService.runBatch(connectionId, 5))
                .isInstanceOf(StripeBackfillIneligibleConnectionException.class);
        assertThat(jdbc().sql("SELECT COUNT(*) FROM billing_customers WHERE workspace_id = :w")
                        .param("w", workspaceId)
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    void backfillRunsNormallyForAnActiveVerifiedConnection() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_eligible", StripeConnectionMode.TEST);
        STRIPE_LIST_STUB.seed("/v1/customers", List.of(BillingFixtures.customer("cus_ok", "usd", Instant.now().getEpochSecond(), false, null)));
        STRIPE_LIST_STUB.seed("/v1/prices", List.of());
        STRIPE_LIST_STUB.seed("/v1/subscriptions", List.of());
        STRIPE_LIST_STUB.seed("/v1/invoices", List.of());
        STRIPE_LIST_STUB.seed("/v1/charges", List.of());
        STRIPE_LIST_STUB.seed("/v1/refunds", List.of());

        runBackfillToCompletion(connectionId);

        assertThat(jdbc().sql("SELECT COUNT(*) FROM billing_customers WHERE workspace_id = :w")
                        .param("w", workspaceId)
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void aPageFetchedWhileEligibleIsRejectedIfTheConnectionIsDisconnectedBeforeItApplies() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_race", StripeConnectionMode.TEST);

        List<JsonNode> page = parse(List.of(BillingFixtures.customer("cus_race", "usd", Instant.now().getEpochSecond(), false, null)));
        Consumer<JsonNode> normalizer = item -> ledger.upsertCustomer(
                workspaceId, StripeBillingObjectParser.parseCustomer(item), BillingSourceVersion.forBackfillFetch(Instant.now()),
                BillingLedgerSource.BACKFILL);

        // Simulates the connection being disconnected by the user in the moment between this page
        // being fetched (while still ACTIVE/VERIFIED) and applyPage's lock acquisition.
        jdbc().sql("UPDATE stripe_connections SET status = 'DISCONNECTED' WHERE id = :id")
                .param("id", connectionId)
                .update();

        StripeBackfillPageRunner.PageApplyOutcome outcome =
                pageRunner.applyPage(connectionId, StripeBackfillPhase.CUSTOMERS, null, page, false, normalizer);

        assertThat(outcome.status()).isEqualTo(StripeBackfillPageRunner.PageApplyStatus.CONNECTION_INELIGIBLE);
        assertThat(jdbc().sql("SELECT COUNT(*) FROM billing_customers WHERE workspace_id = :w")
                        .param("w", workspaceId)
                        .query(Integer.class)
                        .single())
                .isZero();
        assertThat(jdbc().sql("SELECT sync_checkpoint FROM stripe_connections WHERE id = :id")
                        .param("id", connectionId)
                        .query(String.class)
                        .optional())
                .isEmpty();
    }

    private List<JsonNode> parse(List<String> rawItems) {
        List<JsonNode> parsed = new ArrayList<>();
        for (String raw : rawItems) {
            parsed.add(objectMapper.readTree(raw));
        }
        return parsed;
    }
}
