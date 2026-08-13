package com.mrrorigin.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * #15's bounded, idempotent, workspace-scoped replay of FAILED raw webhook events: duplicate
 * replay/idempotency, interrupted replay + retry, failed webhook recovery, unsupported-vs-transient
 * classification, cross-workspace isolation, and concurrent replay attempts.
 */
@Testcontainers
class StripeWebhookReplayIntegrationTests extends AbstractBillingLedgerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    private static final StripeBillingListApiStub STRIPE_LIST_STUB = new StripeBillingListApiStub();

    @DynamicPropertySource
    static void stripeListApi(DynamicPropertyRegistry registry) {
        registry.add("mrrorigin.stripe.connect.api-base-uri", STRIPE_LIST_STUB::apiBaseUri);
    }

    @Autowired
    private StripeWebhookReplayService replayService;

    private static final Instant BASE = Instant.parse("2026-03-01T00:00:00Z");

    // ---- Failed webhook recovery + duplicate replay/idempotency ---------------------------------

    @Test
    void replayRecoversATransientlyFailedEventAndReplayingAgainIsANoOp() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_replay_transient", StripeConnectionMode.TEST);

        String subscriptionId = "sub_transient_fail";
        String customer = BillingFixtures.customer("cus_transient_fail", "usd", BASE.getEpochSecond(), false, null);
        insertPendingWebhookEvent(
                connectionId, workspaceId, StripeConnectionMode.TEST, "evt_cust_transient", "customer.created", BASE, customer);
        drainWebhookQueue();

        // A subscription-level discount that arrives as a bare (non-object) id forces the normalizer
        // to fetch the fully expanded subscription from Stripe. It is deliberately NOT seeded on the
        // stub yet, so that fetch 404s -- a StripeBackfillException, not a
        // StripeBillingNormalizationException, i.e. a TRANSIENT (not UNSUPPORTED) failure.
        String item = BillingFixtures.subscriptionItem("si_transient_fail", "price_unseeded", 1);
        String subscription = BillingFixtures.subscription(
                subscriptionId, "cus_transient_fail", "active", "usd", BASE.getEpochSecond(),
                BASE.plusSeconds(2_592_000).getEpochSecond(), false, null, null, item, "\"di_bare_unexpanded\"");
        insertPendingWebhookEvent(
                connectionId, workspaceId, StripeConnectionMode.TEST, "evt_sub_transient", "customer.subscription.created",
                BASE.plusSeconds(1), subscription);
        drainWebhookQueue();

        UUID eventId = eventIdFor(workspaceId, "evt_sub_transient");
        assertThat(processingState(eventId)).isEqualTo("FAILED");
        assertThat(failureKind(eventId)).isEqualTo("TRANSIENT");
        assertThat(replayCount(eventId)).isZero();
        assertThat(subscriptionSnapshot(workspaceId, subscriptionId)).isEmpty();

        // Now seed the expanded subscription, with a full discount object resolving the same
        // "di_bare_unexpanded" id the raw event referenced only by id, so reprocessing succeeds.
        String expandedDiscount = BillingFixtures.discount(
                "di_bare_unexpanded", null, subscriptionId, "coupon_transient_fix", 10L, null, null,
                BASE.getEpochSecond(), null);
        String expandedSubscription = BillingFixtures.subscription(
                subscriptionId, "cus_transient_fail", "active", "usd", BASE.getEpochSecond(),
                BASE.plusSeconds(2_592_000).getEpochSecond(), false, null, null, item, expandedDiscount);
        STRIPE_LIST_STUB.seedSingleSubscription(subscriptionId, expandedSubscription);

        StripeWebhookReplayService.ReplayOutcome outcome = replayService.replayEvent(workspaceId, eventId);
        assertThat(outcome).isEqualTo(StripeWebhookReplayService.ReplayOutcome.REPLAYED);
        assertThat(processingState(eventId)).isEqualTo("PENDING");
        assertThat(failureKind(eventId)).isNull();
        assertThat(replayCount(eventId)).isEqualTo(1);
        assertThat(lastReplayedAt(eventId)).isNotNull();

        drainWebhookQueue();
        assertThat(processingState(eventId)).isEqualTo("PROCESSED");
        assertThat(subscriptionSnapshot(workspaceId, subscriptionId)).isPresent();

        // Idempotency: replaying an event that is no longer FAILED is a safe no-op, not an error.
        StripeWebhookReplayService.ReplayOutcome secondReplay = replayService.replayEvent(workspaceId, eventId);
        assertThat(secondReplay).isEqualTo(StripeWebhookReplayService.ReplayOutcome.NOT_ELIGIBLE);
        assertThat(replayCount(eventId)).isEqualTo(1);
        assertThat(processingState(eventId)).isEqualTo("PROCESSED");
    }

    @Test
    void replayingAStillPendingEventIsANoOpAndDoesNotDoubleCountReplayCount() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_replay_dup", StripeConnectionMode.TEST);
        insertUnsupportedFailure(connectionId, workspaceId, "evt_dup_replay");
        UUID eventId = eventIdFor(workspaceId, "evt_dup_replay");

        assertThat(replayService.replayEvent(workspaceId, eventId)).isEqualTo(StripeWebhookReplayService.ReplayOutcome.REPLAYED);
        assertThat(processingState(eventId)).isEqualTo("PENDING");

        // The event has not been reprocessed (still PENDING, not FAILED again) -- replaying it a
        // second time here must not re-increment replay_count or otherwise change anything.
        assertThat(replayService.replayEvent(workspaceId, eventId)).isEqualTo(StripeWebhookReplayService.ReplayOutcome.NOT_ELIGIBLE);
        assertThat(replayCount(eventId)).isEqualTo(1);
        assertThat(processingState(eventId)).isEqualTo("PENDING");
    }

    // ---- Interrupted replay + retry ---------------------------------------------------------------

    @Test
    void interruptedReprocessingAfterReplayIsSafelyRetriedOnceTheLeaseExpires() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_replay_interrupt", StripeConnectionMode.TEST);

        // A fixable (TRANSIENT) failure, not the always-failing UNSUPPORTED fixture: this test is
        // about the claim/lease pipeline surviving an interruption, so the replayed event must be
        // able to actually reach PROCESSED once reclaimed.
        String subscriptionId = "sub_interrupt";
        String customer = BillingFixtures.customer("cus_interrupt", "usd", BASE.getEpochSecond(), false, null);
        insertPendingWebhookEvent(
                connectionId, workspaceId, StripeConnectionMode.TEST, "evt_cust_interrupt", "customer.created", BASE, customer);
        drainWebhookQueue();
        String item = BillingFixtures.subscriptionItem("si_interrupt", "price_interrupt", 1);
        String subscription = BillingFixtures.subscription(
                subscriptionId, "cus_interrupt", "active", "usd", BASE.getEpochSecond(),
                BASE.plusSeconds(2_592_000).getEpochSecond(), false, null, null, item, "\"di_bare_interrupt\"");
        insertPendingWebhookEvent(
                connectionId, workspaceId, StripeConnectionMode.TEST, "evt_interrupt", "customer.subscription.created",
                BASE.plusSeconds(1), subscription);
        drainWebhookQueue();
        UUID eventId = eventIdFor(workspaceId, "evt_interrupt");
        assertThat(processingState(eventId)).isEqualTo("FAILED");
        assertThat(failureKind(eventId)).isEqualTo("TRANSIENT");

        // Now make it fixable: seed the expanded subscription, with a full discount object resolving
        // the same "di_bare_interrupt" id the raw event referenced only by id, for the normalizer to
        // fetch on retry.
        String expandedDiscount = BillingFixtures.discount(
                "di_bare_interrupt", null, subscriptionId, "coupon_interrupt_fix", 10L, null, null,
                BASE.getEpochSecond(), null);
        String expandedSubscription = BillingFixtures.subscription(
                subscriptionId, "cus_interrupt", "active", "usd", BASE.getEpochSecond(),
                BASE.plusSeconds(2_592_000).getEpochSecond(), false, null, null, item, expandedDiscount);
        STRIPE_LIST_STUB.seedSingleSubscription(subscriptionId, expandedSubscription);

        // Replay requeues to PENDING, then a worker claims it (starting reprocessing)...
        assertThat(replayService.replayEvent(workspaceId, eventId)).isEqualTo(StripeWebhookReplayService.ReplayOutcome.REPLAYED);
        StripeWebhookNormalizationService.PendingEvent claimed = normalizationService.claimBatch(10).stream()
                .filter(e -> e.id().equals(eventId))
                .findFirst()
                .orElseThrow();

        // ...and then crashes before completing: no apply, no mark-failed. Simulate the lease aging
        // out, exactly like BillingLedgerConcurrencyAndIsolationIntegrationTests' lease-fencing test.
        jdbc().sql("UPDATE stripe_webhook_events SET last_attempted_at = last_attempted_at - INTERVAL '10 minutes' WHERE id = :id")
                .param("id", eventId)
                .update();
        assertThat(processingState(eventId)).isEqualTo("PENDING");

        // A later run reclaims and completes it exactly once; the interrupted worker's lease is gone.
        int totalProcessed = drainWebhookQueue();
        assertThat(totalProcessed).isGreaterThanOrEqualTo(1);
        assertThat(processingState(eventId)).isEqualTo("PROCESSED");
        assertThat(subscriptionSnapshot(workspaceId, subscriptionId)).isPresent();

        // The original (now stale) claim can no longer apply anything under its old lease.
        assertThat(normalizationService.applyAndMarkProcessed(claimed, () -> true))
                .isEqualTo(StripeWebhookNormalizationService.ApplyOutcome.LEASE_LOST);
    }

    // ---- Cross-workspace isolation ---------------------------------------------------------------

    @Test
    void replayCannotReachAnotherWorkspacesEvent() {
        UUID workspaceA = createWorkspace();
        UUID workspaceB = createWorkspace();
        UUID connectionA = insertActiveConnection(workspaceA, "acct_replay_iso_a", StripeConnectionMode.TEST);
        insertUnsupportedFailure(connectionA, workspaceA, "evt_iso_a");
        UUID eventId = eventIdFor(workspaceA, "evt_iso_a");

        assertThatThrownBy(() -> replayService.replayEvent(workspaceB, eventId))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(t -> ((ResponseStatusException) t).getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);

        // Untouched: still FAILED under workspace A, not replayed by the cross-tenant attempt.
        assertThat(processingState(eventId)).isEqualTo("FAILED");
        assertThat(replayCount(eventId)).isZero();

        // The batch replay path is scoped the same way: it must never touch workspace A's event
        // while acting "on behalf of" workspace B.
        StripeWebhookReplayService.BatchReplayOutcome batch = replayService.replayFailed(workspaceB, 10);
        assertThat(batch.replayedEventIds()).isEmpty();
        assertThat(processingState(eventId)).isEqualTo("FAILED");
    }

    // ---- Concurrent replay attempts ----------------------------------------------------------------

    @Test
    void concurrentSingleEventReplayAttemptsReplayExactlyOnce() throws Exception {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_replay_concurrent", StripeConnectionMode.TEST);
        insertUnsupportedFailure(connectionId, workspaceId, "evt_concurrent_single");
        UUID eventId = eventIdFor(workspaceId, "evt_concurrent_single");

        int attempts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            CyclicBarrier barrier = new CyclicBarrier(attempts);
            List<Callable<StripeWebhookReplayService.ReplayOutcome>> workers = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                workers.add(() -> {
                    barrier.await();
                    return replayService.replayEvent(workspaceId, eventId);
                });
            }
            List<Future<StripeWebhookReplayService.ReplayOutcome>> futures = pool.invokeAll(workers);
            long replayedCount = 0;
            for (Future<StripeWebhookReplayService.ReplayOutcome> future : futures) {
                if (future.get() == StripeWebhookReplayService.ReplayOutcome.REPLAYED) {
                    replayedCount++;
                }
            }
            assertThat(replayedCount).isOne();
        } finally {
            pool.shutdown();
        }

        assertThat(replayCount(eventId)).isEqualTo(1);
        assertThat(processingState(eventId)).isEqualTo("PENDING");
    }

    @Test
    void concurrentBatchReplayAttemptsPartitionTheFailedBacklogWithoutOverlap() throws Exception {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_replay_batch_concurrent", StripeConnectionMode.TEST);
        int total = 20;
        for (int i = 0; i < total; i++) {
            insertUnsupportedFailure(connectionId, workspaceId, "evt_batch_concurrent_%02d".formatted(i));
        }

        int workers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            CyclicBarrier barrier = new CyclicBarrier(workers);
            List<Callable<List<UUID>>> tasks = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                tasks.add(() -> {
                    barrier.await();
                    return replayService.replayFailed(workspaceId, 10).replayedEventIds();
                });
            }
            List<Future<List<UUID>>> futures = pool.invokeAll(tasks);
            List<UUID> allReplayed = new ArrayList<>();
            for (Future<List<UUID>> future : futures) {
                allReplayed.addAll(future.get());
            }
            assertThat(allReplayed).hasSize(total);
            assertThat(allReplayed).doesNotHaveDuplicates();
        } finally {
            pool.shutdown();
        }

        assertThat(jdbc().sql("SELECT COUNT(*) FROM stripe_webhook_events WHERE workspace_id = :w AND processing_state = 'PENDING'")
                        .param("w", workspaceId)
                        .query(Integer.class)
                        .single())
                .isEqualTo(total);
    }

    // ---- Unsupported vs. transient classification --------------------------------------------------

    @Test
    void unsupportedAndTransientFailuresAreClassifiedDistinctly() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_replay_classify", StripeConnectionMode.TEST);

        insertUnsupportedFailure(connectionId, workspaceId, "evt_classify_unsupported");
        UUID unsupportedId = eventIdFor(workspaceId, "evt_classify_unsupported");
        assertThat(processingState(unsupportedId)).isEqualTo("FAILED");
        assertThat(failureKind(unsupportedId)).isEqualTo("UNSUPPORTED");

        String customer = BillingFixtures.customer("cus_classify_transient", "usd", BASE.getEpochSecond(), false, null);
        insertPendingWebhookEvent(
                connectionId, workspaceId, StripeConnectionMode.TEST, "evt_cust_classify_transient", "customer.created",
                BASE, customer);
        drainWebhookQueue();
        String item = BillingFixtures.subscriptionItem("si_classify_transient", "price_unseeded_classify", 1);
        String subscription = BillingFixtures.subscription(
                "sub_classify_transient", "cus_classify_transient", "active", "usd", BASE.getEpochSecond(),
                BASE.plusSeconds(2_592_000).getEpochSecond(), false, null, null, item, "\"di_bare_classify\"");
        insertPendingWebhookEvent(
                connectionId, workspaceId, StripeConnectionMode.TEST, "evt_classify_transient",
                "customer.subscription.created", BASE.plusSeconds(1), subscription);
        drainWebhookQueue();
        UUID transientId = eventIdFor(workspaceId, "evt_classify_transient");
        assertThat(processingState(transientId)).isEqualTo("FAILED");
        assertThat(failureKind(transientId)).isEqualTo("TRANSIENT");
    }

    // ---- helpers ------------------------------------------------------------------------------------

    /** Directly inserts and drains an event whose {@code data.object} has no discernible shape at all (UNSUPPORTED). */
    private void insertUnsupportedFailure(UUID connectionId, UUID workspaceId, String eventId) {
        insertPendingWebhookEvent(
                connectionId, workspaceId, StripeConnectionMode.TEST, eventId, "customer.discount.created", BASE,
                "{\"id\":\"di_missing_coupon\",\"object\":\"discount\"}");
        drainWebhookQueue();
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
                .optional()
                .orElse(null);
    }

    private int replayCount(UUID eventId) {
        return jdbc().sql("SELECT replay_count FROM stripe_webhook_events WHERE id = :id")
                .param("id", eventId)
                .query(Integer.class)
                .single();
    }

    private OffsetDateTime lastReplayedAt(UUID eventId) {
        return jdbc().sql("SELECT last_replayed_at FROM stripe_webhook_events WHERE id = :id")
                .param("id", eventId)
                .query(OffsetDateTime.class)
                .list()
                .stream()
                .findFirst()
                .orElse(null);
    }

}
