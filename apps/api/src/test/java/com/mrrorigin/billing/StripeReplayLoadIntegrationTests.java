package com.mrrorigin.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

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
        // Architect decision on #93, 2026-08-24: StripeWebhookNormalizationService.markFailed
        // classifies any non-StripeBillingNormalizationException as TRANSIENT (StripeWebhookFailureKind)
        // -- "the same event may succeed on a later replay alone" -- so asserting zero pending/failed
        // immediately after one concurrent pass with no requeue is stricter than the application's own
        // documented replay contract. This bounded, TRANSIENT-only requeue-and-retry is that contract
        // exercised for real, not a relaxed assertion: a non-TRANSIENT failure (a genuine normalization
        // bug) still fails the test immediately, and a round that makes no progress still fails loudly
        // rather than looping forever.
        convergeThroughBoundedTransientReplay(concurrentWorkspace, 3, 25, 10);
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

        // 5 of this workspace's 55 events (not "half", a corrected count per #93 architect review) are
        // stamped as claimed-but-abandoned (simulated crash); the rest of the backlog is left genuinely
        // untouched (last_attempted_at NULL), like a real partial outage where some in-flight work is
        // stuck and the rest simply hasn't been picked up yet. Deliberately the terminal
        // customer.discount.created event of each slot (WAVE2_BASE.plusSeconds(8), the latest
        // receivedAt in the chain, seedCustomerLifecycle's own comment on WAVE2_BASE) -- nothing else
        // in the seeded fixture set depends on it, unlike e.g. customer.created/price.created, which the
        // subscription/invoice/charge chain for every slot requires normalized first. Leasing those
        // instead would make claimBatch's ORDER BY received_at ASC hand dependent events to workers
        // before their prerequisite rows exist, producing real (not lease-related) processing failures
        // and defeating the point of this test -- confirmed by hitting exactly that while drafting this.
        List<String> leasedEventIds = leasedSubsetEventIds(workspace, slots);
        assertThat(leasedEventIds).hasSize(slots);

        // First prove the lease boundary itself is enforced, not merely that *something* eventually
        // reclaims an already-expired timestamp (which would pass even with claimBatch's
        // `last_attempted_at < leaseCutoff` condition removed entirely): stamp a lease that started
        // moments ago, well inside StripeWebhookNormalizationService's 5-minute LEASE_DURATION, and
        // confirm a drain leaves that subset alone while still completing everything else.
        stampLease(workspace, leasedEventIds, Instant.now().minus(Duration.ofMinutes(1)));
        drainConcurrently(2, 25, 10);
        // The same concurrent-claim TRANSIENT contention convergeThroughBoundedTransientReplay handles
        // for the convergence test can also hit the *other* 50 events here, unrelated to the lease
        // boundary this step is proving -- bounded-retry those away first so the assertions below check
        // lease semantics specifically, not incidentally fail on a pre-existing, already-reported,
        // unfixed race. This never touches the 5 intentionally-still-PENDING leased rows: it only ever
        // requeues rows that are actually FAILED, and claimBatch's lease boundary keeps those 5 PENDING
        // throughout this step.
        retryTransientFailuresToZero(workspace, 2, 25, 10);
        assertThat(pendingOrFailedCountForWorkspace(workspace))
                .as("only the recently-leased subset should still be outstanding -- its lease has not expired yet")
                .isEqualTo(leasedEventIds.size());
        assertThat(processedCountForWorkspace(workspace)).isEqualTo(total - leasedEventIds.size());

        // Now simulate the crash actually going stale: backdate the same rows' lease past
        // LEASE_DURATION -- exactly the persisted state a row would be in if the worker that claimed it
        // crashed before finishing -- without waiting 5 real minutes or throwing a bypass exception
        // around the claim boundary.
        stampLease(workspace, leasedEventIds, Instant.now().minus(Duration.ofMinutes(6)));
        drainConcurrently(2, 25, 10);
        convergeThroughBoundedTransientReplay(workspace, 2, 25, 10);

        assertThat(countWhere("billing_customers", workspace)).isEqualTo(slots);
        assertThat(countWhere("billing_prices", workspace)).isEqualTo(slots);
        assertThat(countWhere("billing_subscriptions", workspace)).isEqualTo(slots);
        assertThat(processedCountForWorkspace(workspace)).isEqualTo(total);
    }

    private List<String> leasedSubsetEventIds(UUID workspace, int slots) {
        List<String> ids = new ArrayList<>();
        for (int slot = 0; slot < slots; slot++) {
            ids.add("evt_" + workspace + "_discount_" + slot);
        }
        return ids;
    }

    private void stampLease(UUID workspace, List<String> eventIds, Instant leaseStart) {
        int updated = jdbc().sql("""
                        UPDATE stripe_webhook_events
                        SET last_attempted_at = :leaseStart, attempt_count = attempt_count + 1
                        WHERE workspace_id = :w AND stripe_event_id IN (:eventIds)
                        """)
                .param("w", workspace)
                .param("eventIds", eventIds)
                .param("leaseStart", OffsetDateTime.ofInstant(leaseStart, ZoneOffset.UTC))
                .update();
        assertThat(updated).isEqualTo(eventIds.size());
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

    private static final int MAX_TRANSIENT_RETRY_ROUNDS = 3;

    /**
     * Requeues and redrains a workspace's leftover FAILED rows through the real claim -> normalize ->
     * apply pipeline (no bypass), up to {@link #MAX_TRANSIENT_RETRY_ROUNDS} times, until none remain.
     * Only {@code TRANSIENT} failures (see {@link StripeWebhookFailureKind}) are eligible -- an
     * {@code UNSUPPORTED}/{@code LEGACY} failure means a genuine normalization bug, not a replayable
     * race, and fails the test immediately rather than being requeued. A round that does not reduce the
     * stuck count also fails the test immediately, so a truly stuck backlog cannot be masked as "just
     * needs one more retry." Never touches rows that are genuinely PENDING (e.g. intentionally
     * lease-skipped in the crash/interruption test below) -- it only ever requeues rows that are
     * actually FAILED.
     */
    private void retryTransientFailuresToZero(UUID workspace, int replicaCount, int batchSize, int maxBatchesPerTick) {
        int previousStuck = Integer.MAX_VALUE;
        for (int round = 1; round <= MAX_TRANSIENT_RETRY_ROUNDS; round++) {
            List<FailedEvent> failed = failedEventsForWorkspace(workspace);
            if (failed.isEmpty()) {
                return;
            }
            List<FailedEvent> nonTransient = failed.stream()
                    .filter(e -> !"TRANSIENT".equals(e.failureKind()))
                    .toList();
            assertThat(nonTransient)
                    .as("only TRANSIENT failures are eligible for bounded replay -- UNSUPPORTED/LEGACY indicates a real normalization bug")
                    .isEmpty();
            System.out.printf(
                    "TRANSIENT retry round %d: %d failure(s) for workspace %s, requeuing and redraining: %s%n",
                    round, failed.size(), workspace, failed);
            if (failed.size() >= previousStuck) {
                fail(
                        "bounded TRANSIENT replay made no progress: round %d still has %d stuck event(s) (previous round had %d): %s"
                                .formatted(round, failed.size(), previousStuck, failed));
            }
            previousStuck = failed.size();
            requeueFailedEventsForWorkspace(workspace);
            drainConcurrently(replicaCount, batchSize, maxBatchesPerTick);
        }
        assertThat(failedEventsForWorkspace(workspace))
                .as("still FAILED after %d bounded TRANSIENT replay round(s)", MAX_TRANSIENT_RETRY_ROUNDS)
                .isEmpty();
    }

    /** {@link #retryTransientFailuresToZero}, then asserts the workspace has nothing pending/failed at all -- for callers that expect full convergence, not a partial (e.g. lease-boundary) outstanding subset. */
    private void convergeThroughBoundedTransientReplay(UUID workspace, int replicaCount, int batchSize, int maxBatchesPerTick) {
        retryTransientFailuresToZero(workspace, replicaCount, batchSize, maxBatchesPerTick);
        assertThat(pendingOrFailedCountForWorkspace(workspace))
                .as("still pending/failed after bounded TRANSIENT replay")
                .isZero();
    }

    private record FailedEvent(String stripeEventId, String failureKind, String lastError) {}

    private List<FailedEvent> failedEventsForWorkspace(UUID workspace) {
        return jdbc().sql(
                        "SELECT stripe_event_id, failure_kind, last_error FROM stripe_webhook_events "
                                + "WHERE workspace_id = :w AND processing_state = 'FAILED'")
                .param("w", workspace)
                .query((rs, rowNum) -> new FailedEvent(
                        rs.getString("stripe_event_id"), rs.getString("failure_kind"), rs.getString("last_error")))
                .list();
    }

    /** Scoped sibling of {@link #requeueFailedEvents()}: only this workspace's FAILED rows, mirroring the real replay endpoint's transition. */
    private int requeueFailedEventsForWorkspace(UUID workspace) {
        // failure_kind/last_error must be cleared in the same statement as the processing_state flip --
        // chk_stripe_webhook_events_failure_kind_consistency (V12) requires
        // (processing_state = 'FAILED') = (failure_kind IS NOT NULL), and StripeWebhookReplayService's
        // real replay transition clears both together for exactly this reason.
        return jdbc().sql(
                        "UPDATE stripe_webhook_events SET processing_state = 'PENDING', failure_kind = NULL, last_error = NULL, last_attempted_at = NULL "
                                + "WHERE workspace_id = :w AND processing_state = 'FAILED'")
                .param("w", workspace)
                .update();
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
        // See requeueFailedEventsForWorkspace's comment: failure_kind/last_error must be cleared in the
        // same statement as the processing_state flip, or chk_stripe_webhook_events_failure_kind_consistency
        // (V12) rejects the row. Latent bug fixed alongside the scoped sibling above -- this whole-table
        // version had never actually requeued a genuinely FAILED row in a run that exercised this line
        // until now.
        return jdbc().sql(
                        "UPDATE stripe_webhook_events SET processing_state = 'PENDING', failure_kind = NULL, last_error = NULL, last_attempted_at = NULL WHERE processing_state = 'FAILED'")
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
