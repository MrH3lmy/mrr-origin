package com.mrrorigin.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
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

import com.mrrorigin.revenue.RevenueCalculationService;
import com.mrrorigin.revenue.RevenueModels.Movement;
import com.mrrorigin.revenue.RevenueModels.Snapshot;

/**
 * #83's production-wiring proof: real webhook and backfill normalization -- not direct calls to
 * {@link RevenueCalculationService} -- must produce persisted {@code customer_mrr_movements}/
 * {@code customer_mrr_snapshots}. See ADR-0010 for the orchestration decision this exercises.
 */
@Testcontainers
class StripeMrrRecalculationIntegrationTests extends AbstractBillingLedgerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    private static final StripeBillingListApiStub STRIPE_LIST_STUB = new StripeBillingListApiStub();

    @DynamicPropertySource
    static void stripeListApi(DynamicPropertyRegistry registry) {
        registry.add("mrrorigin.stripe.connect.api-base-uri", STRIPE_LIST_STUB::apiBaseUri);
    }

    @Autowired
    private RevenueCalculationService revenue;

    private static final Instant T0 = Instant.parse("2026-05-01T00:00:00Z");
    private static final long FAR_FUTURE_END = Instant.parse("2030-01-01T00:00:00Z").getEpochSecond();

    // ---- 1. Webhook -> MRR end-to-end: created -> upgraded -> downgraded -> cancelled ------------

    @Test
    void webhookSubscriptionLifecycleProducesExpectedMrrMovementsAndSnapshots() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_lifecycle", StripeConnectionMode.TEST);

        webhook(connectionId, workspaceId, "evt_lc_customer", "customer.created", T0,
                BillingFixtures.customer("cus_lc", "usd", T0.getEpochSecond(), false, null));
        webhook(connectionId, workspaceId, "evt_lc_price_basic", "price.created", T0,
                BillingFixtures.price("price_lc_basic", "prod_lc", "usd", 2000L, "recurring", "month", 1, true));
        webhook(connectionId, workspaceId, "evt_lc_price_pro", "price.created", T0,
                BillingFixtures.price("price_lc_pro", "prod_lc", "usd", 5000L, "recurring", "month", 1, true));

        long createdAt = T0.plusSeconds(60).getEpochSecond();
        webhook(connectionId, workspaceId, "evt_lc_created", "customer.subscription.created", T0.plusSeconds(60),
                BillingFixtures.subscription(
                        "sub_lc", "cus_lc", "active", "usd", createdAt, createdAt + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_lc", "price_lc_basic", 1), null));

        webhook(connectionId, workspaceId, "evt_lc_upgraded", "customer.subscription.updated", T0.plusSeconds(120),
                BillingFixtures.subscription(
                        "sub_lc", "cus_lc", "active", "usd", createdAt, createdAt + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_lc", "price_lc_pro", 1), null));

        webhook(connectionId, workspaceId, "evt_lc_downgraded", "customer.subscription.updated", T0.plusSeconds(180),
                BillingFixtures.subscription(
                        "sub_lc", "cus_lc", "active", "usd", createdAt, createdAt + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_lc", "price_lc_basic", 1), null));

        webhook(connectionId, workspaceId, "evt_lc_cancelled", "customer.subscription.deleted", T0.plusSeconds(240),
                BillingFixtures.subscription(
                        "sub_lc", "cus_lc", "canceled", "usd", createdAt, createdAt + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_lc", "price_lc_basic", 1), null));

        assertThat(drainWebhookQueue()).isEqualTo(7);

        List<Movement> movements = revenue.movements(workspaceId, "cus_lc");
        assertThat(movements).extracting(Movement::type).containsExactly("NEW", "EXPANSION", "CONTRACTION", "CHURN");
        assertThat(movements).extracting(Movement::amountMinor).containsExactly(2000L, 3000L, 3000L, 2000L);

        List<Snapshot> snapshots = revenue.snapshots(workspaceId, "cus_lc");
        assertThat(snapshots).isNotEmpty();
        assertThat(snapshots.get(snapshots.size() - 1))
                .satisfies(last -> {
                    assertThat(last.supported()).isTrue();
                    assertThat(last.amountMinor()).isZero();
                });
    }

    // ---- 2. Backfill -> MRR convergence -----------------------------------------------------------

    @Test
    void backfillAndWebhookConvergeToTheSameFinalMrrSnapshot() {
        UUID webhookWorkspace = createWorkspace();
        UUID backfillWorkspace = createWorkspace();
        UUID webhookConnection = insertActiveConnection(webhookWorkspace, "acct_conv_wh", StripeConnectionMode.TEST);
        UUID backfillConnection = insertActiveConnection(backfillWorkspace, "acct_conv_bf", StripeConnectionMode.TEST);

        long start = T0.getEpochSecond();
        String finalSubscription = BillingFixtures.subscription(
                "sub_conv_mrr", "cus_conv_mrr", "active", "usd", start, start + 2_592_000L, false, null, null,
                BillingFixtures.subscriptionItem("si_conv_mrr", "price_conv_mrr", 2), null);

        STRIPE_LIST_STUB.seed("/v1/customers", List.of(BillingFixtures.customer("cus_conv_mrr", "usd", start, false, null)));
        STRIPE_LIST_STUB.seed(
                "/v1/prices", List.of(BillingFixtures.price("price_conv_mrr", "prod_conv_mrr", "usd", 1500L, "recurring", "month", 1, true)));
        STRIPE_LIST_STUB.seed("/v1/subscriptions", List.of(finalSubscription));
        STRIPE_LIST_STUB.seed("/v1/invoices", List.of());
        STRIPE_LIST_STUB.seed("/v1/charges", List.of());
        STRIPE_LIST_STUB.seed("/v1/refunds", List.of());

        webhook(webhookConnection, webhookWorkspace, "evt_conv_mrr_customer", "customer.created", T0,
                BillingFixtures.customer("cus_conv_mrr", "usd", start, false, null));
        webhook(webhookConnection, webhookWorkspace, "evt_conv_mrr_price", "price.created", T0,
                BillingFixtures.price("price_conv_mrr", "prod_conv_mrr", "usd", 1500L, "recurring", "month", 1, true));
        webhook(webhookConnection, webhookWorkspace, "evt_conv_mrr_sub", "customer.subscription.created", T0,
                finalSubscription);
        assertThat(drainWebhookQueue()).isEqualTo(3);

        runBackfillToCompletion(backfillConnection);

        List<Snapshot> webhookSnapshots = revenue.snapshots(webhookWorkspace, "cus_conv_mrr");
        List<Snapshot> backfillSnapshots = revenue.snapshots(backfillWorkspace, "cus_conv_mrr");
        Snapshot webhookFinal = webhookSnapshots.get(webhookSnapshots.size() - 1);
        Snapshot backfillFinal = backfillSnapshots.get(backfillSnapshots.size() - 1);

        assertThat(backfillFinal.supported()).isEqualTo(webhookFinal.supported());
        assertThat(backfillFinal.currency()).isEqualTo(webhookFinal.currency());
        assertThat(backfillFinal.amountMinor()).isEqualTo(webhookFinal.amountMinor()).isEqualTo(3000L);

        assertThat(revenue.movements(webhookWorkspace, "cus_conv_mrr")).extracting(Movement::type).containsExactly("NEW");
        assertThat(revenue.movements(backfillWorkspace, "cus_conv_mrr")).extracting(Movement::type).containsExactly("NEW");
    }

    // ---- 3. Duplicate / replay --------------------------------------------------------------------

    @Test
    void duplicateAndReplayedEventsProduceNoDuplicateMovementsOrSnapshots() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_dup", StripeConnectionMode.TEST);

        long start = T0.getEpochSecond();
        String activeSubscription = BillingFixtures.subscription(
                "sub_dup", "cus_dup", "active", "usd", start, start + 2_592_000L, false, null, null,
                BillingFixtures.subscriptionItem("si_dup", "price_dup", 1), null);

        webhook(connectionId, workspaceId, "evt_dup_customer", "customer.created", T0,
                BillingFixtures.customer("cus_dup", "usd", start, false, null));
        webhook(connectionId, workspaceId, "evt_dup_price", "price.created", T0,
                BillingFixtures.price("price_dup", "prod_dup", "usd", 1000L, "recurring", "month", 1, true));
        webhook(connectionId, workspaceId, "evt_dup_sub", "customer.subscription.created", T0, activeSubscription);
        assertThat(drainWebhookQueue()).isEqualTo(3);

        List<Movement> firstPass = revenue.movements(workspaceId, "cus_dup");
        List<Snapshot> firstSnapshots = revenue.snapshots(workspaceId, "cus_dup");

        // Reprocess the exact same already-PROCESSED row a second time -- the same
        // (source_version, source_sequence) this system uses as its idempotency key, exactly what
        // happens on a lease-expiry reclaim or an explicit FAILED replay after a transient error.
        // recordAndReplay's ON CONFLICT(workspace_id, source_billing_reference) DO NOTHING makes the
        // re-insert of revenue_subscription_states a true no-op, so the replay must not add or
        // change any movement/snapshot row.
        resetEventToPending(workspaceId, "evt_dup_sub");
        assertThat(drainWebhookQueue()).isEqualTo(1);

        assertThat(revenue.movements(workspaceId, "cus_dup")).isEqualTo(firstPass);
        assertThat(revenue.snapshots(workspaceId, "cus_dup")).isEqualTo(firstSnapshots);
    }

    // ---- 4. Out-of-order input ---------------------------------------------------------------------

    @Test
    void outOfOrderEventDoesNotRegressNewerMrrState() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_ooo", StripeConnectionMode.TEST);

        long start = T0.getEpochSecond();
        webhook(connectionId, workspaceId, "evt_ooo_customer", "customer.created", T0,
                BillingFixtures.customer("cus_ooo", "usd", start, false, null));
        webhook(connectionId, workspaceId, "evt_ooo_price", "price.created", T0,
                BillingFixtures.price("price_ooo", "prod_ooo", "usd", 1000L, "recurring", "month", 1, true));
        webhook(connectionId, workspaceId, "evt_ooo_created", "customer.subscription.created", T0.plusSeconds(60),
                BillingFixtures.subscription(
                        "sub_ooo", "cus_ooo", "active", "usd", start, start + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_ooo", "price_ooo", 1), null));
        assertThat(drainWebhookQueue()).isEqualTo(3);

        // The chronologically newer change (qty 3, provider second T0+180) is delivered and
        // processed first...
        webhook(connectionId, workspaceId, "evt_ooo_newer", "customer.subscription.updated", T0.plusSeconds(180),
                BillingFixtures.subscription(
                        "sub_ooo", "cus_ooo", "active", "usd", start, start + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_ooo", "price_ooo", 3), null));
        assertThat(drainWebhookQueue()).isEqualTo(1);

        // ...then a chronologically older change (qty 2, provider second T0+120) is delivered late,
        // after the newer one already applied.
        webhook(connectionId, workspaceId, "evt_ooo_older", "customer.subscription.updated", T0.plusSeconds(120),
                BillingFixtures.subscription(
                        "sub_ooo", "cus_ooo", "active", "usd", start, start + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_ooo", "price_ooo", 2), null));
        assertThat(drainWebhookQueue()).isEqualTo(1);

        // BillingLedgerUpsertService's own version guard (source_version, source_sequence) already
        // rejects a ledger write older than what's currently stored -- the late-arriving older event
        // never applies to billing_subscriptions at all, so recalculateMrr is never even invoked for
        // it. The current/latest snapshot still reflects the newer (qty 3) state, not the late (qty
        // 2) one, and the ledger itself was never regressed either.
        assertThat(subscriptionItemSnapshots(workspaceId, "sub_ooo"))
                .singleElement()
                .satisfies(item -> assertThat(item.get("quantity")).isEqualTo(3));

        List<Snapshot> snapshots = revenue.snapshots(workspaceId, "cus_ooo");
        Snapshot latest = snapshots.get(snapshots.size() - 1);
        assertThat(latest.supported()).isTrue();
        assertThat(latest.amountMinor()).isEqualTo(3000L);

        List<Movement> movements = revenue.movements(workspaceId, "cus_ooo");
        assertThat(movements).extracting(Movement::type).containsExactly("NEW", "EXPANSION");
        assertThat(movements).extracting(Movement::amountMinor).containsExactly(1000L, 2000L);
    }

    // ---- 5. Transaction / recovery boundary ---------------------------------------------------------

    @Test
    void failureAtTheNormalizationToMrrBoundaryRollsBackAtomicallyAndRetryConverges() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_recovery", StripeConnectionMode.TEST);

        long start = T0.getEpochSecond();
        webhook(connectionId, workspaceId, "evt_rec_customer", "customer.created", T0,
                BillingFixtures.customer("cus_recovery", "usd", start, false, null));
        webhook(connectionId, workspaceId, "evt_rec_price", "price.created", T0,
                BillingFixtures.price("price_recovery", "prod_recovery", "usd", 1000L, "recurring", "month", 1, true));
        webhook(connectionId, workspaceId, "evt_rec_created", "customer.subscription.created", T0.plusSeconds(60),
                BillingFixtures.subscription(
                        "sub_recovery", "cus_recovery", "active", "usd", start, start + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_recovery", "price_recovery", 1), null));
        webhook(connectionId, workspaceId, "evt_rec_expand", "customer.subscription.updated", T0.plusSeconds(120),
                BillingFixtures.subscription(
                        "sub_recovery", "cus_recovery", "active", "usd", start, start + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_recovery", "price_recovery", 2), null));
        assertThat(drainWebhookQueue()).isEqualTo(4);

        List<Movement> beforeFailure = revenue.movements(workspaceId, "cus_recovery");
        List<Snapshot> snapshotsBeforeFailure = revenue.snapshots(workspaceId, "cus_recovery");
        var subscriptionBeforeFailure = subscriptionSnapshot(workspaceId, "sub_recovery").orElseThrow();
        var itemsBeforeFailure = subscriptionItemSnapshots(workspaceId, "sub_recovery");

        // A malformed payload with two embedded items sharing the same item ID: the ledger write
        // succeeds (billing_subscription_items upserts idempotently by stripe_subscription_item_id,
        // so the duplicate is just an overwrite), but RevenueCalculationService's own
        // revenue_subscription_state_items insert has no such ON CONFLICT clause and fails on the
        // second duplicate reference -- forcing a real failure at the normalization -> MRR boundary,
        // strictly after the ledger write already looked fine in isolation. Because both writes share
        // one transaction, the whole thing -- ledger, lease/checkpoint, and MRR -- rolls back together;
        // nothing partially commits.
        webhook(connectionId, workspaceId, "evt_rec_malformed", "customer.subscription.updated", T0.plusSeconds(150),
                BillingFixtures.subscription(
                        "sub_recovery", "cus_recovery", "active", "usd", start, start + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_recovery", "price_recovery", 3) + ","
                                + BillingFixtures.subscriptionItem("si_recovery", "price_recovery", 5),
                        null));
        assertThat(drainWebhookQueue()).isEqualTo(1);

        assertThat(processingState(workspaceId, "evt_rec_malformed")).isEqualTo("FAILED");
        // Ledger write rolled back together with the MRR write: still quantity 2, not 3.
        assertThat(subscriptionSnapshot(workspaceId, "sub_recovery")).contains(subscriptionBeforeFailure);
        assertThat(subscriptionItemSnapshots(workspaceId, "sub_recovery")).isEqualTo(itemsBeforeFailure);
        assertThat(revenue.movements(workspaceId, "cus_recovery")).isEqualTo(beforeFailure);
        assertThat(revenue.snapshots(workspaceId, "cus_recovery")).isEqualTo(snapshotsBeforeFailure);

        // Retry with a corrected (non-colliding) provider timestamp converges cleanly: no missing
        // output, no duplicated movements, no partially committed state.
        webhook(connectionId, workspaceId, "evt_rec_retry", "customer.subscription.updated", T0.plusSeconds(180),
                BillingFixtures.subscription(
                        "sub_recovery", "cus_recovery", "active", "usd", start, start + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_recovery", "price_recovery", 3), null));
        assertThat(drainWebhookQueue()).isEqualTo(1);

        List<Movement> afterRetry = revenue.movements(workspaceId, "cus_recovery");
        assertThat(afterRetry).extracting(Movement::type).containsExactly("NEW", "EXPANSION", "EXPANSION");
        assertThat(afterRetry).extracting(Movement::amountMinor).containsExactly(1000L, 1000L, 1000L);
        assertThat(subscriptionItemSnapshots(workspaceId, "sub_recovery"))
                .singleElement()
                .satisfies(item -> assertThat(item.get("quantity")).isEqualTo(3));
    }

    // ---- 6. Cross-tenant isolation -------------------------------------------------------------------

    @Test
    void crossTenantProcessingLeavesTheOtherWorkspaceUntouched() {
        UUID workspaceA = createWorkspace();
        UUID workspaceB = createWorkspace();
        UUID connectionA = insertActiveConnection(workspaceA, "acct_tenant_a", StripeConnectionMode.TEST);
        UUID connectionB = insertActiveConnection(workspaceB, "acct_tenant_b", StripeConnectionMode.TEST);

        long start = T0.getEpochSecond();
        webhook(connectionB, workspaceB, "evt_b_customer", "customer.created", T0,
                BillingFixtures.customer("cus_shared_id", "usd", start, false, null));
        webhook(connectionB, workspaceB, "evt_b_price", "price.created", T0,
                BillingFixtures.price("price_shared_id", "prod_b", "usd", 4000L, "recurring", "month", 1, true));
        webhook(connectionB, workspaceB, "evt_b_sub", "customer.subscription.created", T0.plusSeconds(60),
                BillingFixtures.subscription(
                        "sub_shared_id", "cus_shared_id", "active", "usd", start, start + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_b", "price_shared_id", 1), null));
        assertThat(drainWebhookQueue()).isEqualTo(3);

        List<Movement> workspaceBMovementsBefore = revenue.movements(workspaceB, "cus_shared_id");
        List<Snapshot> workspaceBSnapshotsBefore = revenue.snapshots(workspaceB, "cus_shared_id");
        assertThat(workspaceBMovementsBefore).extracting(Movement::type).containsExactly("NEW");

        // Workspace A reuses the exact same Stripe customer/subscription/price IDs (Stripe IDs are
        // only unique within one Stripe account, never globally) with completely different economics.
        webhook(connectionA, workspaceA, "evt_a_customer", "customer.created", T0,
                BillingFixtures.customer("cus_shared_id", "usd", start, false, null));
        webhook(connectionA, workspaceA, "evt_a_price", "price.created", T0,
                BillingFixtures.price("price_shared_id", "prod_a", "usd", 9900L, "recurring", "month", 1, true));
        webhook(connectionA, workspaceA, "evt_a_sub", "customer.subscription.created", T0.plusSeconds(60),
                BillingFixtures.subscription(
                        "sub_shared_id", "cus_shared_id", "active", "usd", start, start + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_a", "price_shared_id", 1), null));
        assertThat(drainWebhookQueue()).isEqualTo(3);

        assertThat(revenue.movements(workspaceA, "cus_shared_id")).extracting(Movement::amountMinor).containsExactly(9900L);
        assertThat(revenue.movements(workspaceB, "cus_shared_id")).isEqualTo(workspaceBMovementsBefore);
        assertThat(revenue.snapshots(workspaceB, "cus_shared_id")).isEqualTo(workspaceBSnapshotsBefore);
    }

    // ---- 7. Same-second, different-sequence changes converge regardless of processing order --------

    @Test
    void sameSecondDifferentSequenceSubscriptionChangesConvergeToIdenticalMrrRegardlessOfProcessingOrder() {
        UUID forwardWorkspace = createWorkspace();
        UUID reversedWorkspace = createWorkspace();
        long sharedVersion = 1_800_000_000L;
        var lower = new BillingSourceVersion.SourceVersion(sharedVersion, "W:evt_order_a");
        var higher = new BillingSourceVersion.SourceVersion(sharedVersion, "W:evt_order_b");

        var price = new StripeBillingObjects.ParsedPrice(
                "price_order", "prod_order", "usd", 1000L, "per_unit", "recurring", "month", 1, "licensed", true);
        ledger.upsertPrice(forwardWorkspace, price, lower, BillingLedgerSource.WEBHOOK);
        ledger.upsertPrice(reversedWorkspace, price, lower, BillingLedgerSource.WEBHOOK);

        var trialing = subscriptionWithItem("sub_order", "cus_order", "trialing", "price_order", 1);
        var active = subscriptionWithItem("sub_order", "cus_order", "active", "price_order", 1);

        // Forward: lower-sequence (trialing) applied, then higher-sequence (active) -- both accepted.
        ledger.upsertSubscription(forwardWorkspace, trialing, lower, BillingLedgerSource.WEBHOOK);
        ledger.upsertSubscription(forwardWorkspace, active, higher, BillingLedgerSource.WEBHOOK);

        // Reversed: higher-sequence (active) applied first, then lower-sequence (trialing) arrives
        // after -- rejected as stale by the ledger's own version guard before MRR ever sees it.
        ledger.upsertSubscription(reversedWorkspace, active, higher, BillingLedgerSource.WEBHOOK);
        ledger.upsertSubscription(reversedWorkspace, trialing, lower, BillingLedgerSource.WEBHOOK);

        List<Movement> forwardMovements = revenue.movements(forwardWorkspace, "cus_order");
        List<Movement> reversedMovements = revenue.movements(reversedWorkspace, "cus_order");
        List<Snapshot> forwardSnapshots = revenue.snapshots(forwardWorkspace, "cus_order");
        List<Snapshot> reversedSnapshots = revenue.snapshots(reversedWorkspace, "cus_order");

        assertThat(forwardMovements).extracting(Movement::type).containsExactly("NEW");
        assertThat(forwardMovements).extracting(Movement::amountMinor).containsExactly(1000L);
        assertThat(forwardMovements).isEqualTo(reversedMovements);
        assertThat(forwardSnapshots).isEqualTo(reversedSnapshots);
        assertThat(jdbc().sql("SELECT count(*) FROM revenue_subscription_states WHERE workspace_id = :w")
                        .param("w", forwardWorkspace)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    // ---- 8. Backfill source_billing_reference stays globally unique across seconds -----------------

    @Test
    void backfillFetchesInDifferentSecondsWithTheSameNanosecondFractionProduceDistinctMrrStates() {
        UUID workspaceId = createWorkspace();
        var price = new StripeBillingObjects.ParsedPrice(
                "price_bfref", "prod_bfref", "usd", 1000L, "per_unit", "recurring", "month", 1, "licensed", true);
        // Same `sequence` (nanosecond-within-second fraction), different `version` (whole second) --
        // exactly the case a sequence-only reference would silently collide on.
        var firstFetch = new BillingSourceVersion.SourceVersion(2_000_000_000L, "Z:000000001");
        var secondFetch = new BillingSourceVersion.SourceVersion(2_000_000_100L, "Z:000000001");
        ledger.upsertPrice(workspaceId, price, firstFetch, BillingLedgerSource.BACKFILL);

        var first = subscriptionWithItem("sub_bfref", "cus_bfref", "active", "price_bfref", 1);
        var second = subscriptionWithItem("sub_bfref", "cus_bfref", "active", "price_bfref", 2);

        ledger.upsertSubscription(workspaceId, first, firstFetch, BillingLedgerSource.BACKFILL);
        ledger.upsertSubscription(workspaceId, second, secondFetch, BillingLedgerSource.BACKFILL);

        List<Movement> movements = revenue.movements(workspaceId, "cus_bfref");
        assertThat(movements).extracting(Movement::type).containsExactly("NEW", "EXPANSION");
        assertThat(movements).extracting(Movement::amountMinor).containsExactly(1000L, 1000L);
    }

    // ---- 9. Metered recurring price fails visibly instead of fabricating MRR -----------------------

    @Test
    void meteredRecurringPriceWithNonNullUnitAmountFailsVisiblyInsteadOfFabricatingMrr() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_metered", StripeConnectionMode.TEST);
        long start = T0.getEpochSecond();

        webhook(connectionId, workspaceId, "evt_metered_customer", "customer.created", T0,
                BillingFixtures.customer("cus_metered", "usd", start, false, null));
        // A metered price still carries a non-null unit_amount (the per-unit rate) -- the bug this
        // guards against is treating that as an ordinary fixed recurring charge.
        webhook(connectionId, workspaceId, "evt_metered_price", "price.created", T0,
                BillingFixtures.price("price_metered", "prod_metered", "usd", 500L, "recurring", "month", 1, "metered", true));
        webhook(connectionId, workspaceId, "evt_metered_sub", "customer.subscription.created", T0.plusSeconds(60),
                BillingFixtures.subscription(
                        "sub_metered", "cus_metered", "active", "usd", start, start + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_metered", "price_metered", 1), null));
        assertThat(drainWebhookQueue()).isEqualTo(3);

        List<Snapshot> snapshots = revenue.snapshots(workspaceId, "cus_metered");
        assertThat(snapshots).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.supported()).isFalse();
            assertThat(snapshot.unsupportedReason()).isEqualTo("UNSUPPORTED_USAGE_PRICING");
            assertThat(snapshot.amountMinor()).isNull();
        });
        assertThat(revenue.movements(workspaceId, "cus_metered")).isEmpty();
    }

    // ---- 10. Customer-level discounts -----------------------------------------------------------

    @Test
    void customerPercentageDiscountExistingBeforeSubscriptionIsAppliedAndReplayIsDeterministic() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_customer_discount", StripeConnectionMode.TEST);
        long start = T0.getEpochSecond();
        String discount = BillingFixtures.discount(
                "di_customer", "cus_customer_discount", null, "coupon_customer", 25L, null, null,
                start - 60, start + 3_600L);
        webhook(connectionId, workspaceId, "evt_cd_customer", "customer.created", T0,
                BillingFixtures.customer("cus_customer_discount", "usd", start - 60, false, discount));
        webhook(connectionId, workspaceId, "evt_cd_price", "price.created", T0,
                BillingFixtures.price("price_cd", "prod_cd", "usd", 2000L, "recurring", "month", 1, true));
        webhook(connectionId, workspaceId, "evt_cd_sub", "customer.subscription.created", T0.plusSeconds(60),
                BillingFixtures.subscription(
                        "sub_cd", "cus_customer_discount", "active", "usd", start, start + 2_592_000L,
                        false, null, null, BillingFixtures.subscriptionItem("si_cd", "price_cd", 1), null));
        assertThat(drainWebhookQueue()).isEqualTo(3);
        assertThat(revenue.snapshots(workspaceId, "cus_customer_discount"))
                .singleElement().extracting(Snapshot::amountMinor).isEqualTo(1500L);

        resetEventToPending(workspaceId, "evt_cd_sub");
        assertThat(drainWebhookQueue()).isEqualTo(1);
        assertThat(revenue.snapshots(workspaceId, "cus_customer_discount"))
                .singleElement().extracting(Snapshot::amountMinor).isEqualTo(1500L);
        assertThat(revenue.movements(workspaceId, "cus_customer_discount")).hasSize(1);
    }

    @Test
    void fullBackfillAppliesCustomerPercentageDiscountBeforeSubscriptionPhase() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_discount_backfill", StripeConnectionMode.TEST);
        long start = T0.getEpochSecond();
        String discount = BillingFixtures.discount(
                "di_backfill_customer", "cus_backfill_customer", null, "coupon_backfill", 20L, null, null,
                start - 60, start + 3_600L);
        STRIPE_LIST_STUB.seed("/v1/customers", List.of(
                BillingFixtures.customer("cus_backfill_customer", "usd", start - 60, false, discount)));
        STRIPE_LIST_STUB.seed("/v1/prices", List.of(
                BillingFixtures.price("price_backfill_discount", "prod_backfill_discount", "usd", 5000L,
                        "recurring", "month", 1, true)));
        STRIPE_LIST_STUB.seed("/v1/subscriptions", List.of(BillingFixtures.subscription(
                "sub_backfill_discount", "cus_backfill_customer", "active", "usd", start, start + 2_592_000L,
                false, null, null,
                BillingFixtures.subscriptionItem("si_backfill_discount", "price_backfill_discount", 1), null)));
        STRIPE_LIST_STUB.seed("/v1/invoices", List.of());
        STRIPE_LIST_STUB.seed("/v1/charges", List.of());
        STRIPE_LIST_STUB.seed("/v1/refunds", List.of());

        runBackfillToCompletion(connectionId);
        assertThat(revenue.snapshots(workspaceId, "cus_backfill_customer"))
                .singleElement().extracting(Snapshot::amountMinor).isEqualTo(4000L);
    }

    @Test
    void customerDiscountLookupRespectsEffectiveStartAndEnd() {
        assertTemporalCustomerDiscountNotApplied("future", T0.plusSeconds(120).getEpochSecond(), T0.plusSeconds(600).getEpochSecond());
        assertTemporalCustomerDiscountNotApplied("ended", T0.minusSeconds(600).getEpochSecond(), T0.minusSeconds(1).getEpochSecond());
    }

    @Test
    void subscriptionAndCustomerDiscountStackingFailsVisibly() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_discount_stack", StripeConnectionMode.TEST);
        long start = T0.getEpochSecond();
        String customerDiscount = BillingFixtures.discount(
                "di_stack_customer", "cus_stack", null, "coupon_stack_customer", 10L, null, null,
                start - 60, start + 3_600L);
        String subscriptionDiscount = BillingFixtures.discount(
                "di_stack_subscription", null, "sub_stack", "coupon_stack_subscription", 5L, null, null,
                start - 60, start + 3_600L);
        webhook(connectionId, workspaceId, "evt_stack_customer", "customer.created", T0,
                BillingFixtures.customer("cus_stack", "usd", start - 60, false, customerDiscount));
        webhook(connectionId, workspaceId, "evt_stack_price", "price.created", T0,
                BillingFixtures.price("price_stack", "prod_stack", "usd", 1000L, "recurring", "month", 1, true));
        webhook(connectionId, workspaceId, "evt_stack_sub", "customer.subscription.created", T0,
                BillingFixtures.subscription("sub_stack", "cus_stack", "active", "usd", start,
                        start + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_stack", "price_stack", 1), subscriptionDiscount));
        assertThat(drainWebhookQueue()).isEqualTo(3);
        assertThat(revenue.snapshots(workspaceId, "cus_stack")).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.supported()).isFalse();
            assertThat(snapshot.unsupportedReason()).isEqualTo("UNSUPPORTED_DISCOUNT");
        });
        assertThat(revenue.movements(workspaceId, "cus_stack")).isEmpty();
    }

    @Test
    void equivalentCustomerDiscountExpandedInSubscriptionPayloadIsAppliedOnlyOnce() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_discount_equivalent", StripeConnectionMode.TEST);
        long start = T0.getEpochSecond();
        String customerDiscount = BillingFixtures.discount(
                "di_equivalent_customer", "cus_equivalent", null, "coupon_equivalent", 25L, null, null,
                start - 60, start + 3_600L);
        // A different expansion ID exercises semantic deduplication, including the NUMERIC scale
        // difference between the ledger's 25.000 and the payload parser's 25.
        String expandedDiscount = BillingFixtures.discount(
                "di_equivalent_expanded", null, "sub_equivalent", "coupon_equivalent", 25L, null, null,
                start - 60, start + 3_600L);
        webhook(connectionId, workspaceId, "evt_equivalent_customer", "customer.created", T0,
                BillingFixtures.customer("cus_equivalent", "usd", start - 60, false, customerDiscount));
        webhook(connectionId, workspaceId, "evt_equivalent_price", "price.created", T0,
                BillingFixtures.price("price_equivalent", "prod_equivalent", "usd", 2000L,
                        "recurring", "month", 1, true));
        webhook(connectionId, workspaceId, "evt_equivalent_sub", "customer.subscription.created", T0,
                BillingFixtures.subscription("sub_equivalent", "cus_equivalent", "active", "usd", start,
                        start + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_equivalent", "price_equivalent", 1), expandedDiscount));
        assertThat(drainWebhookQueue()).isEqualTo(3);

        assertThat(revenue.snapshots(workspaceId, "cus_equivalent")).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.supported()).isTrue();
            assertThat(snapshot.amountMinor()).isEqualTo(1500L);
        });
        assertThat(jdbc().sql(
                        """
                        SELECT count(*) FROM revenue_subscription_state_discounts d
                        JOIN revenue_subscription_states s ON s.id = d.state_id AND s.workspace_id = d.workspace_id
                        WHERE d.workspace_id = :workspaceId AND s.stripe_subscription_id = 'sub_equivalent'
                        """)
                .param("workspaceId", workspaceId)
                .query(Long.class)
                .single()).isEqualTo(1L);
    }

    @Test
    void fixedCustomerDiscountBecomesExplicitlyUnsupportedWhenSecondSubscriptionMakesAllocationAmbiguous() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_fixed_customer", StripeConnectionMode.TEST);
        long start = T0.getEpochSecond();
        String discount = BillingFixtures.discount(
                "di_fixed_customer", "cus_fixed", null, "coupon_fixed", null, 300L, "usd",
                start - 60, start + 3_600L);
        webhook(connectionId, workspaceId, "evt_fixed_customer", "customer.created", T0,
                BillingFixtures.customer("cus_fixed", "usd", start - 60, false, discount));
        webhook(connectionId, workspaceId, "evt_fixed_price", "price.created", T0,
                BillingFixtures.price("price_fixed", "prod_fixed", "usd", 1000L, "recurring", "month", 1, true));
        webhook(connectionId, workspaceId, "evt_fixed_sub1", "customer.subscription.created", T0,
                BillingFixtures.subscription("sub_fixed_1", "cus_fixed", "active", "usd", start,
                        start + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_fixed_1", "price_fixed", 1), null));
        webhook(connectionId, workspaceId, "evt_fixed_sub2", "customer.subscription.created", T0.plusSeconds(60),
                BillingFixtures.subscription("sub_fixed_2", "cus_fixed", "active", "usd", start + 60,
                        start + 2_592_060L, false, null, null,
                        BillingFixtures.subscriptionItem("si_fixed_2", "price_fixed", 1), null));
        assertThat(drainWebhookQueue()).isEqualTo(4);
        Snapshot latest = revenue.snapshots(workspaceId, "cus_fixed").getLast();
        assertThat(latest.supported()).isFalse();
        assertThat(latest.unsupportedReason()).isEqualTo("AMBIGUOUS_FIXED_DISCOUNT_ALLOCATION");
        assertThat(latest.amountMinor()).isNull();
    }

    @Test
    void customerDiscountLookupIsWorkspaceScoped() {
        UUID discountedWorkspace = createWorkspace();
        UUID plainWorkspace = createWorkspace();
        UUID discountedConnection = insertActiveConnection(discountedWorkspace, "acct_tenant_discount", StripeConnectionMode.TEST);
        UUID plainConnection = insertActiveConnection(plainWorkspace, "acct_tenant_plain", StripeConnectionMode.TEST);
        long start = T0.getEpochSecond();
        String discount = BillingFixtures.discount("di_tenant", "cus_shared", null, "coupon_tenant", 50L,
                null, null, start - 60, start + 3_600L);
        webhook(discountedConnection, discountedWorkspace, "evt_tenant_customer_a", "customer.created", T0,
                BillingFixtures.customer("cus_shared", "usd", start, false, discount));
        webhook(plainConnection, plainWorkspace, "evt_tenant_customer_b", "customer.created", T0,
                BillingFixtures.customer("cus_shared", "usd", start, false, null));
        webhook(plainConnection, plainWorkspace, "evt_tenant_price_b", "price.created", T0,
                BillingFixtures.price("price_tenant", "prod_tenant", "usd", 1000L, "recurring", "month", 1, true));
        webhook(plainConnection, plainWorkspace, "evt_tenant_sub_b", "customer.subscription.created", T0,
                BillingFixtures.subscription("sub_tenant", "cus_shared", "active", "usd", start,
                        start + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_tenant", "price_tenant", 1), null));
        assertThat(drainWebhookQueue()).isEqualTo(4);
        assertThat(revenue.snapshots(plainWorkspace, "cus_shared"))
                .singleElement().extracting(Snapshot::amountMinor).isEqualTo(1000L);
    }

    // ---- 11. Historical resolution: a delayed subscription webhook must still see a customer
    //          discount that was deleted (or switched) after the subscription's own effective_at,
    //          never a plausible-but-stale MRR just because billing_discounts only keeps current
    //          state. See ADR-0011's "Historical state: delete and update" amendment. ---------------

    @Test
    void delayedSubscriptionCreatedAfterCustomerDiscountDeletedStillAppliesTheHistoricalDiscount() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_hist_del", StripeConnectionMode.TEST);
        long start = T0.getEpochSecond();

        webhook(connectionId, workspaceId, "evt_hd_customer", "customer.created", T0,
                BillingFixtures.customer("cus_hd", "usd", start - 200, false, null));
        webhook(connectionId, workspaceId, "evt_hd_price", "price.created", T0,
                BillingFixtures.price("price_hd", "prod_hd", "usd", 2000L, "recurring", "month", 1, true));
        assertThat(drainWebhookQueue()).isEqualTo(2);

        // T1: a customer-level percentage discount starts, well before the subscription's own
        // effective_at (current_period_start = start, below).
        webhook(connectionId, workspaceId, "evt_hd_discount", "customer.discount.created", T0.plusSeconds(10),
                BillingFixtures.discount("di_hd", "cus_hd", null, "coupon_hd", 25L, null, null, start - 100, null));
        assertThat(drainWebhookQueue()).isEqualTo(1);

        // T3: customer.discount.deleted is processed -- with Stripe's own provider-declared `end` --
        // BEFORE the delayed subscription webhook below. billing_discounts is now deleted = true.
        long deletedAt = start + 500;
        webhook(connectionId, workspaceId, "evt_hd_deleted", "customer.discount.deleted", T0.plusSeconds(20),
                BillingFixtures.discount("di_hd", "cus_hd", null, "coupon_hd", 25L, null, null, start - 100, deletedAt));
        assertThat(drainWebhookQueue()).isEqualTo(1);
        assertThat(discountSnapshot(workspaceId, "di_hd")).isPresent().get()
                .satisfies(row -> assertThat(row.get("deleted")).isEqualTo(true));

        // T4: the older customer.subscription.created event (T2's own effective_at = current_period_start
        // = `start`, strictly before the deletion instant `deletedAt`) arrives late and is processed last.
        webhook(connectionId, workspaceId, "evt_hd_sub", "customer.subscription.created", T0.plusSeconds(999),
                BillingFixtures.subscription(
                        "sub_hd", "cus_hd", "active", "usd", start, start + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_hd", "price_hd", 1), null));
        assertThat(drainWebhookQueue()).isEqualTo(1);

        // The discount was active at effective_at (start - 100 <= start < deletedAt): MRR is
        // materialized net of the 25% discount from the very first NEW movement, never plausible
        // full-price MRR just because the discount is deleted "now".
        List<Snapshot> snapshots = revenue.snapshots(workspaceId, "cus_hd");
        assertThat(snapshots).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.supported()).isTrue();
            assertThat(snapshot.amountMinor()).isEqualTo(1500L);
        });
        List<Movement> movements = revenue.movements(workspaceId, "cus_hd");
        assertThat(movements).extracting(Movement::type).containsExactly("NEW");
        assertThat(movements).extracting(Movement::amountMinor).containsExactly(1500L);

        // Replay (lease-expiry reclaim / explicit retry) of the exact same delayed event stays
        // deterministic -- no duplicate movements or snapshots.
        List<Movement> firstMovements = movements;
        List<Snapshot> firstSnapshots = snapshots;
        resetEventToPending(workspaceId, "evt_hd_sub");
        assertThat(drainWebhookQueue()).isEqualTo(1);
        assertThat(revenue.movements(workspaceId, "cus_hd")).isEqualTo(firstMovements);
        assertThat(revenue.snapshots(workspaceId, "cus_hd")).isEqualTo(firstSnapshots);
    }

    // ---- 12. As above, but the delete carries no provider-declared `end` -- the persisted end_at
    //          must fall back to the delete event's own provider second (never NULL, never a
    //          fabricated value), and the historical subscription recalculation must still see it. ---

    @Test
    void delayedSubscriptionCreatedAfterCustomerDiscountDeletedWithoutEndStillAppliesTheHistoricalDiscount() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_hist_del_nf", StripeConnectionMode.TEST);
        long start = T0.getEpochSecond();

        webhook(connectionId, workspaceId, "evt_hdnf_customer", "customer.created", T0,
                BillingFixtures.customer("cus_hdnf", "usd", start - 200, false, null));
        webhook(connectionId, workspaceId, "evt_hdnf_price", "price.created", T0,
                BillingFixtures.price("price_hdnf", "prod_hdnf", "usd", 1000L, "recurring", "month", 1, true));
        assertThat(drainWebhookQueue()).isEqualTo(2);

        webhook(connectionId, workspaceId, "evt_hdnf_discount", "customer.discount.created", T0.plusSeconds(10),
                BillingFixtures.discount("di_hdnf", "cus_hdnf", null, "coupon_hdnf", 50L, null, null, start - 100, null));
        assertThat(drainWebhookQueue()).isEqualTo(1);

        // "forever" duration coupon: the delete payload carries no `end` at all.
        Instant deleteReceivedAt = T0.plusSeconds(700);
        webhook(connectionId, workspaceId, "evt_hdnf_deleted", "customer.discount.deleted", deleteReceivedAt,
                BillingFixtures.discount("di_hdnf", "cus_hdnf", null, "coupon_hdnf", 50L, null, null, start - 100, null));
        assertThat(drainWebhookQueue()).isEqualTo(1);

        // No fabricated value: end_at is exactly the delete event's own provider second.
        assertThat(jdbc().sql("SELECT end_at FROM billing_discounts WHERE workspace_id = :w AND stripe_discount_id = :id")
                        .param("w", workspaceId)
                        .param("id", "di_hdnf")
                        .query((rs, rowNum) -> rs.getObject("end_at", java.time.OffsetDateTime.class))
                        .single()
                        .toEpochSecond())
                .isEqualTo(deleteReceivedAt.getEpochSecond());

        webhook(connectionId, workspaceId, "evt_hdnf_sub", "customer.subscription.created", T0.plusSeconds(999),
                BillingFixtures.subscription(
                        "sub_hdnf", "cus_hdnf", "active", "usd", start, start + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_hdnf", "price_hdnf", 1), null));
        assertThat(drainWebhookQueue()).isEqualTo(1);

        assertThat(revenue.snapshots(workspaceId, "cus_hdnf"))
                .singleElement().extracting(Snapshot::amountMinor).isEqualTo(500L);
    }

    // ---- 13. Cross-tenant isolation for the historical-delete path: a second workspace reusing the
    //          same Stripe customer/discount/subscription IDs, without any delete, keeps full price. --

    @Test
    void delayedSubscriptionAfterDeleteHistoricalResolutionIsWorkspaceScoped() {
        UUID discountedWorkspace = createWorkspace();
        UUID plainWorkspace = createWorkspace();
        UUID discountedConnection = insertActiveConnection(discountedWorkspace, "acct_hist_tenant_a", StripeConnectionMode.TEST);
        UUID plainConnection = insertActiveConnection(plainWorkspace, "acct_hist_tenant_b", StripeConnectionMode.TEST);
        long start = T0.getEpochSecond();

        webhook(discountedConnection, discountedWorkspace, "evt_ht_customer_a", "customer.created", T0,
                BillingFixtures.customer("cus_ht_shared", "usd", start - 200, false, null));
        webhook(discountedConnection, discountedWorkspace, "evt_ht_price_a", "price.created", T0,
                BillingFixtures.price("price_ht_shared", "prod_ht_a", "usd", 2000L, "recurring", "month", 1, true));
        webhook(discountedConnection, discountedWorkspace, "evt_ht_discount_a", "customer.discount.created", T0.plusSeconds(10),
                BillingFixtures.discount("di_ht_shared", "cus_ht_shared", null, "coupon_ht_a", 25L, null, null, start - 100, null));
        webhook(discountedConnection, discountedWorkspace, "evt_ht_deleted_a", "customer.discount.deleted", T0.plusSeconds(20),
                BillingFixtures.discount("di_ht_shared", "cus_ht_shared", null, "coupon_ht_a", 25L, null, null, start - 100, start + 500));
        assertThat(drainWebhookQueue()).isEqualTo(4);

        // Workspace B reuses the exact same Stripe IDs but never receives any discount event at all.
        webhook(plainConnection, plainWorkspace, "evt_ht_customer_b", "customer.created", T0,
                BillingFixtures.customer("cus_ht_shared", "usd", start - 200, false, null));
        webhook(plainConnection, plainWorkspace, "evt_ht_price_b", "price.created", T0,
                BillingFixtures.price("price_ht_shared", "prod_ht_b", "usd", 2000L, "recurring", "month", 1, true));
        assertThat(drainWebhookQueue()).isEqualTo(2);

        webhook(discountedConnection, discountedWorkspace, "evt_ht_sub_a", "customer.subscription.created", T0.plusSeconds(999),
                BillingFixtures.subscription("sub_ht_shared", "cus_ht_shared", "active", "usd", start,
                        start + 2_592_000L, false, null, null, BillingFixtures.subscriptionItem("si_ht_a", "price_ht_shared", 1), null));
        webhook(plainConnection, plainWorkspace, "evt_ht_sub_b", "customer.subscription.created", T0.plusSeconds(999),
                BillingFixtures.subscription("sub_ht_shared", "cus_ht_shared", "active", "usd", start,
                        start + 2_592_000L, false, null, null, BillingFixtures.subscriptionItem("si_ht_b", "price_ht_shared", 1), null));
        assertThat(drainWebhookQueue()).isEqualTo(2);

        assertThat(revenue.snapshots(discountedWorkspace, "cus_ht_shared"))
                .singleElement().extracting(Snapshot::amountMinor).isEqualTo(1500L);
        assertThat(revenue.snapshots(plainWorkspace, "cus_ht_shared"))
                .singleElement().extracting(Snapshot::amountMinor).isEqualTo(2000L);
    }

    // ---- 14. Historical resolution: a delayed subscription webhook whose effective_at predates a
    //          customer.discount.updated coupon switch cannot safely reuse the switched-to terms --
    //          the event fails explicitly (FAILED/UNSUPPORTED) and rolls back atomically, rather than
    //          silently applying the wrong percentage or silently dropping the discount. -------------

    @Test
    void delayedSubscriptionEffectiveBeforeCustomerDiscountCouponSwitchFailsExplicitly() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_hist_upd", StripeConnectionMode.TEST);
        long start = T0.getEpochSecond();

        webhook(connectionId, workspaceId, "evt_hu_customer", "customer.created", T0,
                BillingFixtures.customer("cus_hu", "usd", start - 200, false, null));
        webhook(connectionId, workspaceId, "evt_hu_price", "price.created", T0,
                BillingFixtures.price("price_hu", "prod_hu", "usd", 2000L, "recurring", "month", 1, true));
        assertThat(drainWebhookQueue()).isEqualTo(2);

        // T1: the discount is created at 20% off.
        webhook(connectionId, workspaceId, "evt_hu_created", "customer.discount.created", T0.plusSeconds(10),
                BillingFixtures.discount("di_hu", "cus_hu", null, "coupon_hu_v1", 20L, null, null, start - 100, null));
        assertThat(drainWebhookQueue()).isEqualTo(1);

        // T5: the SAME discount id is switched to a different coupon (40% off) with a later
        // provider-declared start -- Stripe's own semantics for customer.discount.updated ("switched
        // from one coupon to another"). billing_discounts now only knows the 40% terms.
        long switchedAt = start + 300;
        webhook(connectionId, workspaceId, "evt_hu_updated", "customer.discount.updated", T0.plusSeconds(20),
                BillingFixtures.discount("di_hu", "cus_hu", null, "coupon_hu_v2", 40L, null, null, switchedAt, null));
        assertThat(drainWebhookQueue()).isEqualTo(1);
        assertThat(discountSnapshot(workspaceId, "di_hu")).isPresent().get()
                .satisfies(row -> assertThat(((Number) row.get("percent_off")).intValue()).isEqualTo(40));

        List<Movement> movementsBefore = revenue.movements(workspaceId, "cus_hu");
        List<Snapshot> snapshotsBefore = revenue.snapshots(workspaceId, "cus_hu");

        // T4: the delayed subscription's own effective_at (current_period_start = `start`) is before
        // the switch (`switchedAt`), but at-or-after the discount's original first_seen_start_at --
        // exactly the window whose true terms (20%, not 40%) are no longer recoverable.
        webhook(connectionId, workspaceId, "evt_hu_sub", "customer.subscription.created", T0.plusSeconds(999),
                BillingFixtures.subscription(
                        "sub_hu", "cus_hu", "active", "usd", start, start + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_hu", "price_hu", 1), null));
        assertThat(drainWebhookQueue()).isEqualTo(1);

        assertThat(processingState(workspaceId, "evt_hu_sub")).isEqualTo("FAILED");
        assertThat(failureKind(workspaceId, "evt_hu_sub")).isEqualTo("UNSUPPORTED");

        // Atomic rollback: no subscription row, no new/changed MRR state for this customer -- never a
        // plausible number computed from the wrong (post-switch) coupon terms.
        assertThat(subscriptionSnapshot(workspaceId, "sub_hu")).isEmpty();
        assertThat(revenue.movements(workspaceId, "cus_hu")).isEqualTo(movementsBefore);
        assertThat(revenue.snapshots(workspaceId, "cus_hu")).isEqualTo(snapshotsBefore);
    }

    // ---- 15. Out-of-order historical evidence: a stale (version-rejected) discount write must still
    //          widen first_seen_start_at, or a delayed subscription effective between the stale
    //          event's own start and the currently-stored (newer) coupon's start would silently see
    //          no discount at all and materialize plausible full-price MRR. See ADR-0011's
    //          "Out-of-order historical evidence" amendment. -------------------------------------------

    @Test
    void staleOutOfOrderDiscountEvidenceIsNotDiscardedForADelayedSubscriptionEffectiveAt() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_ooo_evidence", StripeConnectionMode.TEST);
        long start = T0.getEpochSecond();

        webhook(connectionId, workspaceId, "evt_ooe_customer", "customer.created", T0,
                BillingFixtures.customer("cus_ooe", "usd", start - 200, false, null));
        webhook(connectionId, workspaceId, "evt_ooe_price", "price.created", T0,
                BillingFixtures.price("price_ooe", "prod_ooe", "usd", 2000L, "recurring", "month", 1, true));
        assertThat(drainWebhookQueue()).isEqualTo(2);

        // T2: the chronologically NEWER discount state (coupon_v2, 40% off, provider second T0+50) is
        // delivered and processed FIRST -- di_ooe does not exist yet, so this is a fresh insert.
        long t2Start = start + 300;
        webhook(connectionId, workspaceId, "evt_ooe_newer", "customer.discount.created", T0.plusSeconds(50),
                BillingFixtures.discount("di_ooe", "cus_ooe", null, "coupon_ooe_v2", 40L, null, null, t2Start, null));
        assertThat(drainWebhookQueue()).isEqualTo(1);

        // T1: the chronologically OLDER discount state for the SAME discount id (coupon_v1, 20% off,
        // provider second T0+5) arrives late, after the newer one already applied. The ledger's own
        // version guard rejects it -- current terms stay at 40%, exactly as ADR-0011's existing
        // out-of-order protection already proves -- but its own `start` is still evidence this
        // discount identity existed from T0+5's provider-declared instant onward.
        long t1Start = start - 100;
        webhook(connectionId, workspaceId, "evt_ooe_older", "customer.discount.updated", T0.plusSeconds(5),
                BillingFixtures.discount("di_ooe", "cus_ooe", null, "coupon_ooe_v1", 20L, null, null, t1Start, null));
        assertThat(drainWebhookQueue()).isEqualTo(1);

        assertThat(discountSnapshot(workspaceId, "di_ooe")).isPresent().get().satisfies(row ->
                assertThat(((Number) row.get("percent_off")).intValue()).isEqualTo(40));
        assertThat(firstSeenStartAt(workspaceId, "di_ooe").toEpochSecond()).isEqualTo(t1Start);

        // T4: a delayed customer.subscription.created event whose own effective_at (current_period_start
        // = `start`) falls strictly between the stale T1 evidence and the currently-stored coupon's own
        // start (t1Start < start < t2Start). Before the first_seen_start_at fix this discount would have
        // been silently invisible to activeCustomerDiscounts (first_seen_start_at was pinned at t2Start)
        // and full-price MRR would have been materialized. Now the row is found, but its only provably
        // correct terms (40%) are NOT the ones proven to exist at `start` -- so this must fail explicitly
        // rather than materialize either full-price or 40%-discounted MRR.
        webhook(connectionId, workspaceId, "evt_ooe_sub", "customer.subscription.created", T0.plusSeconds(999),
                BillingFixtures.subscription(
                        "sub_ooe", "cus_ooe", "active", "usd", start, start + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_ooe", "price_ooe", 1), null));
        assertThat(drainWebhookQueue()).isEqualTo(1);

        assertThat(processingState(workspaceId, "evt_ooe_sub")).isEqualTo("FAILED");
        assertThat(failureKind(workspaceId, "evt_ooe_sub")).isEqualTo("UNSUPPORTED");
        assertThat(subscriptionSnapshot(workspaceId, "sub_ooe")).isEmpty();
        assertThat(revenue.movements(workspaceId, "cus_ooe")).isEmpty();
        assertThat(revenue.snapshots(workspaceId, "cus_ooe")).isEmpty();

        // Replay/idempotency: retrying the delayed subscription event fails identically, not
        // flip-flopping between failure and a plausible (wrong) success.
        resetEventToPending(workspaceId, "evt_ooe_sub");
        assertThat(drainWebhookQueue()).isEqualTo(1);
        assertThat(processingState(workspaceId, "evt_ooe_sub")).isEqualTo("FAILED");
        assertThat(failureKind(workspaceId, "evt_ooe_sub")).isEqualTo("UNSUPPORTED");
        assertThat(subscriptionSnapshot(workspaceId, "sub_ooe")).isEmpty();

        // Replay/idempotency: retrying the stale discount event itself is also a deterministic no-op --
        // current terms and the learned first_seen_start_at are both unchanged, not further perturbed.
        resetEventToPending(workspaceId, "evt_ooe_older");
        assertThat(drainWebhookQueue()).isEqualTo(1);
        assertThat(discountSnapshot(workspaceId, "di_ooe")).isPresent().get().satisfies(row ->
                assertThat(((Number) row.get("percent_off")).intValue()).isEqualTo(40));
        assertThat(firstSeenStartAt(workspaceId, "di_ooe").toEpochSecond()).isEqualTo(t1Start);
    }

    // ---- 16. Retroactive invalidation: newly discovered historical evidence must not just widen
    //          first_seen_start_at -- it must invalidate any already-materialized MRR-retaining
    //          state that the new evidence proves is no longer provably safe. See ADR-0011's
    //          "Retroactive invalidation" amendment. ---------------------------------------------

    @Test
    void staleDiscountEvidenceInvalidatesAlreadyMaterializedHistoricalMrr() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_repair", StripeConnectionMode.TEST);
        long start = T0.getEpochSecond();

        webhook(connectionId, workspaceId, "evt_repair_customer", "customer.created", T0,
                BillingFixtures.customer("cus_repair", "usd", start - 200, false, null));
        webhook(connectionId, workspaceId, "evt_repair_price", "price.created", T0,
                BillingFixtures.price("price_repair", "prod_repair", "usd", 2000L, "recurring", "month", 1, true));
        assertThat(drainWebhookQueue()).isEqualTo(2);

        // T1: the subscription's own effective_at (current_period_start = `start`) is processed
        // FIRST -- no customer discount is known yet, so it correctly materializes full price.
        webhook(connectionId, workspaceId, "evt_repair_sub", "customer.subscription.created", T0.plusSeconds(1),
                BillingFixtures.subscription(
                        "sub_repair", "cus_repair", "active", "usd", start, start + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_repair", "price_repair", 1), null));
        assertThat(drainWebhookQueue()).isEqualTo(1);
        assertThat(revenue.snapshots(workspaceId, "cus_repair")).singleElement().satisfies(s -> {
            assertThat(s.supported()).isTrue();
            assertThat(s.amountMinor()).isEqualTo(2000L);
        });
        assertThat(revenue.movements(workspaceId, "cus_repair"))
                .extracting(Movement::type, Movement::amountMinor)
                .containsExactly(tuple("NEW", 2000L));

        // T2: a customer-level discount is created at 40% off, well after T1. The subscription is
        // already active, so the existing (unmodified) fan-out logic recalculates it immediately.
        long t2Start = start + 300;
        webhook(connectionId, workspaceId, "evt_repair_discount_v2", "customer.discount.created", T0.plusSeconds(50),
                BillingFixtures.discount("di_repair", "cus_repair", null, "coupon_repair_v2", 40L, null, null, t2Start, FAR_FUTURE_END));
        assertThat(drainWebhookQueue()).isEqualTo(1);
        assertThat(revenue.snapshots(workspaceId, "cus_repair")).last().satisfies(s -> {
            assertThat(s.supported()).isTrue();
            assertThat(s.amountMinor()).isEqualTo(1200L);
        });
        assertThat(revenue.movements(workspaceId, "cus_repair"))
                .extracting(Movement::type, Movement::amountMinor)
                .containsExactly(tuple("NEW", 2000L), tuple("CONTRACTION", 800L));

        // T0: the SAME discount id's original, older state (20% off, start before T1) arrives late
        // -- AFTER the subscription's full-price state at T1 was already successfully materialized,
        // and AFTER the 40%-off state at T2 already applied. It loses the current-terms version race
        // (its own provider second is earlier than T2's), but its own `start` still proves the
        // discount identity existed before T1.
        long t0Start = start - 100;
        webhook(connectionId, workspaceId, "evt_repair_discount_v1", "customer.discount.updated", T0.plusSeconds(5),
                BillingFixtures.discount("di_repair", "cus_repair", null, "coupon_repair_v1", 20L, null, null, t0Start, FAR_FUTURE_END));
        assertThat(drainWebhookQueue()).isEqualTo(1);

        // Current terms are not regressed: still 40%, not 20%.
        assertThat(discountSnapshot(workspaceId, "di_repair")).isPresent().get().satisfies(row ->
                assertThat(((Number) row.get("percent_off")).intValue()).isEqualTo(40));
        assertThat(firstSeenStartAt(workspaceId, "di_repair").toEpochSecond()).isEqualTo(t0Start);

        // The already-materialized T1 state is no longer plausible: it must not remain "supported"
        // at full price (2000), and must not be silently given the newer 40% terms either (1200) --
        // neither is provable for T1's own instant. It becomes explicitly unsupported.
        List<Snapshot> snapshotsAfterRepair = revenue.snapshots(workspaceId, "cus_repair");
        assertThat(snapshotsAfterRepair).hasSize(2);
        Snapshot t1Snapshot = snapshotsAfterRepair.get(0);
        assertThat(t1Snapshot.supported()).isFalse();
        assertThat(t1Snapshot.unsupportedReason()).isEqualTo("UNSUPPORTED_DISCOUNT");
        assertThat(t1Snapshot.effectiveAt().toEpochSecond()).isEqualTo(start);
        Snapshot t2Snapshot = snapshotsAfterRepair.get(1);
        assertThat(t2Snapshot.supported()).isTrue();
        assertThat(t2Snapshot.amountMinor()).isEqualTo(1200L);
        assertThat(t2Snapshot.effectiveAt().toEpochSecond()).isEqualTo(t2Start);

        // The stale, wrong historical NEW(2000)/CONTRACTION(800) movement sequence must not survive:
        // exactly one NEW movement remains, at T2, for the only amount ever provably known (1200).
        List<Movement> movementsAfterRepair = revenue.movements(workspaceId, "cus_repair");
        assertThat(movementsAfterRepair).extracting(Movement::type, Movement::amountMinor).containsExactly(tuple("NEW", 1200L));
        assertThat(movementsAfterRepair.get(0).effectiveAt().toEpochSecond()).isEqualTo(t2Start);

        // Replay/idempotency: retrying the stale discount event a second time changes nothing further.
        resetEventToPending(workspaceId, "evt_repair_discount_v1");
        assertThat(drainWebhookQueue()).isEqualTo(1);
        assertThat(revenue.snapshots(workspaceId, "cus_repair")).isEqualTo(snapshotsAfterRepair);
        assertThat(revenue.movements(workspaceId, "cus_repair")).isEqualTo(movementsAfterRepair);
        assertThat(discountSnapshot(workspaceId, "di_repair")).isPresent().get().satisfies(row ->
                assertThat(((Number) row.get("percent_off")).intValue()).isEqualTo(40));

        // Replay/determinism: retrying the ORIGINAL T1 subscription event now consistently fails --
        // a live recalculation at T1 can no longer prove which terms applied -- rather than
        // flip-flopping between the stale full-price success and a failure.
        resetEventToPending(workspaceId, "evt_repair_sub");
        assertThat(drainWebhookQueue()).isEqualTo(1);
        assertThat(processingState(workspaceId, "evt_repair_sub")).isEqualTo("FAILED");
        assertThat(failureKind(workspaceId, "evt_repair_sub")).isEqualTo("UNSUPPORTED");
        resetEventToPending(workspaceId, "evt_repair_sub");
        assertThat(drainWebhookQueue()).isEqualTo(1);
        assertThat(processingState(workspaceId, "evt_repair_sub")).isEqualTo("FAILED");
        assertThat(failureKind(workspaceId, "evt_repair_sub")).isEqualTo("UNSUPPORTED");
        // The corrected historical state and the 40%-discounted MRR from T2 onward are untouched by
        // these failed replay attempts (they roll back before reaching MRR recalculation at all).
        assertThat(revenue.snapshots(workspaceId, "cus_repair")).isEqualTo(snapshotsAfterRepair);
        assertThat(revenue.movements(workspaceId, "cus_repair")).isEqualTo(movementsAfterRepair);
    }

    // ---- 17. Cross-tenant isolation for retroactive invalidation -------------------------------

    @Test
    void retroactiveInvalidationIsWorkspaceScoped() {
        UUID repairedWorkspace = createWorkspace();
        UUID plainWorkspace = createWorkspace();
        UUID repairedConnection = insertActiveConnection(repairedWorkspace, "acct_repair_tenant_a", StripeConnectionMode.TEST);
        UUID plainConnection = insertActiveConnection(plainWorkspace, "acct_repair_tenant_b", StripeConnectionMode.TEST);
        long start = T0.getEpochSecond();

        for (var w : List.of(
                new Object[] {repairedWorkspace, repairedConnection, "evt_rt_a"},
                new Object[] {plainWorkspace, plainConnection, "evt_rt_b"})) {
            UUID workspaceId = (UUID) w[0];
            UUID connectionId = (UUID) w[1];
            String prefix = (String) w[2];
            webhook(connectionId, workspaceId, prefix + "_customer", "customer.created", T0,
                    BillingFixtures.customer("cus_rt_shared", "usd", start - 200, false, null));
            webhook(connectionId, workspaceId, prefix + "_price", "price.created", T0,
                    BillingFixtures.price("price_rt_shared", "prod_rt", "usd", 2000L, "recurring", "month", 1, true));
            webhook(connectionId, workspaceId, prefix + "_sub", "customer.subscription.created", T0.plusSeconds(1),
                    BillingFixtures.subscription("sub_rt_shared", "cus_rt_shared", "active", "usd", start,
                            start + 2_592_000L, false, null, null,
                            BillingFixtures.subscriptionItem("si_rt_" + prefix, "price_rt_shared", 1), null));
            webhook(connectionId, workspaceId, prefix + "_discount_v2", "customer.discount.created", T0.plusSeconds(50),
                    BillingFixtures.discount("di_rt_shared", "cus_rt_shared", null, "coupon_rt_v2", 40L, null, null,
                            start + 300, FAR_FUTURE_END));
            assertThat(drainWebhookQueue()).isEqualTo(4);
        }

        // Only the "repaired" workspace receives the stale, earlier discount evidence.
        webhook(repairedConnection, repairedWorkspace, "evt_rt_a_discount_v1", "customer.discount.updated", T0.plusSeconds(5),
                BillingFixtures.discount("di_rt_shared", "cus_rt_shared", null, "coupon_rt_v1", 20L, null, null, start - 100, FAR_FUTURE_END));
        assertThat(drainWebhookQueue()).isEqualTo(1);

        List<Snapshot> repairedSnapshots = revenue.snapshots(repairedWorkspace, "cus_rt_shared");
        assertThat(repairedSnapshots).hasSize(2);
        assertThat(repairedSnapshots.get(0).supported()).isFalse();
        assertThat(repairedSnapshots.get(0).unsupportedReason()).isEqualTo("UNSUPPORTED_DISCOUNT");
        assertThat(repairedSnapshots.get(1).supported()).isTrue();
        assertThat(repairedSnapshots.get(1).amountMinor()).isEqualTo(1200L);

        // The other workspace, sharing every Stripe ID but never receiving the stale event, keeps its
        // original, still-plausible NEW/CONTRACTION history untouched.
        List<Snapshot> plainSnapshots = revenue.snapshots(plainWorkspace, "cus_rt_shared");
        assertThat(plainSnapshots).hasSize(2);
        assertThat(plainSnapshots).allSatisfy(s -> assertThat(s.supported()).isTrue());
        assertThat(plainSnapshots.get(0).amountMinor()).isEqualTo(2000L);
        assertThat(plainSnapshots.get(1).amountMinor()).isEqualTo(1200L);
        assertThat(revenue.movements(plainWorkspace, "cus_rt_shared"))
                .extracting(Movement::type, Movement::amountMinor)
                .containsExactly(tuple("NEW", 2000L), tuple("CONTRACTION", 800L));
    }

    private java.time.OffsetDateTime firstSeenStartAt(UUID workspaceId, String stripeDiscountId) {
        return jdbc().sql(
                        "SELECT first_seen_start_at FROM billing_discounts WHERE workspace_id = :w AND stripe_discount_id = :id")
                .param("w", workspaceId)
                .param("id", stripeDiscountId)
                .query((rs, rowNum) -> rs.getObject("first_seen_start_at", java.time.OffsetDateTime.class))
                .single();
    }

    private void assertTemporalCustomerDiscountNotApplied(String suffix, long discountStart, long discountEnd) {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_temporal_" + suffix, StripeConnectionMode.TEST);
        long effective = T0.getEpochSecond();
        String customerId = "cus_temporal_" + suffix;
        String discount = BillingFixtures.discount("di_temporal_" + suffix, customerId, null,
                "coupon_temporal_" + suffix, 50L, null, null, discountStart, discountEnd);
        webhook(connectionId, workspaceId, "evt_temporal_customer_" + suffix, "customer.created", T0,
                BillingFixtures.customer(customerId, "usd", effective - 600, false, discount));
        webhook(connectionId, workspaceId, "evt_temporal_price_" + suffix, "price.created", T0,
                BillingFixtures.price("price_temporal_" + suffix, "prod_temporal", "usd", 1000L,
                        "recurring", "month", 1, true));
        webhook(connectionId, workspaceId, "evt_temporal_sub_" + suffix, "customer.subscription.created", T0,
                BillingFixtures.subscription("sub_temporal_" + suffix, customerId, "active", "usd", effective,
                        effective + 2_592_000L, false, null, null,
                        BillingFixtures.subscriptionItem("si_temporal_" + suffix, "price_temporal_" + suffix, 1), null));
        assertThat(drainWebhookQueue()).isEqualTo(3);
        assertThat(revenue.snapshots(workspaceId, customerId))
                .singleElement().extracting(Snapshot::amountMinor).isEqualTo(1000L);
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static StripeBillingObjects.ParsedSubscription subscriptionWithItem(
            String stripeSubscriptionId, String customerId, String status, String stripePriceId, int quantity) {
        return new StripeBillingObjects.ParsedSubscription(
                stripeSubscriptionId,
                customerId,
                status,
                "usd",
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(new StripeBillingObjects.ParsedSubscriptionItem(
                        stripeSubscriptionId + "-item", stripePriceId, quantity, List.of())),
                List.of());
    }

    private void webhook(UUID connectionId, UUID workspaceId, String eventId, String type, Instant at, String object) {
        insertPendingWebhookEvent(connectionId, workspaceId, StripeConnectionMode.TEST, eventId, type, at, object);
    }

    /** Simulates a lease-expiry reclaim or explicit replay: makes an already-PROCESSED row claimable again. */
    private void resetEventToPending(UUID workspaceId, String stripeEventId) {
        int updated = jdbc().sql(
                        """
                        UPDATE stripe_webhook_events
                        SET processing_state = 'PENDING', last_attempted_at = NULL, attempt_count = 0, failure_kind = NULL
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
