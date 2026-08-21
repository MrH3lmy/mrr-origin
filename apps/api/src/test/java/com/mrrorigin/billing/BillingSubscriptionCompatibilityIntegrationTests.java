package com.mrrorigin.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * #12's compatibility with Stripe's current subscription contract
 * (https://docs.stripe.com/api/subscriptions/object): the plural, multi-entry {@code discounts}
 * array (not the singular {@code discount} field), subscription-item-level discounts, and embedded
 * {@code items} pages that are themselves paginated ({@code has_more=true}), which must never be
 * silently truncated when replacing a subscription's stored item set.
 */
@Testcontainers
class BillingSubscriptionCompatibilityIntegrationTests extends AbstractBillingLedgerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    private static final StripeBillingListApiStub STRIPE_LIST_STUB = new StripeBillingListApiStub();

    @DynamicPropertySource
    static void stripeListApi(DynamicPropertyRegistry registry) {
        registry.add("mrrorigin.stripe.connect.api-base-uri", STRIPE_LIST_STUB::apiBaseUri);
    }

    private static final long T = Instant.parse("2026-03-01T00:00:00Z").getEpochSecond();

    @Test
    void subscriptionWithMultipleDiscountsNormalizesEveryDiscount() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_multi_discount", StripeConnectionMode.TEST);
        seedPrice(workspaceId, "price_x");

        String discountOne = BillingFixtures.discount("di_multi_1", null, "sub_multi_disc", "coupon_1", 10L, null, null, T, null);
        String discountTwo = BillingFixtures.discount("di_multi_2", null, "sub_multi_disc", "coupon_2", null, 500L, "usd", T, null);
        String subscription = BillingFixtures.subscription(
                "sub_multi_disc",
                "cus_multi_disc",
                "active",
                "usd",
                T,
                T + 2_592_000L,
                false,
                null,
                null,
                BillingFixtures.subscriptionItem("si_multi_disc", "price_x", 1),
                String.join(",", discountOne, discountTwo));

        insertPendingWebhookEvent(
                connectionId, workspaceId, StripeConnectionMode.TEST, "evt_multi_disc", "customer.subscription.created",
                Instant.ofEpochSecond(T), subscription);
        assertThat(drainWebhookQueue()).isEqualTo(1);

        assertThat(discountSnapshot(workspaceId, "di_multi_1")).isPresent();
        assertThat(discountSnapshot(workspaceId, "di_multi_2")).isPresent();
        assertThat(jdbc().sql(
                        "SELECT COUNT(*) FROM billing_discounts WHERE workspace_id = :w AND stripe_subscription_id = :sub")
                        .param("w", workspaceId)
                        .param("sub", "sub_multi_disc")
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void subscriptionItemLevelDiscountIsAttributedToTheItemNotTheSubscription() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_item_discount", StripeConnectionMode.TEST);
        seedPrice(workspaceId, "price_x");

        String itemDiscount = BillingFixtures.discount("di_item_level", null, null, "coupon_item", 20L, null, null, T, null);
        String itemWithDiscount = BillingFixtures.subscriptionItem("si_item_disc", "price_x", 1, itemDiscount);
        String subscription = BillingFixtures.subscription(
                "sub_item_disc", "cus_item_disc", "active", "usd", T, T + 2_592_000L, false, null, null, itemWithDiscount, null);

        insertPendingWebhookEvent(
                connectionId, workspaceId, StripeConnectionMode.TEST, "evt_item_disc", "customer.subscription.created",
                Instant.ofEpochSecond(T), subscription);
        assertThat(drainWebhookQueue()).isEqualTo(1);

        Map<String, Object> row = discountSnapshot(workspaceId, "di_item_level").orElseThrow();
        assertThat(row.get("stripe_subscription_id")).isNull();
        assertThat(row.get("stripe_customer_id")).isNull();
        assertThat(jdbc().sql(
                        "SELECT stripe_subscription_item_id FROM billing_discounts WHERE workspace_id = :w AND stripe_discount_id = 'di_item_level'")
                        .param("w", workspaceId)
                        .query(String.class)
                        .single())
                .isEqualTo("si_item_disc");
    }

    @Test
    void paginatedEmbeddedSubscriptionItemsAreFullyResolvedDuringBackfill() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_paged_items", StripeConnectionMode.TEST);
        seedPrice(workspaceId, "price_x");

        String embeddedItem = BillingFixtures.subscriptionItem("si_page_1", "price_x", 1);
        String subscription = BillingFixtures.subscription(
                "sub_paged_items", "cus_paged_items", "active", "usd", T, T + 2_592_000L, false, null, null, embeddedItem, true, null);

        STRIPE_LIST_STUB.seed("/v1/customers", List.of());
        STRIPE_LIST_STUB.seed("/v1/prices", List.of());
        STRIPE_LIST_STUB.seed("/v1/subscriptions", List.of(subscription));
        STRIPE_LIST_STUB.seed(
                "/v1/subscription_items",
                List.of(
                        BillingFixtures.subscriptionItem("si_page_2", "price_x", 2),
                        BillingFixtures.subscriptionItem("si_page_3", "price_x", 3)));
        STRIPE_LIST_STUB.seed("/v1/invoices", List.of());
        STRIPE_LIST_STUB.seed("/v1/charges", List.of());
        STRIPE_LIST_STUB.seed("/v1/refunds", List.of());

        runBackfillToCompletion(connectionId);

        List<Map<String, Object>> items = subscriptionItemSnapshots(workspaceId, "sub_paged_items");
        assertThat(items).extracting(row -> row.get("stripe_price_id")).containsOnly("price_x");
        assertThat(jdbc().sql("""
                        SELECT stripe_subscription_item_id FROM billing_subscription_items bsi
                        JOIN billing_subscriptions bs ON bs.id = bsi.subscription_id
                        WHERE bs.workspace_id = :w AND bs.stripe_subscription_id = 'sub_paged_items'
                        ORDER BY stripe_subscription_item_id
                        """)
                        .param("w", workspaceId)
                        .query(String.class)
                        .list())
                .containsExactly("si_page_1", "si_page_2", "si_page_3");
    }

    @Test
    void paginatedEmbeddedSubscriptionItemsAreFullyResolvedDuringWebhookNormalization() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_paged_items_wh", StripeConnectionMode.TEST);
        seedPrice(workspaceId, "price_x");

        String embeddedItem = BillingFixtures.subscriptionItem("si_wh_page_1", "price_x", 1);
        String subscription = BillingFixtures.subscription(
                "sub_wh_paged_items", "cus_wh_paged_items", "active", "usd", T, T + 2_592_000L, false, null, null, embeddedItem, true, null);

        STRIPE_LIST_STUB.seed("/v1/subscription_items", List.of(BillingFixtures.subscriptionItem("si_wh_page_2", "price_x", 1)));

        insertPendingWebhookEvent(
                connectionId, workspaceId, StripeConnectionMode.TEST, "evt_wh_paged_items", "customer.subscription.created",
                Instant.ofEpochSecond(T), subscription);
        assertThat(drainWebhookQueue()).isEqualTo(1);

        assertThat(jdbc().sql("""
                        SELECT stripe_subscription_item_id FROM billing_subscription_items bsi
                        JOIN billing_subscriptions bs ON bs.id = bsi.subscription_id
                        WHERE bs.workspace_id = :w AND bs.stripe_subscription_id = 'sub_wh_paged_items'
                        ORDER BY stripe_subscription_item_id
                        """)
                        .param("w", workspaceId)
                        .query(String.class)
                        .list())
                .containsExactly("si_wh_page_1", "si_wh_page_2");
    }

    @Test
    void backfillRequestsDiscountExpansionForSubscriptionsAndSupplementalItems() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_expand_params", StripeConnectionMode.TEST);
        seedPrice(workspaceId, "price_x");

        String embeddedItem = BillingFixtures.subscriptionItem("si_expand_1", "price_x", 1);
        String subscription = BillingFixtures.subscription(
                "sub_expand_params", "cus_expand_params", "active", "usd", T, T + 2_592_000L, false, null, null,
                embeddedItem, true, null);

        STRIPE_LIST_STUB.seed("/v1/customers", List.of());
        STRIPE_LIST_STUB.seed("/v1/prices", List.of());
        STRIPE_LIST_STUB.seed("/v1/subscriptions", List.of(subscription));
        STRIPE_LIST_STUB.seed("/v1/subscription_items", List.of(BillingFixtures.subscriptionItem("si_expand_2", "price_x", 1)));
        STRIPE_LIST_STUB.seed("/v1/invoices", List.of());
        STRIPE_LIST_STUB.seed("/v1/charges", List.of());
        STRIPE_LIST_STUB.seed("/v1/refunds", List.of());

        runBackfillToCompletion(connectionId);

        StripeBillingListApiStub.RecordedRequest subscriptionsRequest = STRIPE_LIST_STUB.requests.stream()
                .filter(request -> request.path().equals("/v1/subscriptions"))
                .findFirst()
                .orElseThrow();
        assertThat(subscriptionsRequest.query()).contains("expand[]=data.discounts");
        assertThat(subscriptionsRequest.query()).contains("expand[]=data.items.data.discounts");

        StripeBillingListApiStub.RecordedRequest itemsRequest = STRIPE_LIST_STUB.requests.stream()
                .filter(request -> request.path().equals("/v1/subscription_items"))
                .findFirst()
                .orElseThrow();
        assertThat(itemsRequest.query()).contains("expand[]=data.discounts");
    }

    @Test
    void webhookPayloadWithUnexpandedDiscountFallsBackToALiveFullyExpandedFetch() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_unexpanded_discount", StripeConnectionMode.TEST);
        seedPrice(workspaceId, "price_x");

        // The webhook's own payload carries only a bare discount ID string -- not a full object --
        // simulating a push-delivered event Stripe did not (and cannot, via expand[]) expand.
        String unexpandedSubscription = BillingFixtures.subscription(
                "sub_unexpanded", "cus_unexpanded", "active", "usd", T, T + 2_592_000L, false, null, null,
                BillingFixtures.subscriptionItem("si_unexpanded", "price_x", 1), "\"di_pending_expansion\"");

        String fullyExpandedDiscount =
                BillingFixtures.discount("di_pending_expansion", null, "sub_unexpanded", "coupon_live_fetch", 42L, null, null, T, null);
        // The live object has already moved on. It may enrich the bare discount ID, but none of
        // these current status/period/item values may replace the immutable webhook snapshot.
        String fullyExpandedSubscription = BillingFixtures.subscription(
                "sub_unexpanded", "cus_unexpanded", "canceled", "usd", T + 300, T + 600, false, T + 400, T + 400,
                BillingFixtures.subscriptionItem("si_unexpanded", "price_x", 9), fullyExpandedDiscount);
        STRIPE_LIST_STUB.seedSingleSubscription("sub_unexpanded", fullyExpandedSubscription);

        insertPendingWebhookEvent(
                connectionId, workspaceId, StripeConnectionMode.TEST, "evt_unexpanded_discount", "customer.subscription.created",
                Instant.ofEpochSecond(T), unexpandedSubscription);
        assertThat(drainWebhookQueue()).isEqualTo(1);

        // The discount only exists because normalization fell back to the live GET -- the original
        // webhook payload had no expandable data to normalize it from at all.
        Map<String, Object> discount = discountSnapshot(workspaceId, "di_pending_expansion").orElseThrow();
        assertThat(discount).containsEntry("stripe_coupon_id", "coupon_live_fetch");
        assertThat(((Number) discount.get("percent_off")).intValue()).isEqualTo(42);
        assertThat(subscriptionSnapshot(workspaceId, "sub_unexpanded").orElseThrow()).containsEntry("status", "active");
        assertThat(subscriptionItemSnapshots(workspaceId, "sub_unexpanded").getFirst()).containsEntry("quantity", 1);

        StripeBillingListApiStub.RecordedRequest fallbackRequest = STRIPE_LIST_STUB.requests.stream()
                .filter(request -> request.path().equals("/v1/subscriptions/sub_unexpanded"))
                .findFirst()
                .orElseThrow();
        assertThat(fallbackRequest.query()).contains("expand[]=discounts");
        assertThat(fallbackRequest.query()).contains("expand[]=items.data.discounts");
    }

    @Test
    void unresolvedBareDiscountFailsVisiblyInsteadOfApplyingPartialSubscriptionState() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_missing_discount", StripeConnectionMode.TEST);
        String webhookSubscription = BillingFixtures.subscription(
                "sub_missing_discount", "cus_missing_discount", "active", "usd", T, T + 2_592_000L, false, null, null,
                BillingFixtures.subscriptionItem("si_missing_discount", "price_x", 1), "\"di_no_longer_available\"");
        String liveSubscription = BillingFixtures.subscription(
                "sub_missing_discount", "cus_missing_discount", "canceled", "usd", T + 300, T + 600, false, null, null,
                BillingFixtures.subscriptionItem("si_missing_discount", "price_x", 9), null);
        STRIPE_LIST_STUB.seedSingleSubscription("sub_missing_discount", liveSubscription);

        insertPendingWebhookEvent(
                connectionId, workspaceId, StripeConnectionMode.TEST, "evt_missing_discount", "customer.subscription.updated",
                Instant.ofEpochSecond(T), webhookSubscription);

        StripeWebhookNormalizationService.NormalizationRunOutcome outcome = normalizationService.processBatch(1);
        assertThat(outcome.failed()).isEqualTo(1);
        assertThat(subscriptionSnapshot(workspaceId, "sub_missing_discount")).isEmpty();
        assertThat(jdbc().sql("SELECT processing_state FROM stripe_webhook_events WHERE stripe_event_id = 'evt_missing_discount'")
                        .query(String.class)
                        .single())
                .isEqualTo("FAILED");
    }
}
