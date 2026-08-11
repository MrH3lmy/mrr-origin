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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * #12's checkpoint/restart guarantees: bounded pagination resumes exactly where it left off,
 * a failure mid-page leaves no partial state and no advanced checkpoint, and reprocessing an
 * already-applied page is a safe no-op.
 */
@Testcontainers
class StripeBackfillCheckpointIntegrationTests extends AbstractBillingLedgerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

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
        BillingSourceVersion.SourceVersion replayVersion = BillingSourceVersion.forBackfillFetch(Instant.now());
        Consumer<JsonNode> normalizer = item -> ledger.upsertCustomer(
                workspaceId, StripeBillingObjectParser.parseCustomer(item), replayVersion, BillingLedgerSource.BACKFILL);

        // Directly re-applies the exact same already-committed page twice in a row, both using the
        // SAME expected cursor the original fetch used (an operator retry, or a caller that
        // redelivers a page it mistakenly believed had failed). The first call succeeds and
        // advances the checkpoint; the second is now stale relative to the advanced checkpoint and
        // is safely discarded rather than reapplied -- either way the ledger ends up identical.
        StripeBackfillPageRunner.PageApplyOutcome first =
                pageRunner.applyPage(connectionId, StripeBackfillPhase.PRICES, null, parsed, true, normalizer);
        StripeBackfillPageRunner.PageApplyOutcome second =
                pageRunner.applyPage(connectionId, StripeBackfillPhase.PRICES, null, parsed, true, normalizer);

        assertThat(first.status()).isEqualTo(StripeBackfillPageRunner.PageApplyStatus.APPLIED);
        assertThat(second.status()).isEqualTo(StripeBackfillPageRunner.PageApplyStatus.STALE);
        assertThat(countCustomers(workspaceId)).isEqualTo(10);
    }

    @Test
    void aStaleSlowerPageCanNeverMoveTheCheckpointBackwards() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_monotonic", StripeConnectionMode.TEST);

        List<String> firstPageItems = customers(100);
        List<JsonNode> firstPageParsed = parse(firstPageItems);
        List<String> secondPageItems = customers(50, 100);
        List<JsonNode> secondPageParsed = parse(secondPageItems);

        Consumer<JsonNode> normalizer = item -> ledger.upsertCustomer(
                workspaceId, StripeBillingObjectParser.parseCustomer(item), BillingSourceVersion.forBackfillFetch(Instant.now()),
                BillingLedgerSource.BACKFILL);

        // Request A fetches page 1 (starting_after=null) and applies it: checkpoint cursor -> last item of page 1.
        StripeBackfillPageRunner.PageApplyOutcome pageOneOutcome =
                pageRunner.applyPage(connectionId, StripeBackfillPhase.CUSTOMERS, null, firstPageParsed, true, normalizer);
        assertThat(pageOneOutcome.status()).isEqualTo(StripeBackfillPageRunner.PageApplyStatus.APPLIED);
        String cursorAfterPageOne = currentCheckpointCursor(connectionId);
        assertThat(cursorAfterPageOne).isEqualTo("cus_0099");

        // A faster concurrent request B fetches and applies page 2 (starting_after=<page 1's last
        // id>) before A's slow retry ever arrives: checkpoint cursor -> last item of page 2.
        StripeBackfillPageRunner.PageApplyOutcome pageTwoOutcome = pageRunner.applyPage(
                connectionId, StripeBackfillPhase.CUSTOMERS, cursorAfterPageOne, secondPageParsed, true, normalizer);
        assertThat(pageTwoOutcome.status()).isEqualTo(StripeBackfillPageRunner.PageApplyStatus.APPLIED);
        assertThat(currentCheckpointCursor(connectionId)).isEqualTo("cus_0149");

        // Request A's slow retry of page 1 (still expecting starting_after=null, its own original
        // fetch position) finally arrives, after the checkpoint has already moved past it. Phase
        // alone would match (still CUSTOMERS); only the cursor check catches this.
        StripeBackfillPageRunner.PageApplyOutcome staleRetryOutcome =
                pageRunner.applyPage(connectionId, StripeBackfillPhase.CUSTOMERS, null, firstPageParsed, true, normalizer);

        assertThat(staleRetryOutcome.status()).isEqualTo(StripeBackfillPageRunner.PageApplyStatus.STALE);
        assertThat(currentCheckpointCursor(connectionId)).isEqualTo("cus_0149");
        assertThat(countCustomers(workspaceId)).isEqualTo(150);
    }

    private List<JsonNode> parse(List<String> rawItems) {
        List<JsonNode> parsed = new ArrayList<>();
        for (String raw : rawItems) {
            parsed.add(objectMapper.readTree(raw));
        }
        return parsed;
    }

    private static List<String> customers(int count) {
        return customers(count, 0);
    }

    private static List<String> customers(int count, int startIndex) {
        List<String> items = new ArrayList<>();
        long created = Instant.now().getEpochSecond();
        for (int i = startIndex; i < startIndex + count; i++) {
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
