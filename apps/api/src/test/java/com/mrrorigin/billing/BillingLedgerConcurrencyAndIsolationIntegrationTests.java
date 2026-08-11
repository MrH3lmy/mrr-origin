package com.mrrorigin.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** #12's concurrency and cross-workspace isolation guarantees. */
@Testcontainers
class BillingLedgerConcurrencyAndIsolationIntegrationTests extends AbstractBillingLedgerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    private static final StripeBillingListApiStub STRIPE_LIST_STUB = new StripeBillingListApiStub();

    @DynamicPropertySource
    static void stripeListApi(DynamicPropertyRegistry registry) {
        registry.add("mrrorigin.stripe.connect.api-base-uri", STRIPE_LIST_STUB::apiBaseUri);
    }

    @Test
    void concurrentWebhookNormalizationNeverDoubleProcessesAnEvent() throws Exception {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_concurrency_webhook", StripeConnectionMode.TEST);
        int total = 30;
        Instant base = Instant.parse("2026-03-01T00:00:00Z");
        for (int i = 0; i < total; i++) {
            String customer = BillingFixtures.customer("cus_conc_%03d".formatted(i), "usd", base.getEpochSecond(), false, null);
            insertPendingWebhookEvent(
                    connectionId, workspaceId, StripeConnectionMode.TEST, "evt_conc_%03d".formatted(i), "customer.created",
                    base.plusSeconds(i), customer);
        }

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CyclicBarrier barrier = new CyclicBarrier(4);
            List<Callable<Integer>> workers = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                workers.add(() -> {
                    barrier.await();
                    int processed = 0;
                    StripeWebhookNormalizationService.NormalizationRunOutcome outcome;
                    do {
                        outcome = normalizationService.processBatch(5);
                        processed += outcome.fetched();
                    } while (outcome.fetched() > 0);
                    return processed;
                });
            }
            List<Future<Integer>> futures = pool.invokeAll(workers);
            int totalClaimed = 0;
            for (Future<Integer> future : futures) {
                totalClaimed += future.get();
            }
            assertThat(totalClaimed).isEqualTo(total);
        } finally {
            pool.shutdown();
        }

        assertThat(jdbc().sql("SELECT COUNT(*) FROM billing_customers WHERE workspace_id = :w")
                        .param("w", workspaceId)
                        .query(Integer.class)
                        .single())
                .isEqualTo(total);
        assertThat(jdbc().sql("SELECT COUNT(*) FROM stripe_webhook_events WHERE workspace_id = :w AND processing_state = 'PENDING'")
                        .param("w", workspaceId)
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    void concurrentBackfillRunsForTheSameConnectionConvergeWithoutLosingProgress() throws Exception {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_concurrency_backfill", StripeConnectionMode.TEST);
        List<String> customers = new ArrayList<>();
        long created = Instant.now().getEpochSecond();
        for (int i = 0; i < 40; i++) {
            customers.add(BillingFixtures.customer("cus_bconc_%03d".formatted(i), "usd", created, false, null));
        }
        STRIPE_LIST_STUB.seed("/v1/customers", customers);
        STRIPE_LIST_STUB.seed("/v1/prices", List.of());
        STRIPE_LIST_STUB.seed("/v1/subscriptions", List.of());
        STRIPE_LIST_STUB.seed("/v1/invoices", List.of());
        STRIPE_LIST_STUB.seed("/v1/charges", List.of());
        STRIPE_LIST_STUB.seed("/v1/refunds", List.of());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Callable<Void>> workers = List.of(
                    () -> {
                        barrier.await();
                        runBackfillToCompletion(connectionId);
                        return null;
                    },
                    () -> {
                        barrier.await();
                        runBackfillToCompletion(connectionId);
                        return null;
                    });
            List<Future<Void>> futures = pool.invokeAll(workers);
            for (Future<Void> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdown();
        }

        assertThat(jdbc().sql("SELECT COUNT(*) FROM billing_customers WHERE workspace_id = :w")
                        .param("w", workspaceId)
                        .query(Integer.class)
                        .single())
                .isEqualTo(40);
    }

    @Test
    void crossWorkspaceDataNeverLeaksEvenWithIdenticalStripeIds() {
        UUID workspaceA = createWorkspace();
        UUID workspaceB = createWorkspace();
        UUID connectionA = insertActiveConnection(workspaceA, "acct_iso_a", StripeConnectionMode.TEST);
        UUID connectionB = insertActiveConnection(workspaceB, "acct_iso_b", StripeConnectionMode.TEST);
        Instant now = Instant.now();

        // Same Stripe object ID string reused across two unrelated workspaces' connected accounts,
        // with deliberately different field values, so a leak would be immediately visible.
        insertPendingWebhookEvent(
                connectionA, workspaceA, StripeConnectionMode.TEST, "evt_iso_a", "customer.created",
                now, BillingFixtures.customer("cus_shared", "usd", now.getEpochSecond(), false, null));
        insertPendingWebhookEvent(
                connectionB, workspaceB, StripeConnectionMode.TEST, "evt_iso_b", "customer.created",
                now, BillingFixtures.customer("cus_shared", "eur", now.getEpochSecond(), false, null));

        assertThat(drainWebhookQueue()).isEqualTo(2);

        assertThat(customerSnapshot(workspaceA, "cus_shared").orElseThrow()).containsEntry("currency", "usd");
        assertThat(customerSnapshot(workspaceB, "cus_shared").orElseThrow()).containsEntry("currency", "eur");

        // A webhook event for workspace A's connection must never normalize into workspace B's ledger.
        insertPendingWebhookEvent(
                connectionA, workspaceA, StripeConnectionMode.TEST, "evt_iso_a2", "customer.created",
                now.plusSeconds(60), BillingFixtures.customer("cus_only_a", "usd", now.getEpochSecond(), false, null));
        assertThat(drainWebhookQueue()).isEqualTo(1);
        assertThat(customerSnapshot(workspaceA, "cus_only_a")).isPresent();
        assertThat(customerSnapshot(workspaceB, "cus_only_a")).isEmpty();
    }
}
