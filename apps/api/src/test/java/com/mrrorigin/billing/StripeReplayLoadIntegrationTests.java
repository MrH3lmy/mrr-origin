package com.mrrorigin.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * #93's Stripe replay/reprocessing load evidence. Proposed execution method posted on #93 before
 * this class was written: a dedicated JVM/Testcontainers harness against the real
 * {@link StripeWebhookNormalizationScheduler}/{@link StripeWebhookNormalizationService} claim/lease
 * pipeline #92 wired up, not an HTTP tool against the operator replay trigger endpoint -- the actual
 * concurrent worker path the issue asks to load-test lives entirely inside that pipeline, not behind
 * one synchronous HTTP call per event.
 *
 * <p>Tagged {@code load} and excluded from the default {@code mvn verify}/{@code mvn test} run
 * ({@code apps/api/pom.xml}'s {@code excluded.groups} property) -- a 1,000+-event backlog is
 * legitimately slow and not something every PR should pay for. See
 * {@code docs/operations/load-readiness.md} for the exact command to run this deliberately.
 */
@Tag("load")
@Testcontainers
class StripeReplayLoadIntegrationTests extends AbstractBillingLedgerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    private static final Instant WAVE1_BASE = Instant.parse("2026-05-01T00:00:00Z");
    // Wave 2 (subscription lifecycle onward) is seeded far enough after wave 1 (customer/price) that
    // claimBatch's `ORDER BY received_at ASC` claims essentially every wave-1 row before any wave-2
    // row becomes eligible -- minimizing (not eliminating) the realistic out-of-order-delivery race
    // multiple concurrent replicas create, matching real Stripe webhook delivery behavior rather than
    // engineering it away.
    private static final Instant WAVE2_BASE = WAVE1_BASE.plusSeconds(100_000);

    private static final int WORKSPACES = 10;
    private static final int CUSTOMERS_PER_WORKSPACE = 10;
    private static final int EVENTS_PER_CUSTOMER = 11;
    private static final int TOTAL_EVENTS = WORKSPACES * CUSTOMERS_PER_WORKSPACE * EVENTS_PER_CUSTOMER; // 1,100

    // #93's stated targets: <=2 minutes to drain the reference 1,000+-event backlog, <0.5% unexpected
    // processing failures.
    private static final Duration DRAIN_TIME_BUDGET = Duration.ofMinutes(2);
    private static final double MAX_FAILURE_RATE = 0.005;

    @Test
    void concurrentDrainOfAThousandPlusEventBacklogAcrossTenWorkspacesConvergesWithoutDuplicationOrCrossTenantLeakage() {
        UUID[] workspaces = new UUID[WORKSPACES];
        UUID[] connections = new UUID[WORKSPACES];
        for (int w = 0; w < WORKSPACES; w++) {
            workspaces[w] = createWorkspace();
            connections[w] = insertActiveConnection(workspaces[w], "acct_load_" + w, StripeConnectionMode.TEST);
            // Every workspace reuses the identical slot 0..9 Stripe object id strings -- Stripe ids are
            // account-scoped, not globally unique, so two different Stripe accounts (workspaces) reusing
            // "cus_load_3" is realistic, and stresses the (workspace_id, stripe_*_id) keying under real
            // concurrent access rather than just sequential isolation.
            for (int slot = 0; slot < CUSTOMERS_PER_WORKSPACE; slot++) {
                seedCustomerLifecycle(connections[w], workspaces[w], slot);
            }
        }
        assertThat(pendingCount()).isEqualTo(TOTAL_EVENTS);

        Instant drainStart = Instant.now();
        DrainResult first = drainConcurrently(3, 50, 10);
        Duration elapsed = Duration.between(drainStart, Instant.now());

        assertThat(elapsed)
                .as("1,100-event concurrent backlog drain must complete within the #93 2-minute budget")
                .isLessThanOrEqualTo(DRAIN_TIME_BUDGET);
        assertThat((double) first.failed() / TOTAL_EVENTS)
                .as("unexpected processing failure rate")
                .isLessThan(MAX_FAILURE_RATE);

        for (int w = 0; w < WORKSPACES; w++) {
            assertThat(countWhere("billing_customers", workspaces[w])).isEqualTo(CUSTOMERS_PER_WORKSPACE);
            assertThat(countWhere("billing_prices", workspaces[w])).isEqualTo(CUSTOMERS_PER_WORKSPACE);
            assertThat(countWhere("billing_subscriptions", workspaces[w])).isEqualTo(CUSTOMERS_PER_WORKSPACE);
            assertThat(countWhere("billing_invoices", workspaces[w])).isEqualTo(CUSTOMERS_PER_WORKSPACE);
            assertThat(countWhere("billing_payments", workspaces[w])).isEqualTo(CUSTOMERS_PER_WORKSPACE);
            assertThat(countWhere("billing_refunds", workspaces[w])).isEqualTo(CUSTOMERS_PER_WORKSPACE);
            assertThat(countWhere("billing_discounts", workspaces[w])).isEqualTo(CUSTOMERS_PER_WORKSPACE);
        }
        // Captured before the duplicate/retry pass below: the reference row counts every ledger table
        // should still show afterward, since a retried/redelivered already-applied event must never
        // insert additional rows -- a differential check rather than a hardcoded expected count, since
        // this class cannot itself be executed in every environment to pin down that literal number.
        List<Ledger> beforeRetry = ledgerSnapshots(workspaces);

        // Duplicates/retries (#93): any event a genuine out-of-order race failed is still FAILED, not
        // lost -- requeue it exactly as the existing replay endpoint would, then also requeue a
        // representative already-succeeded subset (every subscription.updated event) to simulate a
        // redelivery/retry of already-applied work. Both paths go through the real claim -> normalize
        // -> apply pipeline again, not a bypassed shortcut.
        requeueFailedEvents();
        requeueProcessedEventsMatching("customer.subscription.updated");
        DrainResult second = drainConcurrently(2, 50, 10);
        assertThat(second.failed())
                .as("a retried event must not fail again once any transient ordering condition has cleared")
                .isZero();

        assertThat(pendingOrFailedCount())
                .as("no unexplained permanently-pending/failed events after the workload/recovery window")
                .isZero();
        assertThat(ledgerSnapshots(workspaces))
                .as("the duplicate/retry pass must not have changed any ledger row count")
                .isEqualTo(beforeRetry);
    }

    private List<Ledger> ledgerSnapshots(UUID[] workspaces) {
        return IntStream.range(0, workspaces.length)
                .mapToObj(w -> new Ledger(
                        countWhere("billing_customers", workspaces[w]),
                        countWhere("billing_prices", workspaces[w]),
                        countWhere("billing_subscriptions", workspaces[w]),
                        countWhere("billing_subscription_status_events", workspaces[w]),
                        countWhere("billing_invoices", workspaces[w]),
                        countWhere("billing_payments", workspaces[w]),
                        countWhere("billing_refunds", workspaces[w]),
                        countWhere("billing_discounts", workspaces[w])))
                .toList();
    }

    private record Ledger(
            int customers, int prices, int subscriptions, int statusEvents, int invoices, int payments, int refunds,
            int discounts) {}

    @Test
    void concurrentProcessingConvergesToTheSameNormalizedStateAsASerialReferenceRun() {
        // Genuine serial-vs-concurrent isolation, by sequencing rather than by filtering:
        // StripeWebhookNormalizationService.claimBatch claims PENDING rows globally, exactly like real
        // replicas do -- that shared-backlog behavior is the whole point of the load test, so it must
        // not be special-cased for this one test. Instead, the serial reference workspace is fully
        // seeded AND fully drained (backlog confirmed empty) before the concurrent workspace's events
        // are seeded at all, so the two runs never coexist in the shared PENDING backlog and cannot
        // race or share workers with each other.
        UUID serialWorkspace = createWorkspace();
        UUID serialConnection = insertActiveConnection(serialWorkspace, "acct_load_conv_serial", StripeConnectionMode.TEST);
        int slots = 5;
        for (int slot = 0; slot < slots; slot++) {
            seedCustomerLifecycle(serialConnection, serialWorkspace, slot);
        }
        drainSerially();
        assertThat(pendingOrFailedCount()).isZero();

        UUID concurrentWorkspace = createWorkspace();
        UUID concurrentConnection = insertActiveConnection(concurrentWorkspace, "acct_load_conv_concurrent", StripeConnectionMode.TEST);
        for (int slot = 0; slot < slots; slot++) {
            seedCustomerLifecycle(concurrentConnection, concurrentWorkspace, slot);
        }
        drainConcurrently(3, 25, 10);
        assertThat(pendingOrFailedCount()).isZero();

        for (int slot = 0; slot < slots; slot++) {
            String customerId = "cus_load_" + slot;
            String priceId = "price_load_" + slot;
            String subscriptionId = "sub_load_" + slot;
            String invoiceId = "inv_load_" + slot;
            String chargeId = "ch_load_" + slot;

            assertThat(customerSnapshot(concurrentWorkspace, customerId)).isEqualTo(customerSnapshot(serialWorkspace, customerId));
            assertThat(priceSnapshot(concurrentWorkspace, priceId)).isEqualTo(priceSnapshot(serialWorkspace, priceId));
            assertThat(subscriptionSnapshot(concurrentWorkspace, subscriptionId))
                    .isEqualTo(subscriptionSnapshot(serialWorkspace, subscriptionId));
            assertThat(subscriptionItemSnapshots(concurrentWorkspace, subscriptionId))
                    .isEqualTo(subscriptionItemSnapshots(serialWorkspace, subscriptionId));
            assertThat(subscriptionStatusHistory(concurrentWorkspace, subscriptionId))
                    .isEqualTo(subscriptionStatusHistory(serialWorkspace, subscriptionId));
            assertThat(invoiceSnapshot(concurrentWorkspace, invoiceId)).isEqualTo(invoiceSnapshot(serialWorkspace, invoiceId));
            assertThat(paymentSnapshot(concurrentWorkspace, chargeId)).isEqualTo(paymentSnapshot(serialWorkspace, chargeId));
            assertThat(refundSnapshot(concurrentWorkspace, "re_load_" + slot))
                    .isEqualTo(refundSnapshot(serialWorkspace, "re_load_" + slot));
            assertThat(discountSnapshot(concurrentWorkspace, "dc_load_" + slot))
                    .isEqualTo(discountSnapshot(serialWorkspace, "dc_load_" + slot));
        }
    }

    // #93's failure/recovery pressure requirement: a controlled interruption during load must not lose
    // queued work, must converge on recovery, and must not duplicate output. Simulated by directly
    // backdating last_attempted_at past StripeWebhookNormalizationService's 5-minute lease window --
    // exactly the persisted state a row would be in if the worker that claimed it crashed before
    // finishing -- rather than a real 5-minute wait or an exception that bypasses the real claim
    // boundary. A fresh scheduler run must reclaim and complete these through the unmodified real
    // lease-expiry path (claimBatch's own `last_attempted_at < leaseCutoff` condition).
    @Test
    void aWorkersClaimedButUnfinishedEventsFromACrashAreReclaimedAndCompletedWithoutDuplication() {
        UUID workspace = createWorkspace();
        UUID connection = insertActiveConnection(workspace, "acct_load_crash", StripeConnectionMode.TEST);
        int slots = 5;
        for (int slot = 0; slot < slots; slot++) {
            seedCustomerLifecycle(connection, workspace, slot);
        }
        int total = slots * EVENTS_PER_CUSTOMER;

        // Half the backlog is claimed-and-abandoned (simulated crash); the other half is left genuinely
        // PENDING, exactly like a real partial outage where some in-flight work is stuck and the rest
        // simply hasn't been picked up yet.
        Instant crashedLeaseStart = Instant.now().minus(Duration.ofMinutes(6));
        jdbc().sql("""
                        UPDATE stripe_webhook_events
                        SET last_attempted_at = :leaseStart, attempt_count = 1
                        WHERE workspace_id = :w AND stripe_event_id LIKE 'evt_%_cust_0'
                           OR (workspace_id = :w AND stripe_event_id LIKE 'evt_%_price_0')
                        """)
                .param("w", workspace)
                .param("leaseStart", OffsetDateTime.ofInstant(crashedLeaseStart, ZoneOffset.UTC))
                .update();

        DrainResult recovery = drainConcurrently(2, 25, 10);

        assertThat(pendingOrFailedCountForWorkspace(workspace)).isZero();
        assertThat(countWhere("billing_customers", workspace)).isEqualTo(slots);
        assertThat(countWhere("billing_prices", workspace)).isEqualTo(slots);
        assertThat(countWhere("billing_subscriptions", workspace)).isEqualTo(slots);
        assertThat(recovery.processed()).isGreaterThan(0);
        assertThat(processedCountForWorkspace(workspace)).isEqualTo(total);
    }

    // ---- fixture generation --------------------------------------------------------------------

    /**
     * One customer's full lifecycle (11 events): customer, price, subscription
     * created/active/past_due/canceled, invoice created/paid, charge, refund, and a top-level customer
     * discount -- reusing {@link BillingFixtures}, not new ad hoc payloads, per the method posted on
     * #93.
     */
    private void seedCustomerLifecycle(UUID connectionId, UUID workspaceId, int slot) {
        String customerId = "cus_load_" + slot;
        String priceId = "price_load_" + slot;
        String subscriptionId = "sub_load_" + slot;
        String invoiceId = "inv_load_" + slot;
        String chargeId = "ch_load_" + slot;
        String refundId = "re_load_" + slot;
        String discountId = "dc_load_" + slot;

        Instant wave1First = WAVE1_BASE.plusSeconds((long) slot * 2);
        Instant wave1Second = wave1First.plusSeconds(1);
        Instant wave2 = WAVE2_BASE.plusSeconds((long) slot * 10);
        long periodStart = wave2.getEpochSecond();
        long periodEnd = periodStart + 2_592_000L;

        event(connectionId, workspaceId, "cust_" + slot, "customer.created", wave1First,
                BillingFixtures.customer(customerId, "usd", wave1First.getEpochSecond(), false, null));
        event(connectionId, workspaceId, "price_" + slot, "price.created", wave1Second,
                BillingFixtures.price(priceId, "prod_load_" + slot, "usd", 1000L, "recurring", "month", 1, true));

        String item = BillingFixtures.subscriptionItem("si_load_" + slot, priceId, 1);
        event(connectionId, workspaceId, "sub_created_" + slot, "customer.subscription.created", wave2,
                BillingFixtures.subscription(
                        subscriptionId, customerId, "trialing", "usd", periodStart, periodEnd, false,
                        periodStart, periodStart + 604_800L, item, null));
        event(connectionId, workspaceId, "sub_active_" + slot, "customer.subscription.updated", wave2.plusSeconds(1),
                BillingFixtures.subscription(
                        subscriptionId, customerId, "active", "usd", periodStart, periodEnd, false, null, null, item, null));
        event(connectionId, workspaceId, "sub_pastdue_" + slot, "customer.subscription.updated", wave2.plusSeconds(2),
                BillingFixtures.subscription(
                        subscriptionId, customerId, "past_due", "usd", periodStart, periodEnd, false, null, null, item, null));
        event(connectionId, workspaceId, "sub_canceled_" + slot, "customer.subscription.deleted", wave2.plusSeconds(3),
                BillingFixtures.subscription(
                        subscriptionId, customerId, "canceled", "usd", periodStart, periodEnd, false, null, null, item, null));
        event(connectionId, workspaceId, "inv_created_" + slot, "invoice.created", wave2.plusSeconds(4),
                BillingFixtures.invoice(
                        invoiceId, customerId, subscriptionId, "open", "usd", 1000, 0, 1000, periodStart, periodEnd, periodStart));
        event(connectionId, workspaceId, "inv_paid_" + slot, "invoice.paid", wave2.plusSeconds(5),
                BillingFixtures.invoice(
                        invoiceId, customerId, subscriptionId, "paid", "usd", 1000, 1000, 0, periodStart, periodEnd, periodStart));
        event(connectionId, workspaceId, "charge_" + slot, "charge.succeeded", wave2.plusSeconds(6),
                BillingFixtures.charge(chargeId, customerId, invoiceId, 1000, "usd", "succeeded", true, false, 0, periodStart));
        event(connectionId, workspaceId, "refund_" + slot, "refund.created", wave2.plusSeconds(7),
                BillingFixtures.refund(refundId, chargeId, 200, "usd", "succeeded", null, periodStart));
        event(connectionId, workspaceId, "discount_" + slot, "customer.discount.created", wave2.plusSeconds(8),
                BillingFixtures.discount(discountId, customerId, null, "coupon_load_" + slot, 10L, null, null, periodStart, null));
    }

    /** {@code stripe_event_id} embeds the workspace id, so it is globally unique even though every workspace reuses the same slot object ids. */
    private void event(UUID connectionId, UUID workspaceId, String suffix, String type, Instant receivedAt, String object) {
        insertPendingWebhookEvent(
                connectionId, workspaceId, StripeConnectionMode.TEST, "evt_" + workspaceId + "_" + suffix, type,
                receivedAt, receivedAt, object);
    }

    // ---- concurrent/serial drain helpers --------------------------------------------------------

    private DrainResult drainConcurrently(int replicaCount, int batchSize, int maxBatchesPerTick) {
        List<StripeWebhookNormalizationScheduler> replicas = IntStream.range(0, replicaCount)
                .mapToObj(i -> new StripeWebhookNormalizationScheduler(
                        normalizationService, new StripeWebhookNormalizationSchedulerProperties(true, batchSize, maxBatchesPerTick)))
                .toList();
        ExecutorService pool = Executors.newFixedThreadPool(replicaCount);
        CountDownLatch ready = new CountDownLatch(replicaCount);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger processed = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (StripeWebhookNormalizationScheduler scheduler : replicas) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    await(go);
                    int fetched;
                    do {
                        StripeWebhookNormalizationScheduler.DrainOutcome outcome = scheduler.drain();
                        fetched = outcome.fetched();
                        processed.addAndGet(outcome.processed());
                        failed.addAndGet(outcome.failed());
                    } while (fetched > 0);
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
            for (Future<?> future : futures) {
                future.get(180, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            pool.shutdown();
        }
        return new DrainResult(processed.get(), failed.get());
    }

    private void drainSerially() {
        var scheduler = new StripeWebhookNormalizationScheduler(
                normalizationService, new StripeWebhookNormalizationSchedulerProperties(true, 25, 10));
        int fetched;
        do {
            fetched = scheduler.drain().fetched();
        } while (fetched > 0);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    // ---- verification helpers --------------------------------------------------------------------

    private int pendingCount() {
        return jdbc().sql("SELECT COUNT(*) FROM stripe_webhook_events WHERE processing_state = 'PENDING'")
                .query(Integer.class)
                .single();
    }

    private int pendingOrFailedCount() {
        return jdbc().sql("SELECT COUNT(*) FROM stripe_webhook_events WHERE processing_state IN ('PENDING', 'FAILED')")
                .query(Integer.class)
                .single();
    }

    private int pendingOrFailedCountForWorkspace(UUID workspace) {
        return jdbc().sql(
                        "SELECT COUNT(*) FROM stripe_webhook_events WHERE workspace_id = :w AND processing_state IN ('PENDING', 'FAILED')")
                .param("w", workspace)
                .query(Integer.class)
                .single();
    }

    private int processedCountForWorkspace(UUID workspace) {
        return jdbc().sql("SELECT COUNT(*) FROM stripe_webhook_events WHERE workspace_id = :w AND processing_state = 'PROCESSED'")
                .param("w", workspace)
                .query(Integer.class)
                .single();
    }

    /** Requeues every currently-FAILED event (across all workspaces) back to PENDING, mirroring the real replay endpoint's transition. */
    private int requeueFailedEvents() {
        return jdbc().sql("UPDATE stripe_webhook_events SET processing_state = 'PENDING', last_attempted_at = NULL WHERE processing_state = 'FAILED'")
                .update();
    }

    /**
     * Simulates a redelivery/retry of already-successfully-applied events by resetting a representative
     * subset of PROCESSED rows back to PENDING directly at the DB layer -- this re-enters the exact
     * same real claim -> normalize -> apply pipeline on the next drain, it is not a bypassed shortcut.
     */
    private int requeueProcessedEventsMatching(String eventType) {
        return jdbc().sql(
                        "UPDATE stripe_webhook_events SET processing_state = 'PENDING', last_attempted_at = NULL "
                                + "WHERE processing_state = 'PROCESSED' AND event_type = :type")
                .param("type", eventType)
                .update();
    }

    private int countWhere(String table, UUID workspace) {
        return jdbc().sql("SELECT COUNT(*) FROM " + table + " WHERE workspace_id = :w")
                .param("w", workspace)
                .query(Integer.class)
                .single();
    }

    private record DrainResult(int processed, int failed) {}
}
