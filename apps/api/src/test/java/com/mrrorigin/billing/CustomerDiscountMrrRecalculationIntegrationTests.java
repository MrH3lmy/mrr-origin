package com.mrrorigin.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
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
import com.mrrorigin.revenue.RevenueModels.Snapshot;

/**
 * #86's production-wiring proof: real webhook normalization of legacy top-level {@code
 * customer.discount.created/updated/deleted} events -- not direct calls to any revenue-package type
 * -- must produce persisted {@code customer_mrr_movements}/{@code customer_mrr_snapshots}, or must
 * fail visibly rather than leaving a plausible but stale MRR value. See ADR-0011 for the design
 * decision this exercises.
 */
@Testcontainers
class CustomerDiscountMrrRecalculationIntegrationTests extends AbstractBillingLedgerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    @Autowired
    private RevenueCalculationService revenue;

    private static final Instant T0 = Instant.parse("2026-06-01T00:00:00Z");
    private static final long FAR_FUTURE_END = Instant.parse("2030-01-01T00:00:00Z").getEpochSecond();

    // ---- 1. Percentage discount create -> contraction on existing MRR --------------------------------

    @Test
    void percentageCustomerDiscountCreateAppliesToMrr() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_pct_create", StripeConnectionMode.TEST);
        long start = T0.getEpochSecond();

        webhook(connectionId, workspaceId, "evt_pct_customer", "customer.created", T0,
                BillingFixtures.customer("cus_pct", "usd", start, false, null));
        webhook(connectionId, workspaceId, "evt_pct_price", "price.created", T0,
                BillingFixtures.price("price_pct", "prod_pct", "usd", 2000L, "recurring", "month", 1, true));
        webhook(connectionId, workspaceId, "evt_pct_sub", "customer.subscription.created", T0,
                BillingFixtures.subscription(
                        "sub_pct", "cus_pct", "active", "usd", start, start + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_pct", "price_pct", 1), null));
        assertThat(drainWebhookQueue()).isEqualTo(3);
        assertThat(revenue.movements(workspaceId, "cus_pct")).extracting(Movement::type).containsExactly("NEW");

        webhook(connectionId, workspaceId, "evt_pct_discount", "customer.discount.created", T0.plusSeconds(60),
                BillingFixtures.discount(
                        "di_pct", "cus_pct", null, "coupon_pct", 20L, null, null, start + 60, FAR_FUTURE_END));
        assertThat(drainWebhookQueue()).isEqualTo(1);

        assertThat(discountSnapshot(workspaceId, "di_pct")).isPresent().get().satisfies(row -> {
            assertThat(row.get("stripe_customer_id")).isEqualTo("cus_pct");
            assertThat(row.get("stripe_subscription_id")).isNull();
            assertThat(row.get("deleted")).isEqualTo(false);
        });

        List<Movement> movements = revenue.movements(workspaceId, "cus_pct");
        assertThat(movements).extracting(Movement::type).containsExactly("NEW", "CONTRACTION");
        assertThat(movements).extracting(Movement::amountMinor).containsExactly(2000L, 400L);

        List<Snapshot> snapshots = revenue.snapshots(workspaceId, "cus_pct");
        Snapshot last = snapshots.get(snapshots.size() - 1);
        assertThat(last.supported()).isTrue();
        assertThat(last.amountMinor()).isEqualTo(1600L);
    }

    // ---- 2. Percentage discount update -> recalculates without duplicating movements -----------------

    @Test
    void percentageCustomerDiscountUpdateRecalculatesWithoutDuplicateMovements() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_pct_update", StripeConnectionMode.TEST);
        long start = T0.getEpochSecond();

        webhook(connectionId, workspaceId, "evt_upd_customer", "customer.created", T0,
                BillingFixtures.customer("cus_upd", "usd", start, false, null));
        webhook(connectionId, workspaceId, "evt_upd_price", "price.created", T0,
                BillingFixtures.price("price_upd", "prod_upd", "usd", 2000L, "recurring", "month", 1, true));
        webhook(connectionId, workspaceId, "evt_upd_sub", "customer.subscription.created", T0,
                BillingFixtures.subscription(
                        "sub_upd", "cus_upd", "active", "usd", start, start + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_upd", "price_upd", 1), null));
        webhook(connectionId, workspaceId, "evt_upd_created", "customer.discount.created", T0.plusSeconds(60),
                BillingFixtures.discount(
                        "di_upd", "cus_upd", null, "coupon_upd", 20L, null, null, start + 60, FAR_FUTURE_END));
        assertThat(drainWebhookQueue()).isEqualTo(4);

        // Same discount id, different percentage and a later provider-declared start -- a genuine
        // update, not a replay of the same content.
        webhook(connectionId, workspaceId, "evt_upd_updated", "customer.discount.updated", T0.plusSeconds(120),
                BillingFixtures.discount(
                        "di_upd", "cus_upd", null, "coupon_upd", 40L, null, null, start + 120, FAR_FUTURE_END));
        assertThat(drainWebhookQueue()).isEqualTo(1);

        assertThat(discountSnapshot(workspaceId, "di_upd")).isPresent().get().satisfies(row -> {
            assertThat(((Number) row.get("percent_off")).intValue()).isEqualTo(40);
            assertThat(row.get("deleted")).isEqualTo(false);
        });

        List<Movement> movements = revenue.movements(workspaceId, "cus_upd");
        assertThat(movements).extracting(Movement::type).containsExactly("NEW", "CONTRACTION", "CONTRACTION");
        assertThat(movements).extracting(Movement::amountMinor).containsExactly(2000L, 400L, 400L);

        List<Snapshot> snapshots = revenue.snapshots(workspaceId, "cus_upd");
        Snapshot last = snapshots.get(snapshots.size() - 1);
        assertThat(last.supported()).isTrue();
        assertThat(last.amountMinor()).isEqualTo(1200L);
    }

    // ---- 3. Discount delete -> restores prior MRR -------------------------------------------------

    @Test
    void discountDeleteRestoresMrr() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_del", StripeConnectionMode.TEST);
        long start = T0.getEpochSecond();

        webhook(connectionId, workspaceId, "evt_del_customer", "customer.created", T0,
                BillingFixtures.customer("cus_del", "usd", start, false, null));
        webhook(connectionId, workspaceId, "evt_del_price", "price.created", T0,
                BillingFixtures.price("price_del", "prod_del", "usd", 2000L, "recurring", "month", 1, true));
        webhook(connectionId, workspaceId, "evt_del_sub", "customer.subscription.created", T0,
                BillingFixtures.subscription(
                        "sub_del", "cus_del", "active", "usd", start, start + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_del", "price_del", 1), null));
        webhook(connectionId, workspaceId, "evt_del_created", "customer.discount.created", T0.plusSeconds(60),
                BillingFixtures.discount(
                        "di_del", "cus_del", null, "coupon_del", 25L, null, null, start + 60, FAR_FUTURE_END));
        assertThat(drainWebhookQueue()).isEqualTo(4);
        assertThat(revenue.snapshots(workspaceId, "cus_del").stream().reduce((a, b) -> b).orElseThrow().amountMinor())
                .isEqualTo(1500L);

        // The delete payload carries its own provider-declared end -- used as the effective restoration
        // instant rather than any fallback.
        long restoredAt = start + 180;
        webhook(connectionId, workspaceId, "evt_del_deleted", "customer.discount.deleted", T0.plusSeconds(240),
                BillingFixtures.discount(
                        "di_del", "cus_del", null, "coupon_del", 25L, null, null, start + 60, restoredAt));
        assertThat(drainWebhookQueue()).isEqualTo(1);

        assertThat(discountSnapshot(workspaceId, "di_del")).isPresent().get().satisfies(row -> assertThat(row.get("deleted")).isEqualTo(true));

        List<Movement> movements = revenue.movements(workspaceId, "cus_del");
        assertThat(movements).extracting(Movement::type).containsExactly("NEW", "CONTRACTION", "EXPANSION");
        assertThat(movements).extracting(Movement::amountMinor).containsExactly(2000L, 500L, 500L);

        List<Snapshot> snapshots = revenue.snapshots(workspaceId, "cus_del");
        Snapshot last = snapshots.get(snapshots.size() - 1);
        assertThat(last.supported()).isTrue();
        assertThat(last.amountMinor()).isEqualTo(2000L);
    }

    // ---- 3b. Discount delete with no provider-declared end falls back to the event's own second -----

    @Test
    void discountDeleteWithoutEndFallsBackToEventProviderSecond() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_del_fallback", StripeConnectionMode.TEST);
        long start = T0.getEpochSecond();

        webhook(connectionId, workspaceId, "evt_delf_customer", "customer.created", T0,
                BillingFixtures.customer("cus_delf", "usd", start, false, null));
        webhook(connectionId, workspaceId, "evt_delf_price", "price.created", T0,
                BillingFixtures.price("price_delf", "prod_delf", "usd", 1000L, "recurring", "month", 1, true));
        webhook(connectionId, workspaceId, "evt_delf_sub", "customer.subscription.created", T0,
                BillingFixtures.subscription(
                        "sub_delf", "cus_delf", "active", "usd", start, start + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_delf", "price_delf", 1), null));
        // "forever" duration coupon: no end date carried at all.
        webhook(connectionId, workspaceId, "evt_delf_created", "customer.discount.created", T0.plusSeconds(60),
                BillingFixtures.discount(
                        "di_delf", "cus_delf", null, "coupon_delf", 50L, null, null, start + 60, FAR_FUTURE_END));
        assertThat(drainWebhookQueue()).isEqualTo(4);

        Instant deleteReceivedAt = T0.plusSeconds(300);
        webhook(connectionId, workspaceId, "evt_delf_deleted", "customer.discount.deleted", deleteReceivedAt,
                BillingFixtures.discount("di_delf", "cus_delf", null, "coupon_delf", 50L, null, null, start + 60, null));
        assertThat(drainWebhookQueue()).isEqualTo(1);

        List<Snapshot> snapshots = revenue.snapshots(workspaceId, "cus_delf");
        Snapshot last = snapshots.get(snapshots.size() - 1);
        assertThat(last.supported()).isTrue();
        assertThat(last.amountMinor()).isEqualTo(1000L);
        assertThat(last.effectiveAt().toEpochSecond()).isEqualTo(deleteReceivedAt.getEpochSecond());
    }

    // ---- 4. Duplicate delivery / replay produce no duplicate movements or snapshots -----------------

    @Test
    void duplicateAndReplayedCustomerDiscountEventsProduceNoDuplicateMovementsOrSnapshots() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_dup_disc", StripeConnectionMode.TEST);
        long start = T0.getEpochSecond();

        webhook(connectionId, workspaceId, "evt_dupd_customer", "customer.created", T0,
                BillingFixtures.customer("cus_dupd", "usd", start, false, null));
        webhook(connectionId, workspaceId, "evt_dupd_price", "price.created", T0,
                BillingFixtures.price("price_dupd", "prod_dupd", "usd", 1000L, "recurring", "month", 1, true));
        webhook(connectionId, workspaceId, "evt_dupd_sub", "customer.subscription.created", T0,
                BillingFixtures.subscription(
                        "sub_dupd", "cus_dupd", "active", "usd", start, start + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_dupd", "price_dupd", 1), null));
        webhook(connectionId, workspaceId, "evt_dupd_discount", "customer.discount.created", T0.plusSeconds(60),
                BillingFixtures.discount(
                        "di_dupd", "cus_dupd", null, "coupon_dupd", 10L, null, null, start + 60, FAR_FUTURE_END));
        assertThat(drainWebhookQueue()).isEqualTo(4);

        List<Movement> firstPass = revenue.movements(workspaceId, "cus_dupd");
        List<Snapshot> firstSnapshots = revenue.snapshots(workspaceId, "cus_dupd");

        // Reprocess the exact same already-PROCESSED discount row -- a lease-expiry reclaim or an
        // explicit replay after a transient error, exactly what #86's acceptance criteria requires
        // stays idempotent.
        resetEventToPending(workspaceId, "evt_dupd_discount");
        assertThat(drainWebhookQueue()).isEqualTo(1);

        assertThat(revenue.movements(workspaceId, "cus_dupd")).isEqualTo(firstPass);
        assertThat(revenue.snapshots(workspaceId, "cus_dupd")).isEqualTo(firstSnapshots);
    }

    // ---- 5. Out-of-order delivery never regresses newer MRR state -----------------------------------

    @Test
    void outOfOrderDiscountEventDoesNotRegressNewerMrrState() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_ooo_disc", StripeConnectionMode.TEST);
        long start = T0.getEpochSecond();

        webhook(connectionId, workspaceId, "evt_ood_customer", "customer.created", T0,
                BillingFixtures.customer("cus_ood", "usd", start, false, null));
        webhook(connectionId, workspaceId, "evt_ood_price", "price.created", T0,
                BillingFixtures.price("price_ood", "prod_ood", "usd", 2000L, "recurring", "month", 1, true));
        webhook(connectionId, workspaceId, "evt_ood_sub", "customer.subscription.created", T0,
                BillingFixtures.subscription(
                        "sub_ood", "cus_ood", "active", "usd", start, start + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_ood", "price_ood", 1), null));
        assertThat(drainWebhookQueue()).isEqualTo(3);

        // The chronologically newer discount state (30% off, provider second T0+180) is delivered and
        // processed first...
        webhook(connectionId, workspaceId, "evt_ood_newer", "customer.discount.created", T0.plusSeconds(180),
                BillingFixtures.discount(
                        "di_ood", "cus_ood", null, "coupon_ood", 30L, null, null, start + 180, FAR_FUTURE_END));
        assertThat(drainWebhookQueue()).isEqualTo(1);

        // ...then a chronologically older state for the SAME discount (10% off, provider second
        // T0+120) is delivered late, after the newer one already applied.
        webhook(connectionId, workspaceId, "evt_ood_older", "customer.discount.updated", T0.plusSeconds(120),
                BillingFixtures.discount(
                        "di_ood", "cus_ood", null, "coupon_ood", 10L, null, null, start + 120, FAR_FUTURE_END));
        assertThat(drainWebhookQueue()).isEqualTo(1);

        // The ledger's own version guard rejects the older discount write before recalculation is ever
        // invoked for it -- the stored discount stays at 30%, not regressed to 10%.
        assertThat(discountSnapshot(workspaceId, "di_ood")).isPresent().get().satisfies(row ->
                assertThat(((Number) row.get("percent_off")).intValue()).isEqualTo(30));

        List<Snapshot> snapshots = revenue.snapshots(workspaceId, "cus_ood");
        Snapshot last = snapshots.get(snapshots.size() - 1);
        assertThat(last.supported()).isTrue();
        assertThat(last.amountMinor()).isEqualTo(1400L);

        List<Movement> movements = revenue.movements(workspaceId, "cus_ood");
        assertThat(movements).extracting(Movement::type).containsExactly("NEW", "CONTRACTION");
        assertThat(movements).extracting(Movement::amountMinor).containsExactly(2000L, 600L);
    }

    // ---- 6. Multiple subscriptions for the same customer are all recalculated -----------------------

    @Test
    void percentageCustomerDiscountRecalculatesEveryActiveSubscription() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_multi_sub", StripeConnectionMode.TEST);
        long start = T0.getEpochSecond();

        webhook(connectionId, workspaceId, "evt_ms_customer", "customer.created", T0,
                BillingFixtures.customer("cus_ms", "usd", start, false, null));
        webhook(connectionId, workspaceId, "evt_ms_price1", "price.created", T0,
                BillingFixtures.price("price_ms1", "prod_ms1", "usd", 1000L, "recurring", "month", 1, true));
        webhook(connectionId, workspaceId, "evt_ms_price2", "price.created", T0,
                BillingFixtures.price("price_ms2", "prod_ms2", "usd", 3000L, "recurring", "month", 1, true));
        webhook(connectionId, workspaceId, "evt_ms_sub1", "customer.subscription.created", T0,
                BillingFixtures.subscription(
                        "sub_ms1", "cus_ms", "active", "usd", start, start + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_ms1", "price_ms1", 1), null));
        webhook(connectionId, workspaceId, "evt_ms_sub2", "customer.subscription.created", T0.plusSeconds(30),
                BillingFixtures.subscription(
                        "sub_ms2", "cus_ms", "active", "usd", start + 30, start + 30 + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_ms2", "price_ms2", 1), null));
        assertThat(drainWebhookQueue()).isEqualTo(5);
        assertThat(revenue.movements(workspaceId, "cus_ms")).extracting(Movement::type).containsExactly("NEW", "EXPANSION");
        assertThat(revenue.movements(workspaceId, "cus_ms")).extracting(Movement::amountMinor).containsExactly(1000L, 3000L);

        webhook(connectionId, workspaceId, "evt_ms_discount", "customer.discount.created", T0.plusSeconds(90),
                BillingFixtures.discount(
                        "di_ms", "cus_ms", null, "coupon_ms", 10L, null, null, start + 90, FAR_FUTURE_END));
        assertThat(drainWebhookQueue()).isEqualTo(1);

        // Both subscriptions' states land at the same effective_at (the discount's own start), so
        // ADR-0004's equal-timestamp grouping produces exactly one net customer-level movement rather
        // than one per subscription.
        List<Movement> movements = revenue.movements(workspaceId, "cus_ms");
        assertThat(movements).extracting(Movement::type).containsExactly("NEW", "EXPANSION", "CONTRACTION");
        assertThat(movements).extracting(Movement::amountMinor).containsExactly(1000L, 3000L, 400L);

        List<Snapshot> snapshots = revenue.snapshots(workspaceId, "cus_ms");
        Snapshot last = snapshots.get(snapshots.size() - 1);
        assertThat(last.supported()).isTrue();
        assertThat(last.amountMinor()).isEqualTo(3600L);
    }

    // ---- 7. Cross-tenant isolation -------------------------------------------------------------------

    @Test
    void crossTenantDiscountEventDoesNotAffectOtherWorkspace() {
        UUID workspaceA = createWorkspace();
        UUID workspaceB = createWorkspace();
        UUID connectionA = insertActiveConnection(workspaceA, "acct_tenant_disc_a", StripeConnectionMode.TEST);
        UUID connectionB = insertActiveConnection(workspaceB, "acct_tenant_disc_b", StripeConnectionMode.TEST);
        long start = T0.getEpochSecond();

        // Both workspaces reuse the exact same Stripe customer/subscription/price IDs -- Stripe IDs
        // are only unique within one Stripe account, never globally.
        webhook(connectionB, workspaceB, "evt_tc_customer_b", "customer.created", T0,
                BillingFixtures.customer("cus_shared", "usd", start, false, null));
        webhook(connectionB, workspaceB, "evt_tc_price_b", "price.created", T0,
                BillingFixtures.price("price_shared", "prod_shared_b", "usd", 2000L, "recurring", "month", 1, true));
        webhook(connectionB, workspaceB, "evt_tc_sub_b", "customer.subscription.created", T0,
                BillingFixtures.subscription(
                        "sub_shared", "cus_shared", "active", "usd", start, start + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_shared_b", "price_shared", 1), null));
        assertThat(drainWebhookQueue()).isEqualTo(3);

        webhook(connectionA, workspaceA, "evt_tc_customer_a", "customer.created", T0,
                BillingFixtures.customer("cus_shared", "usd", start, false, null));
        webhook(connectionA, workspaceA, "evt_tc_price_a", "price.created", T0,
                BillingFixtures.price("price_shared", "prod_shared_a", "usd", 2000L, "recurring", "month", 1, true));
        webhook(connectionA, workspaceA, "evt_tc_sub_a", "customer.subscription.created", T0,
                BillingFixtures.subscription(
                        "sub_shared", "cus_shared", "active", "usd", start, start + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_shared_a", "price_shared", 1), null));
        assertThat(drainWebhookQueue()).isEqualTo(3);

        List<Movement> workspaceBMovementsBefore = revenue.movements(workspaceB, "cus_shared");
        List<Snapshot> workspaceBSnapshotsBefore = revenue.snapshots(workspaceB, "cus_shared");

        // Only workspace A's customer gets a discount, reusing the exact same Stripe customer ID.
        webhook(connectionA, workspaceA, "evt_tc_discount", "customer.discount.created", T0.plusSeconds(60),
                BillingFixtures.discount(
                        "di_tc", "cus_shared", null, "coupon_tc", 50L, null, null, start + 60, FAR_FUTURE_END));
        assertThat(drainWebhookQueue()).isEqualTo(1);

        assertThat(revenue.snapshots(workspaceA, "cus_shared").stream().reduce((a, b) -> b).orElseThrow().amountMinor())
                .isEqualTo(1000L);
        assertThat(revenue.movements(workspaceB, "cus_shared")).isEqualTo(workspaceBMovementsBefore);
        assertThat(revenue.snapshots(workspaceB, "cus_shared")).isEqualTo(workspaceBSnapshotsBefore);
        assertThat(discountSnapshot(workspaceB, "di_tc")).isEmpty();
    }

    // ---- 8. Ambiguous fixed-amount discount across multiple subscriptions: explicit rejection,
    //         atomic rollback, no plausible-but-stale MRR ------------------------------------------

    @Test
    void ambiguousFixedAmountCustomerDiscountAcrossMultipleSubscriptionsFailsAtomically() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_ambiguous_fixed", StripeConnectionMode.TEST);
        long start = T0.getEpochSecond();

        webhook(connectionId, workspaceId, "evt_amb_customer", "customer.created", T0,
                BillingFixtures.customer("cus_amb", "usd", start, false, null));
        webhook(connectionId, workspaceId, "evt_amb_price1", "price.created", T0,
                BillingFixtures.price("price_amb1", "prod_amb1", "usd", 1000L, "recurring", "month", 1, true));
        // A second subscription in a DIFFERENT currency: proves the rejection is driven by "more than
        // one subscription", not any currency-mismatch shortcut, and that a fixed amount is never
        // silently applied across currencies either.
        webhook(connectionId, workspaceId, "evt_amb_price2", "price.created", T0,
                BillingFixtures.price("price_amb2", "prod_amb2", "eur", 2500L, "recurring", "month", 1, true));
        webhook(connectionId, workspaceId, "evt_amb_sub1", "customer.subscription.created", T0,
                BillingFixtures.subscription(
                        "sub_amb1", "cus_amb", "active", "usd", start, start + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_amb1", "price_amb1", 1), null));
        webhook(connectionId, workspaceId, "evt_amb_sub2", "customer.subscription.created", T0.plusSeconds(30),
                BillingFixtures.subscription(
                        "sub_amb2", "cus_amb", "active", "eur", start, start + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_amb2", "price_amb2", 1), null));
        assertThat(drainWebhookQueue()).isEqualTo(5);

        List<Movement> movementsBefore = revenue.movements(workspaceId, "cus_amb");
        List<Snapshot> snapshotsBefore = revenue.snapshots(workspaceId, "cus_amb");

        webhook(connectionId, workspaceId, "evt_amb_discount", "customer.discount.created", T0.plusSeconds(90),
                BillingFixtures.discount(
                        "di_amb", "cus_amb", null, "coupon_amb", null, 500L, "usd", start + 90, FAR_FUTURE_END));
        assertThat(drainWebhookQueue()).isEqualTo(1);

        assertThat(processingState(workspaceId, "evt_amb_discount")).isEqualTo("FAILED");
        assertThat(failureKind(workspaceId, "evt_amb_discount")).isEqualTo("UNSUPPORTED");

        // Nothing partially committed: no billing_discounts row, and MRR is exactly what it was before
        // the rejected event -- never a plausible-looking but wrong number for either currency.
        assertThat(discountSnapshot(workspaceId, "di_amb")).isEmpty();
        assertThat(revenue.movements(workspaceId, "cus_amb")).isEqualTo(movementsBefore);
        assertThat(revenue.snapshots(workspaceId, "cus_amb")).isEqualTo(snapshotsBefore);
    }

    // ---- 9. Fixed-amount discount IS supported when it resolves unambiguously to one subscription ----

    @Test
    void fixedAmountCustomerDiscountAppliesWhenExactlyOneSubscriptionIsAffected() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_fixed_ok", StripeConnectionMode.TEST);
        long start = T0.getEpochSecond();

        webhook(connectionId, workspaceId, "evt_fx_customer", "customer.created", T0,
                BillingFixtures.customer("cus_fx", "usd", start, false, null));
        webhook(connectionId, workspaceId, "evt_fx_price", "price.created", T0,
                BillingFixtures.price("price_fx", "prod_fx", "usd", 2000L, "recurring", "month", 1, true));
        webhook(connectionId, workspaceId, "evt_fx_sub", "customer.subscription.created", T0,
                BillingFixtures.subscription(
                        "sub_fx", "cus_fx", "active", "usd", start, start + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_fx", "price_fx", 1), null));
        webhook(connectionId, workspaceId, "evt_fx_discount", "customer.discount.created", T0.plusSeconds(60),
                BillingFixtures.discount(
                        "di_fx", "cus_fx", null, "coupon_fx", null, 300L, "usd", start + 60, FAR_FUTURE_END));
        assertThat(drainWebhookQueue()).isEqualTo(4);

        assertThat(processingState(workspaceId, "evt_fx_discount")).isEqualTo("PROCESSED");
        List<Movement> movements = revenue.movements(workspaceId, "cus_fx");
        assertThat(movements).extracting(Movement::type).containsExactly("NEW", "CONTRACTION");
        assertThat(movements).extracting(Movement::amountMinor).containsExactly(2000L, 300L);

        List<Snapshot> snapshots = revenue.snapshots(workspaceId, "cus_fx");
        Snapshot last = snapshots.get(snapshots.size() - 1);
        assertThat(last.supported()).isTrue();
        assertThat(last.amountMinor()).isEqualTo(1700L);
    }

    // ---- 10. Multi-currency customer: percentage discount applies independently per currency ---------

    @Test
    void multiCurrencyCustomerPercentageDiscountAppliesIndependentlyPerCurrency() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_multi_currency", StripeConnectionMode.TEST);
        long start = T0.getEpochSecond();

        webhook(connectionId, workspaceId, "evt_mc_customer", "customer.created", T0,
                BillingFixtures.customer("cus_mc", "usd", start, false, null));
        webhook(connectionId, workspaceId, "evt_mc_price_usd", "price.created", T0,
                BillingFixtures.price("price_mc_usd", "prod_mc_usd", "usd", 1000L, "recurring", "month", 1, true));
        webhook(connectionId, workspaceId, "evt_mc_price_eur", "price.created", T0,
                BillingFixtures.price("price_mc_eur", "prod_mc_eur", "eur", 2000L, "recurring", "month", 1, true));
        webhook(connectionId, workspaceId, "evt_mc_sub_usd", "customer.subscription.created", T0,
                BillingFixtures.subscription(
                        "sub_mc_usd", "cus_mc", "active", "usd", start, start + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_mc_usd", "price_mc_usd", 1), null));
        webhook(connectionId, workspaceId, "evt_mc_sub_eur", "customer.subscription.created", T0.plusSeconds(30),
                BillingFixtures.subscription(
                        "sub_mc_eur", "cus_mc", "active", "eur", start, start + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_mc_eur", "price_mc_eur", 1), null));
        assertThat(drainWebhookQueue()).isEqualTo(5);

        webhook(connectionId, workspaceId, "evt_mc_discount", "customer.discount.created", T0.plusSeconds(90),
                BillingFixtures.discount(
                        "di_mc", "cus_mc", null, "coupon_mc", 10L, null, null, start + 90, FAR_FUTURE_END));
        assertThat(drainWebhookQueue()).isEqualTo(1);

        List<Snapshot> snapshots = revenue.snapshots(workspaceId, "cus_mc");
        Snapshot usdFinal = snapshots.stream().filter(s -> "USD".equals(s.currency())).reduce((a, b) -> b).orElseThrow();
        Snapshot eurFinal = snapshots.stream().filter(s -> "EUR".equals(s.currency())).reduce((a, b) -> b).orElseThrow();
        assertThat(usdFinal.supported()).isTrue();
        assertThat(usdFinal.amountMinor()).isEqualTo(900L);
        assertThat(eurFinal.supported()).isTrue();
        assertThat(eurFinal.amountMinor()).isEqualTo(1800L);
    }

    // ---- 11. Pre-existing subscription discount + new customer-level discount: stacking is
    //          unsupported, not silently approximated ------------------------------------------------

    @Test
    void customerLevelDiscountLayeredOnAnExistingSubscriptionDiscountIsUnsupported() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_stack", StripeConnectionMode.TEST);
        long start = T0.getEpochSecond();

        webhook(connectionId, workspaceId, "evt_stk_customer", "customer.created", T0,
                BillingFixtures.customer("cus_stk", "usd", start, false, null));
        webhook(connectionId, workspaceId, "evt_stk_price", "price.created", T0,
                BillingFixtures.price("price_stk", "prod_stk", "usd", 2000L, "recurring", "month", 1, true));
        webhook(connectionId, workspaceId, "evt_stk_sub", "customer.subscription.created", T0,
                BillingFixtures.subscription(
                        "sub_stk", "cus_stk", "active", "usd", start, start + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_stk", "price_stk", 1),
                        BillingFixtures.discount(
                                "di_stk_sub", null, "sub_stk", "coupon_stk_sub", 15L, null, null, start, FAR_FUTURE_END)));
        assertThat(drainWebhookQueue()).isEqualTo(3);
        assertThat(revenue.snapshots(workspaceId, "cus_stk").stream().reduce((a, b) -> b).orElseThrow().amountMinor())
                .isEqualTo(1700L);

        webhook(connectionId, workspaceId, "evt_stk_discount", "customer.discount.created", T0.plusSeconds(60),
                BillingFixtures.discount(
                        "di_stk_cust", "cus_stk", null, "coupon_stk_cust", 10L, null, null, start + 60, FAR_FUTURE_END));
        assertThat(drainWebhookQueue()).isEqualTo(1);

        // The event itself is normalized (unlike the multi-subscription fixed-amount case, stacking
        // ambiguity is caught by RevenueCalculationService's own pre-existing guard, not rejected up
        // front), but the resulting customer state is explicitly UNSUPPORTED_DISCOUNT -- never a
        // fabricated combined discount.
        assertThat(processingState(workspaceId, "evt_stk_discount")).isEqualTo("PROCESSED");
        assertThat(discountSnapshot(workspaceId, "di_stk_cust")).isPresent();

        List<Snapshot> snapshots = revenue.snapshots(workspaceId, "cus_stk");
        Snapshot last = snapshots.get(snapshots.size() - 1);
        assertThat(last.supported()).isFalse();
        assertThat(last.unsupportedReason()).isEqualTo("UNSUPPORTED_DISCOUNT");
    }

    // ---- helpers --------------------------------------------------------------------------------

    private void webhook(UUID connectionId, UUID workspaceId, String eventId, String type, Instant at, String object) {
        insertPendingWebhookEvent(connectionId, workspaceId, StripeConnectionMode.TEST, eventId, type, at, object);
    }

    private void resetEventToPending(UUID workspaceId, String stripeEventId) {
        int updated = jdbc().sql(
                        """
                        UPDATE stripe_webhook_events
                        SET processing_state = 'PENDING', last_attempted_at = NULL, attempt_count = 0
                        WHERE workspace_id = :w AND stripe_event_id = :e
                        """)
                .param("w", workspaceId)
                .param("e", stripeEventId)
                .update();
        assertThat(updated).isEqualTo(1);
    }

    private String processingState(UUID workspaceId, String stripeEventId) {
        return jdbc().sql("SELECT processing_state FROM stripe_webhook_events WHERE workspace_id = :w AND stripe_event_id = :e")
                .param("w", workspaceId)
                .param("e", stripeEventId)
                .query(String.class)
                .single();
    }

    private String failureKind(UUID workspaceId, String stripeEventId) {
        return jdbc().sql("SELECT failure_kind FROM stripe_webhook_events WHERE workspace_id = :w AND stripe_event_id = :e")
                .param("w", workspaceId)
                .param("e", stripeEventId)
                .query(String.class)
                .single();
    }
}
