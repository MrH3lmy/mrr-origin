package com.mrrorigin.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.mrrorigin.revenue.RevenueCalculationService;
import com.mrrorigin.revenue.RevenueModels.Movement;

/** Proves an out-of-order subscription-before-price webhook recovers without stale MRR. */
@Testcontainers
class StripeMrrPriceDependencyRecoveryIntegrationTests extends AbstractBillingLedgerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    private static final Instant T0 = Instant.parse("2026-05-01T00:00:00Z");

    @Autowired private RevenueCalculationService revenue;
    @Autowired private StripeWebhookReplayService replayService;

    @Test
    void subscriptionBeforePriceFailsTransientlyThenReplayConvergesAfterPriceArrives() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_price_dependency", StripeConnectionMode.TEST);
        long start = T0.getEpochSecond();

        insertPendingWebhookEvent(
                connectionId,
                workspaceId,
                StripeConnectionMode.TEST,
                "evt_dep_customer",
                "customer.created",
                T0.minusSeconds(120),
                BillingFixtures.customer("cus_dep", "usd", start, false, null));
        assertThat(drainWebhookQueue()).isEqualTo(1);

        insertPendingWebhookEvent(
                connectionId,
                workspaceId,
                StripeConnectionMode.TEST,
                "evt_dep_subscription",
                "customer.subscription.created",
                T0,
                BillingFixtures.subscription(
                        "sub_dep",
                        "cus_dep",
                        "active",
                        "usd",
                        start,
                        start + 2_592_000L,
                        false,
                        null,
                        null,
                        BillingFixtures.subscriptionItem("si_dep", "price_dep", 1),
                        null));
        assertThat(drainWebhookQueue()).isEqualTo(1);

        UUID failedEventId = jdbc().sql(
                        "SELECT id FROM stripe_webhook_events WHERE workspace_id = :w AND stripe_event_id = 'evt_dep_subscription'")
                .param("w", workspaceId)
                .query(UUID.class)
                .single();
        String failureKind = jdbc().sql(
                        "SELECT failure_kind FROM stripe_webhook_events WHERE id = :id")
                .param("id", failedEventId)
                .query(String.class)
                .single();

        assertThat(failureKind).isEqualTo(StripeWebhookFailureKind.TRANSIENT.name());
        assertThat(subscriptionSnapshot(workspaceId, "sub_dep")).isEmpty();
        assertThat(revenue.movements(workspaceId, "cus_dep")).isEmpty();
        assertThat(revenue.snapshots(workspaceId, "cus_dep")).isEmpty();

        insertPendingWebhookEvent(
                connectionId,
                workspaceId,
                StripeConnectionMode.TEST,
                "evt_dep_price",
                "price.created",
                T0.minusSeconds(60),
                BillingFixtures.price("price_dep", "prod_dep", "usd", 2500L, "recurring", "month", 1, true));
        assertThat(drainWebhookQueue()).isEqualTo(1);
        assertThat(priceSnapshot(workspaceId, "price_dep")).isPresent();

        assertThat(replayService.replayEvent(workspaceId, failedEventId))
                .isEqualTo(StripeWebhookReplayService.ReplayOutcome.REPLAYED);
        assertThat(drainWebhookQueue()).isEqualTo(1);

        assertThat(subscriptionSnapshot(workspaceId, "sub_dep")).isPresent();
        assertThat(revenue.movements(workspaceId, "cus_dep"))
                .extracting(Movement::type, Movement::amountMinor)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("NEW", 2500L));

        assertThat(replayService.replayEvent(workspaceId, failedEventId))
                .isEqualTo(StripeWebhookReplayService.ReplayOutcome.NOT_ELIGIBLE);
        assertThat(revenue.movements(workspaceId, "cus_dep"))
                .extracting(Movement::type, Movement::amountMinor)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("NEW", 2500L));
    }
}
