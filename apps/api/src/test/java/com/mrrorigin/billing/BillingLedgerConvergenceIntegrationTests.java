package com.mrrorigin.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The #12 acceptance-criteria fixture: a deterministic customer/subscription/invoice/payment/
 * refund/discount journey is processed three ways -- backfill-only, webhook-only, and a mix of
 * both -- and every run must land on the exact same normalized snapshot.
 *
 * <p>One deliberate, documented asymmetry: {@code billing_subscription_status_events} history is
 * NOT asserted equal for the backfill-only run, because a backfill only ever observes Stripe's
 * current live state, never the history of how it got there -- it correctly records a single
 * "discovered as active" transition instead of replaying trialing-then-active. Webhook-only and
 * mixed both witness every intermediate event and so must produce identical history.
 */
@Testcontainers
class BillingLedgerConvergenceIntegrationTests extends AbstractBillingLedgerIntegrationTest {

    private static final StripeBillingListApiStub STRIPE_LIST_STUB = new StripeBillingListApiStub();

    @DynamicPropertySource
    static void stripeListApi(DynamicPropertyRegistry registry) {
        registry.add("mrrorigin.stripe.connect.api-base-uri", STRIPE_LIST_STUB::apiBaseUri);
    }

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final long T1 = T0.plusSeconds(60).getEpochSecond();
    private static final long T3 = T0.plusSeconds(180).getEpochSecond();
    private static final long T5 = T0.plusSeconds(300).getEpochSecond();
    private static final long T5_END = T5 + 2_592_000L;
    private static final long T6 = T0.plusSeconds(360).getEpochSecond();
    private static final long T7 = T0.plusSeconds(420).getEpochSecond();

    @Test
    void backfillOnlyWebhookOnlyAndMixedProcessingConverge() {
        UUID backfillWorkspace = createWorkspace();
        UUID webhookWorkspace = createWorkspace();
        UUID mixedWorkspace = createWorkspace();

        UUID backfillConnection = insertActiveConnection(backfillWorkspace, "acct_backfill_conv", StripeConnectionMode.TEST);
        UUID webhookConnection = insertActiveConnection(webhookWorkspace, "acct_webhook_conv", StripeConnectionMode.TEST);
        UUID mixedConnection = insertActiveConnection(mixedWorkspace, "acct_mixed_conv", StripeConnectionMode.TEST);

        seedFinalStateOnStub();

        // ---- backfill-only ---------------------------------------------------------------------
        runBackfillToCompletion(backfillConnection);

        // ---- webhook-only ----------------------------------------------------------------------
        for (WebhookStep step : fullEventSequence()) {
            insertPendingWebhookEvent(
                    webhookConnection, webhookWorkspace, StripeConnectionMode.TEST, "wh_" + step.id(), step.type(), step.at(), step.object());
        }
        assertThat(drainWebhookQueue()).isEqualTo(fullEventSequence().size());

        // ---- mixed: a few early webhook events, then a full backfill of the resulting live state -
        // (stripe_webhook_events' event-id uniqueness is global, not per workspace, so each run
        // needs its own event IDs even though the underlying object payloads are identical.)
        for (WebhookStep step : fullEventSequence().subList(0, 3)) {
            insertPendingWebhookEvent(
                    mixedConnection, mixedWorkspace, StripeConnectionMode.TEST, "mx_" + step.id(), step.type(), step.at(), step.object());
        }
        assertThat(drainWebhookQueue()).isEqualTo(3);
        runBackfillToCompletion(mixedConnection);

        // ---- snapshots converge across all three workspaces -------------------------------------
        assertSameSnapshot(backfillWorkspace, webhookWorkspace, mixedWorkspace);

        // ---- subscription status history: webhook-only and mixed match; backfill-only is smaller -
        List<String> webhookHistory = subscriptionStatusHistory(webhookWorkspace, "sub_conv");
        List<String> mixedHistory = subscriptionStatusHistory(mixedWorkspace, "sub_conv");
        assertThat(webhookHistory).containsExactly("trialing", "active");
        assertThat(mixedHistory).containsExactly("trialing", "active");
        assertThat(subscriptionStatusHistory(backfillWorkspace, "sub_conv")).containsExactly("active");
    }

    private void assertSameSnapshot(UUID... workspaces) {
        for (UUID workspaceId : workspaces) {
            assertThat(customerSnapshot(workspaceId, "cus_conv").orElseThrow())
                    .containsEntry("currency", "usd")
                    .containsEntry("deleted", false);
            assertThat(priceSnapshot(workspaceId, "price_conv")).isPresent();
            assertThat(subscriptionSnapshot(workspaceId, "sub_conv")).isPresent();
            assertThat(invoiceSnapshot(workspaceId, "in_conv")).isPresent();
            assertThat(paymentSnapshot(workspaceId, "ch_conv")).isPresent();
            assertThat(refundSnapshot(workspaceId, "re_conv")).isPresent();
            assertThat(discountSnapshot(workspaceId, "di_conv")).isPresent();
        }
        Map<String, Object> priceReference = priceSnapshot(workspaces[0], "price_conv").orElseThrow();
        Map<String, Object> subscriptionReference = subscriptionSnapshot(workspaces[0], "sub_conv").orElseThrow();
        List<Map<String, Object>> itemsReference = subscriptionItemSnapshots(workspaces[0], "sub_conv");
        Map<String, Object> invoiceReference = invoiceSnapshot(workspaces[0], "in_conv").orElseThrow();
        Map<String, Object> paymentReference = paymentSnapshot(workspaces[0], "ch_conv").orElseThrow();
        Map<String, Object> refundReference = refundSnapshot(workspaces[0], "re_conv").orElseThrow();
        Map<String, Object> discountReference = discountSnapshot(workspaces[0], "di_conv").orElseThrow();

        for (UUID workspaceId : workspaces) {
            assertThat(priceSnapshot(workspaceId, "price_conv")).contains(priceReference);
            assertThat(subscriptionSnapshot(workspaceId, "sub_conv")).contains(subscriptionReference);
            assertThat(subscriptionItemSnapshots(workspaceId, "sub_conv")).isEqualTo(itemsReference);
            assertThat(invoiceSnapshot(workspaceId, "in_conv")).contains(invoiceReference);
            assertThat(paymentSnapshot(workspaceId, "ch_conv")).contains(paymentReference);
            assertThat(refundSnapshot(workspaceId, "re_conv")).contains(refundReference);
            assertThat(discountSnapshot(workspaceId, "di_conv")).contains(discountReference);
        }
    }

