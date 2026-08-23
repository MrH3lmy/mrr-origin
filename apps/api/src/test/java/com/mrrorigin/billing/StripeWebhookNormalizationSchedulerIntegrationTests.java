package com.mrrorigin.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * #92's bounded background driver for {@link StripeWebhookNormalizationService#processBatch}. Every
 * scenario drives the scheduler's {@code tick()}/{@code drain()} methods directly and deterministically
 * -- never a real {@code @Scheduled} firing or a sleep -- matching this repo's existing
 * scheduled-service test convention ({@code WeeklySummaryDeliveryIntegrationTests}).
 */
@Testcontainers
class StripeWebhookNormalizationSchedulerIntegrationTests extends AbstractBillingLedgerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    private static final Instant BASE = Instant.parse("2026-04-01T00:00:00Z");

    @Autowired
    private StripeWebhookReplayService replayService;

    // ---- Bounded per tick, convergent across ticks -------------------------------------------------

    @Test
    void boundedTickDrainsAtMostConfiguredWorkAndALaterTickContinuesDraining() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_bounded", StripeConnectionMode.TEST);
        int total = 15;
        for (int i = 0; i < total; i++) {
            insertPendingWebhookEvent(
                    connectionId, workspaceId, StripeConnectionMode.TEST, "evt_bounded_" + i, "customer.created",
                    BASE.plusSeconds(i), BillingFixtures.customer("cus_bounded_" + i, "usd", BASE.getEpochSecond(), false, null));
        }

        // 2 batches of 5 = 10 events per tick; strictly less than the 15 pending, so this tick must
        // not drain the whole backlog in one bounded invocation.
        var scheduler = new StripeWebhookNormalizationScheduler(
                normalizationService, new StripeWebhookNormalizationSchedulerProperties(true, 5, 2));

        StripeWebhookNormalizationScheduler.DrainOutcome first = scheduler.drain();
        assertThat(first.batchesRun()).isEqualTo(2);
        assertThat(first.fetched()).isEqualTo(10);
        assertThat(pendingCount(workspaceId)).isEqualTo(5);

        StripeWebhookNormalizationScheduler.DrainOutcome second = scheduler.drain();
        assertThat(second.fetched()).isEqualTo(5);
        // The remaining backlog (5) exactly equals batchSize, so the first batch call in this tick
        // returns a full batch (not yet detectably "drained"); one further batch call returning 0
        // confirms it. Still bounded by maxBatchesPerTick (2), just not short-circuited to 1 here.
        assertThat(second.batchesRun()).isEqualTo(2);
        assertThat(pendingCount(workspaceId)).isZero();
        assertThat(processedCount(workspaceId)).isEqualTo(total);
    }

    // ---- Multiple replicas / overlapping ticks ------------------------------------------------------

    @Test
    void twoConcurrentTicksSimulatingTwoReplicasProcessEveryEventExactlyOnce() throws Exception {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_concurrent", StripeConnectionMode.TEST);
        int total = 24;
        for (int i = 0; i < total; i++) {
            insertPendingWebhookEvent(
                    connectionId, workspaceId, StripeConnectionMode.TEST, "evt_concurrent_" + i, "customer.created",
                    BASE.plusSeconds(i), BillingFixtures.customer("cus_concurrent_" + i, "usd", BASE.getEpochSecond(), false, null));
        }

        // Two independently-constructed schedulers stand in for two application replicas, both
        // pointed at the same database, both draining concurrently.
        var replicaA = new StripeWebhookNormalizationScheduler(
                normalizationService, new StripeWebhookNormalizationSchedulerProperties(true, 5, 10));
        var replicaB = new StripeWebhookNormalizationScheduler(
                normalizationService, new StripeWebhookNormalizationSchedulerProperties(true, 5, 10));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Callable<Integer>> tasks = List.of(
                    drainToCompletion(replicaA, ready, go), drainToCompletion(replicaB, ready, go));
            List<Future<Integer>> futures = tasks.stream().map(pool::submit).toList();
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
            int totalProcessed = 0;
            for (Future<Integer> future : futures) {
                totalProcessed += future.get(30, TimeUnit.SECONDS);
            }
            assertThat(totalProcessed).isEqualTo(total);
        } finally {
            pool.shutdown();
        }

        assertThat(pendingCount(workspaceId)).isZero();
        assertThat(processedCount(workspaceId)).isEqualTo(total);
        // No duplicate ledger effect: exactly one billing_customers row per distinct customer id.
        assertThat(jdbc().sql("SELECT COUNT(*) FROM billing_customers WHERE workspace_id = :w")
                        .param("w", workspaceId)
                        .query(Integer.class)
                        .single())
                .isEqualTo(total);
    }

    private Callable<Integer> drainToCompletion(
            StripeWebhookNormalizationScheduler scheduler, CountDownLatch ready, CountDownLatch go) {
        return () -> {
            ready.countDown();
            await(go);
            int fetched;
            int processed = 0;
            do {
                StripeWebhookNormalizationScheduler.DrainOutcome outcome = scheduler.drain();
                fetched = outcome.fetched();
                processed += outcome.processed() + outcome.skipped();
            } while (fetched > 0);
            return processed;
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    // ---- Retry/replay idempotency --------------------------------------------------------------

    @Test
    void repeatedReplayCyclesDrainedByLaterTicksConvergeWithoutDuplicatingLedgerRows() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_replay", StripeConnectionMode.TEST);
        String customer = BillingFixtures.customer("cus_replay_idem", "usd", BASE.getEpochSecond(), false, null);
        insertPendingWebhookEvent(
                connectionId, workspaceId, StripeConnectionMode.TEST, "evt_replay_idem_cust", "customer.created", BASE, customer);
        // References an unseeded price, so this event fails TRANSIENT (BillingMrrRecalculationAdapter
        // .requireResolvedPrices) every tick until the price is seeded -- deterministic, no Stripe API
        // call involved.
        String item = BillingFixtures.subscriptionItem("si_replay_idem", "price_replay_idem_unresolved", 1);
        String subscription = BillingFixtures.subscription(
                "sub_replay_idem", "cus_replay_idem", "active", "usd", BASE.getEpochSecond(),
                BASE.plusSeconds(2_592_000).getEpochSecond(), false, null, null, item, null);
        insertPendingWebhookEvent(
                connectionId, workspaceId, StripeConnectionMode.TEST, "evt_replay_idem_sub", "customer.subscription.created",
                BASE.plusSeconds(1), subscription);

        var scheduler = new StripeWebhookNormalizationScheduler(
                normalizationService, new StripeWebhookNormalizationSchedulerProperties(true, 50, 10));
        scheduler.drain();
        UUID subEventId = eventIdFor(workspaceId, "evt_replay_idem_sub");
        assertThat(processingState(subEventId)).isEqualTo("FAILED");

        // First replay/retry cycle: still fails, because the price is still unresolved.
        assertThat(replayService.replayEvent(workspaceId, subEventId)).isEqualTo(StripeWebhookReplayService.ReplayOutcome.REPLAYED);
        scheduler.drain();
        assertThat(processingState(subEventId)).isEqualTo("FAILED");
        assertThat(replayCount(subEventId)).isEqualTo(1);

        // Second replay/retry cycle: still fails, same reason -- proves repeated retries stay safe,
        // not just a single retry.
        assertThat(replayService.replayEvent(workspaceId, subEventId)).isEqualTo(StripeWebhookReplayService.ReplayOutcome.REPLAYED);
        scheduler.drain();
        assertThat(processingState(subEventId)).isEqualTo("FAILED");
        assertThat(replayCount(subEventId)).isEqualTo(2);
        assertThat(subscriptionSnapshot(workspaceId, "sub_replay_idem")).isEmpty();

        // Fix the underlying condition, then a third replay/retry cycle finally succeeds.
        seedPrice(workspaceId, "price_replay_idem_unresolved");
        assertThat(replayService.replayEvent(workspaceId, subEventId)).isEqualTo(StripeWebhookReplayService.ReplayOutcome.REPLAYED);
        scheduler.drain();
        assertThat(processingState(subEventId)).isEqualTo("PROCESSED");
        assertThat(subscriptionSnapshot(workspaceId, "sub_replay_idem")).isPresent();
        assertThat(subscriptionItemSnapshots(workspaceId, "sub_replay_idem")).hasSize(1);

        // Replaying an already-PROCESSED event is a safe idempotent no-op, not a duplicate attempt.
        assertThat(replayService.replayEvent(workspaceId, subEventId))
                .isEqualTo(StripeWebhookReplayService.ReplayOutcome.NOT_ELIGIBLE);
        scheduler.drain();
        assertThat(jdbc().sql("SELECT COUNT(*) FROM billing_subscriptions WHERE workspace_id = :w AND stripe_subscription_id = :s")
                        .param("w", workspaceId)
                        .param("s", "sub_replay_idem")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    // ---- Failure/crash recovery: not permanently lost, not silently completed --------------------

    @Test
    void aTransientFailureDuringATickLeavesTheEventFailedNotLostAndRecoverableByReplayAndALaterTick() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_failure", StripeConnectionMode.TEST);
        // Referencing an unseeded price makes BillingMrrRecalculationAdapter.requireResolvedPrices
        // throw IllegalStateException (classified TRANSIENT, not UNSUPPORTED) -- no Stripe API call
        // involved, deterministic every run.
        String customer = BillingFixtures.customer("cus_failure", "usd", BASE.getEpochSecond(), false, null);
        insertPendingWebhookEvent(
                connectionId, workspaceId, StripeConnectionMode.TEST, "evt_failure_cust", "customer.created", BASE, customer);
        String item = BillingFixtures.subscriptionItem("si_failure", "price_unresolved", 1);
        String subscription = BillingFixtures.subscription(
                "sub_failure", "cus_failure", "active", "usd", BASE.getEpochSecond(),
                BASE.plusSeconds(2_592_000).getEpochSecond(), false, null, null, item, null);
        insertPendingWebhookEvent(
                connectionId, workspaceId, StripeConnectionMode.TEST, "evt_failure_sub", "customer.subscription.created",
                BASE.plusSeconds(1), subscription);

        var scheduler = new StripeWebhookNormalizationScheduler(
                normalizationService, new StripeWebhookNormalizationSchedulerProperties(true, 50, 10));
        scheduler.drain();

        UUID subEventId = eventIdFor(workspaceId, "evt_failure_sub");
        assertThat(processingState(subEventId)).isEqualTo("FAILED");
        assertThat(failureKind(subEventId)).isEqualTo("TRANSIENT");
        // Not silently completed: no subscription row was created from the failed attempt.
        assertThat(subscriptionSnapshot(workspaceId, "sub_failure")).isEmpty();

        // Fix the underlying condition, then recover through the ordinary replay endpoint + a later tick.
        seedPrice(workspaceId, "price_unresolved");
        replayService.replayEvent(workspaceId, subEventId);
        scheduler.drain();

        assertThat(processingState(subEventId)).isEqualTo("PROCESSED");
        assertThat(subscriptionSnapshot(workspaceId, "sub_failure")).isPresent();
    }

    // ---- Cross-tenant isolation ------------------------------------------------------------------

    @Test
    void oneTickProcessesMultipleWorkspacesWithoutCrossingTenantBoundaries() {
        UUID workspaceA = createWorkspace();
        UUID workspaceB = createWorkspace();
        UUID connectionA = insertActiveConnection(workspaceA, "acct_tenant_a", StripeConnectionMode.TEST);
        UUID connectionB = insertActiveConnection(workspaceB, "acct_tenant_b", StripeConnectionMode.TEST);
        IntStream.range(0, 5).forEach(i -> insertPendingWebhookEvent(
                connectionA, workspaceA, StripeConnectionMode.TEST, "evt_tenant_a_" + i, "customer.created",
                BASE.plusSeconds(i), BillingFixtures.customer("cus_tenant_a_" + i, "usd", BASE.getEpochSecond(), false, null)));
        IntStream.range(0, 3).forEach(i -> insertPendingWebhookEvent(
                connectionB, workspaceB, StripeConnectionMode.TEST, "evt_tenant_b_" + i, "customer.created",
                BASE.plusSeconds(i), BillingFixtures.customer("cus_tenant_b_" + i, "usd", BASE.getEpochSecond(), false, null)));

        var scheduler = new StripeWebhookNormalizationScheduler(
                normalizationService, new StripeWebhookNormalizationSchedulerProperties(true, 50, 10));
        scheduler.drain();

        assertThat(processedCount(workspaceA)).isEqualTo(5);
        assertThat(processedCount(workspaceB)).isEqualTo(3);
        assertThat(jdbc().sql("SELECT COUNT(*) FROM billing_customers WHERE workspace_id = :w")
                        .param("w", workspaceA)
                        .query(Integer.class)
                        .single())
                .isEqualTo(5);
        assertThat(jdbc().sql("SELECT COUNT(*) FROM billing_customers WHERE workspace_id = :w")
                        .param("w", workspaceB)
                        .query(Integer.class)
                        .single())
                .isEqualTo(3);
    }

    // ---- Configurable / disableable ---------------------------------------------------------------

    @Test
    void tickIsANoOpWhenDisabled() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_disabled", StripeConnectionMode.TEST);
        insertPendingWebhookEvent(
                connectionId, workspaceId, StripeConnectionMode.TEST, "evt_disabled", "customer.created",
                BASE, BillingFixtures.customer("cus_disabled", "usd", BASE.getEpochSecond(), false, null));

        var scheduler = new StripeWebhookNormalizationScheduler(
                normalizationService, new StripeWebhookNormalizationSchedulerProperties(false, 50, 10));
        scheduler.tick();

        assertThat(pendingCount(workspaceId)).isEqualTo(1);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private int pendingCount(UUID workspaceId) {
        return jdbc().sql(
                        "SELECT COUNT(*) FROM stripe_webhook_events WHERE workspace_id = :w AND processing_state = 'PENDING'")
                .param("w", workspaceId)
                .query(Integer.class)
                .single();
    }

    private int processedCount(UUID workspaceId) {
        return jdbc().sql(
                        "SELECT COUNT(*) FROM stripe_webhook_events WHERE workspace_id = :w AND processing_state = 'PROCESSED'")
                .param("w", workspaceId)
                .query(Integer.class)
                .single();
    }

    private UUID eventIdFor(UUID workspaceId, String stripeEventId) {
        return jdbc().sql("SELECT id FROM stripe_webhook_events WHERE workspace_id = :w AND stripe_event_id = :e")
                .param("w", workspaceId)
                .param("e", stripeEventId)
                .query(UUID.class)
                .single();
    }

    private String processingState(UUID eventId) {
        return jdbc().sql("SELECT processing_state FROM stripe_webhook_events WHERE id = :id")
                .param("id", eventId)
                .query(String.class)
                .single();
    }

    private String failureKind(UUID eventId) {
        return jdbc().sql("SELECT failure_kind FROM stripe_webhook_events WHERE id = :id")
                .param("id", eventId)
                .query(String.class)
                .single();
    }

    private int replayCount(UUID eventId) {
        return jdbc().sql("SELECT replay_count FROM stripe_webhook_events WHERE id = :id")
                .param("id", eventId)
                .query(Integer.class)
                .single();
    }
}
