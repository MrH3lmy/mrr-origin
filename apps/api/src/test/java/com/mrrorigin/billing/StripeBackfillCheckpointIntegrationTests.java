package com.mrrorigin.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * #12's checkpoint/restart guarantees: bounded pagination resumes exactly where it left off,
 * a failure mid-page leaves no partial state and no advanced checkpoint, and reprocessing an
 * already-applied page is a safe no-op.
 */
@Testcontainers
class StripeBackfillCheckpointIntegrationTests extends AbstractBillingLedgerIntegrationTest {

    private static final StripeBillingListApiStub STRIPE_LIST_STUB = new StripeBillingListApiStub();

    @DynamicPropertySource
    static void stripeListApi(DynamicPropertyRegistry registry) {
        registry.add("mrrorigin.stripe.connect.api-base-uri", STRIPE_LIST_STUB::apiBaseUri);
    }

    @Autowired
    private StripeBackfillPageRunner pageRunner;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void interruptedBackfillResumesFromTheLastCommittedPage() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_pagination", StripeConnectionMode.TEST);
        STRIPE_LIST_STUB.seed("/v1/customers", customers(150));
        STRIPE_LIST_STUB.seed("/v1/prices", List.of());
        STRIPE_LIST_STUB.seed("/v1/subscriptions", List.of());
        STRIPE_LIST_STUB.seed("/v1/invoices", List.of());
        STRIPE_LIST_STUB.seed("/v1/charges", List.of());
        STRIPE_LIST_STUB.seed("/v1/refunds", List.of());

        StripeBackfillService.BackfillRunOutcome first = backfillService.runBatch(connectionId, 1);
        assertThat(first.pagesProcessed()).isEqualTo(1);
        assertThat(first.complete()).isFalse();
        assertThat(countCustomers(workspaceId)).isEqualTo(StripeBackfillClient.PAGE_SIZE);

        // Simulates a process restart: a brand-new call, resuming purely from the persisted checkpoint.
        runBackfillToCompletion(connectionId);
        assertThat(countCustomers(workspaceId)).isEqualTo(150);
    }

    @Test
    void aFailureBeforeCheckpointCommitLeavesNoPartialStateAndRetryStartsFromTheSameCursor() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_failure", StripeConnectionMode.TEST);

        // The second object is malformed (no "created"); the whole page must roll back atomically.
        STRIPE_LIST_STUB.seed(
                "/v1/customers",
                List.of(
                        BillingFixtures.customer("cus_ok", "usd", Instant.now().getEpochSecond(), false, null),
                        "{\"id\":\"cus_bad\",\"object\":\"customer\"}"));
        STRIPE_LIST_STUB.seed("/v1/prices", List.of());
        STRIPE_LIST_STUB.seed("/v1/subscriptions", List.of());
        STRIPE_LIST_STUB.seed("/v1/invoices", List.of());
        STRIPE_LIST_STUB.seed("/v1/charges", List.of());
        STRIPE_LIST_STUB.seed("/v1/refunds", List.of());

        assertThatThrownBy(() -> backfillService.runBatch(connectionId, 1))
                .isInstanceOf(StripeBillingNormalizationException.class);
        assertThat(countCustomers(workspaceId)).isZero();
        assertThat(currentCheckpointCursor(connectionId)).isNull();

        // Retry with corrected upstream data: the page is refetched and fully (not partially) applied.
        STRIPE_LIST_STUB.seed(
                "/v1/customers",
                List.of(
                        BillingFixtures.customer("cus_ok", "usd", Instant.now().getEpochSecond(), false, null),
                        BillingFixtures.customer("cus_bad", "usd", Instant.now().getEpochSecond(), false, null)));
        runBackfillToCompletion(connectionId);
        assertThat(countCustomers(workspaceId)).isEqualTo(2);
    }

    @Test
    void reapplyingAnAlreadyCommittedPageIsANoOp() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_replay", StripeConnectionMode.TEST);
        List<String> page = customers(10);
        STRIPE_LIST_STUB.seed("/v1/customers", page);
        STRIPE_LIST_STUB.seed("/v1/prices", List.of());
        STRIPE_LIST_STUB.seed("/v1/subscriptions", List.of());
        STRIPE_LIST_STUB.seed("/v1/invoices", List.of());
        STRIPE_LIST_STUB.seed("/v1/charges", List.of());
        STRIPE_LIST_STUB.seed("/v1/refunds", List.of());

        backfillService.runBatch(connectionId, 1);
        assertThat(countCustomers(workspaceId)).isEqualTo(10);

        List<JsonNode> parsed = new ArrayList<>();
        for (String raw : page) {
            parsed.add(objectMapper.readTree(raw));
        }
        long replayVersion = Instant.now().getEpochSecond();
        Consumer<JsonNode> normalizer = item -> ledger.upsertCustomer(
                workspaceId, StripeBillingObjectParser.parseCustomer(item), replayVersion, BillingLedgerSource.BACKFILL);

        // Directly re-applies the exact same already-committed page twice in a row (an operator
        // retry, or a caller that redelivers a page it mistakenly believed had failed). hasMore=true
        // keeps both calls in the same phase so the second one is a genuine reapplication, not a
        // discarded stale-phase call.
        pageRunner.applyPage(connectionId, StripeBackfillPhase.PRICES, parsed, true, normalizer);
        pageRunner.applyPage(connectionId, StripeBackfillPhase.PRICES, parsed, true, normalizer);

        assertThat(countCustomers(workspaceId)).isEqualTo(10);
    }

    private static List<String> customers(int count) {
        List<String> items = new ArrayList<>();
        long created = Instant.now().getEpochSecond();
        for (int i = 0; i < count; i++) {
            items.add(BillingFixtures.customer("cus_%04d".formatted(i), "usd", created, false, null));
        }
        return items;
    }

    private int countCustomers(UUID workspaceId) {
        return jdbc().sql("SELECT COUNT(*) FROM billing_customers WHERE workspace_id = :w")
                .param("w", workspaceId)
                .query(Integer.class)
                .single();
    }

    private String currentCheckpointCursor(UUID connectionId) {
        String raw = jdbc().sql("SELECT sync_checkpoint FROM stripe_connections WHERE id = :id")
                .param("id", connectionId)
                .query(String.class)
                .optional()
                .orElse(null);
        return StripeBackfillCheckpoint.parse(objectMapper, raw).cursor();
    }
}