    private void seedFinalStateOnStub() {
        String finalSubscription = BillingFixtures.subscription(
                "sub_conv",
                "cus_conv",
                "active",
                "usd",
                T5,
                T5_END,
                false,
                T1,
                T3,
                BillingFixtures.subscriptionItem("si_conv", "price_conv", 1),
                BillingFixtures.discount("di_conv", null, "sub_conv", "coupon_15off", 15L, null, null, T5, null));
        STRIPE_LIST_STUB.seed("/v1/customers", List.of(BillingFixtures.customer("cus_conv", "usd", T0.getEpochSecond(), false, null)));
        STRIPE_LIST_STUB.seed(
                "/v1/prices", List.of(BillingFixtures.price("price_conv", "prod_conv", "usd", 2000L, "recurring", "month", 1, true)));
        STRIPE_LIST_STUB.seed("/v1/subscriptions", List.of(finalSubscription));
        STRIPE_LIST_STUB.seed(
                "/v1/invoices",
                List.of(BillingFixtures.invoice("in_conv", "cus_conv", "sub_conv", "paid", "usd", 1700, 1700, 0, T5, T5_END, T5)));
        STRIPE_LIST_STUB.seed(
                "/v1/charges",
                List.of(BillingFixtures.charge("ch_conv", "cus_conv", "in_conv", 1700, "usd", "succeeded", true, false, 500, T6)));
        STRIPE_LIST_STUB.seed(
                "/v1/refunds",
                List.of(BillingFixtures.refund("re_conv", "ch_conv", 500, "usd", "succeeded", "requested_by_customer", T7)));
    }

    private List<WebhookStep> fullEventSequence() {
        String trialingSubscription = BillingFixtures.subscription(
                "sub_conv",
                "cus_conv",
                "trialing",
                "usd",
                T1,
                T1 + 2_592_000L,
                false,
                T1,
                T3,
                BillingFixtures.subscriptionItem("si_conv", "price_conv", 1),
                null);
        String activeSubscriptionWithDiscount = BillingFixtures.subscription(
                "sub_conv",
                "cus_conv",
                "active",
                "usd",
                T5,
                T5_END,
                false,
                T1,
                T3,
                BillingFixtures.subscriptionItem("si_conv", "price_conv", 1),
                BillingFixtures.discount("di_conv", null, "sub_conv", "coupon_15off", 15L, null, null, T5, null));

        return List.of(
                new WebhookStep(
                        "evt_customer_created",
                        "customer.created",
                        T0,
                        BillingFixtures.customer("cus_conv", "usd", T0.getEpochSecond(), false, null)),
                new WebhookStep(
                        "evt_price_created",
                        "price.created",
                        T0,
                        BillingFixtures.price("price_conv", "prod_conv", "usd", 2000L, "recurring", "month", 1, true)),
                new WebhookStep("evt_sub_created", "customer.subscription.created", T0.plusSeconds(60), trialingSubscription),
                new WebhookStep(
                        "evt_sub_activated", "customer.subscription.updated", T0.plusSeconds(300), activeSubscriptionWithDiscount),
                new WebhookStep(
                        "evt_invoice_created",
                        "invoice.created",
                        T0.plusSeconds(300),
                        BillingFixtures.invoice("in_conv", "cus_conv", "sub_conv", "open", "usd", 1700, 0, 1700, T5, T5_END, T5)),
                new WebhookStep(
                        "evt_invoice_paid",
                        "invoice.paid",
                        T0.plusSeconds(360),
                        BillingFixtures.invoice("in_conv", "cus_conv", "sub_conv", "paid", "usd", 1700, 1700, 0, T5, T5_END, T5)),
                new WebhookStep(
                        "evt_charge_succeeded",
                        "charge.succeeded",
                        T0.plusSeconds(360),
                        BillingFixtures.charge("ch_conv", "cus_conv", "in_conv", 1700, "usd", "succeeded", true, false, 0, T6)),
                new WebhookStep(
                        "evt_charge_refunded",
                        "charge.refunded",
                        T0.plusSeconds(420),
                        BillingFixtures.charge("ch_conv", "cus_conv", "in_conv", 1700, "usd", "succeeded", true, false, 500, T6)),
                new WebhookStep(
                        "evt_refund_created",
                        "refund.created",
                        T0.plusSeconds(420),
                        BillingFixtures.refund("re_conv", "ch_conv", 500, "usd", "succeeded", "requested_by_customer", T7)));
    }

    private record WebhookStep(String id, String type, Instant at, String object) {}
}
