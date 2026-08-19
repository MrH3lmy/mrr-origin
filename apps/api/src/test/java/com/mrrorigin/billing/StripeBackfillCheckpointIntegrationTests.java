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

    /**
     * #81's core recovery drill: interrupt a multi-phase backfill (not just the customers phase)
     * after a partial page, resume it from a brand-new call (simulating a process restart), and
     * prove the final normalized state across every object type is identical to a clean,
     * uninterrupted run of the exact same source data -- not just that a row count matches a
     * hardcoded number. Also proves the interrupted/resumed run of workspace A never touches a
     * concurrently-active workspace B's ledger or checkpoint.
     */
    @Test
    void interruptedMultiPhaseBackfillResumesToStateIdenticalToACleanRunAndNeverTouchesAnotherWorkspace() {
        // ---- workspace B: seeded and fully backfilled first, so we have a baseline to prove it's
        // untouched by workspace A's later interrupt/resume. -----------------------------------
        UUID workspaceB = createWorkspace();
        UUID connectionB = insertActiveConnection(workspaceB, "acct_bystander", StripeConnectionMode.TEST);
        STRIPE_LIST_STUB.seed("/v1/customers", List.of(BillingFixtures.customer("cus_bystander", "usd", Instant.now().getEpochSecond(), false, null)));
        STRIPE_LIST_STUB.seed("/v1/prices", List.of());
        STRIPE_LIST_STUB.seed("/v1/subscriptions", List.of());
        STRIPE_LIST_STUB.seed("/v1/invoices", List.of());
        STRIPE_LIST_STUB.seed("/v1/charges", List.of());
        STRIPE_LIST_STUB.seed("/v1/refunds", List.of());
        runBackfillToCompletion(connectionB);
        assertThat(countCustomers(workspaceB)).isEqualTo(1);
        String bystanderCheckpointBefore = rawCheckpoint(connectionB);

        // ---- shared source data for both the clean and the interrupted run: 130 customers (more
        // than one PAGE_SIZE=100 page) plus one full object chain across every other phase. -------
        long created = Instant.now().getEpochSecond();
        List<String> customers = customers(130);
        String subscription = BillingFixtures.subscription(
                "sub_recovery", "cus_0000", "active", "usd", created, created + 2_592_000L, false, null, null,
                BillingFixtures.subscriptionItem("si_recovery", "price_recovery", 1),
                BillingFixtures.discount("di_recovery", null, "sub_recovery", "coupon_recovery", 10L, null, null, created, null));
        STRIPE_LIST_STUB.seed("/v1/customers", customers);
        STRIPE_LIST_STUB.seed(
                "/v1/prices",
                List.of(BillingFixtures.price("price_recovery", "prod_recovery", "usd", 1500L, "recurring", "month", 1, true)));
        STRIPE_LIST_STUB.seed("/v1/subscriptions", List.of(subscription));
        STRIPE_LIST_STUB.seed(
                "/v1/invoices",
                List.of(BillingFixtures.invoice("in_recovery", "cus_0000", "sub_recovery", "paid", "usd", 1350, 1350, 0, created, created + 2_592_000L, created)));
        STRIPE_LIST_STUB.seed(
                "/v1/charges",
                List.of(BillingFixtures.charge("ch_recovery", "cus_0000", "in_recovery", 1350, "usd", "succeeded", true, false, 200, created)));
        STRIPE_LIST_STUB.seed(
                "/v1/refunds",
                List.of(BillingFixtures.refund("re_recovery", "ch_recovery", 200, "usd", "succeeded", "requested_by_customer", created)));

        // ---- clean, uninterrupted run: the baseline every recovery must match. -------------------
        UUID cleanWorkspace = createWorkspace();
        UUID cleanConnection = insertActiveConnection(cleanWorkspace, "acct_recovery_clean", StripeConnectionMode.TEST);
        runBackfillToCompletion(cleanConnection);

        // ---- interrupted run: only the first page of the first phase completes, simulating a
        // process crash right after that page's checkpoint commit... --------------------------
        UUID interruptedWorkspace = createWorkspace();
        UUID interruptedConnection = insertActiveConnection(interruptedWorkspace, "acct_recovery_interrupted", StripeConnectionMode.TEST);
        StripeBackfillService.BackfillRunOutcome partial = backfillService.runBatch(interruptedConnection, 1);
        assertThat(partial.complete()).isFalse();
        assertThat(countCustomers(interruptedWorkspace)).isEqualTo(StripeBackfillClient.PAGE_SIZE);

        // ...workspace B is completely unaffected by A's in-flight interrupted backfill.
        assertThat(countCustomers(workspaceB)).isEqualTo(1);
        assertThat(rawCheckpoint(connectionB)).isEqualTo(bystanderCheckpointBefore);

        // ...then a brand-new call (simulating the restarted process) resumes purely from the
        // persisted checkpoint and completes.
        runBackfillToCompletion(interruptedConnection);

        // ---- final state: interrupted-then-resumed must equal the clean run, table by table. -----
        assertThat(countCustomers(interruptedWorkspace)).isEqualTo(countCustomers(cleanWorkspace)).isEqualTo(130);
        assertThat(count("billing_prices", interruptedWorkspace)).isEqualTo(count("billing_prices", cleanWorkspace)).isEqualTo(1);
        assertThat(count("billing_subscriptions", interruptedWorkspace)).isEqualTo(count("billing_subscriptions", cleanWorkspace)).isEqualTo(1);
        assertThat(count("billing_invoices", interruptedWorkspace)).isEqualTo(count("billing_invoices", cleanWorkspace)).isEqualTo(1);
        assertThat(count("billing_payments", interruptedWorkspace)).isEqualTo(count("billing_payments", cleanWorkspace)).isEqualTo(1);
        assertThat(count("billing_refunds", interruptedWorkspace)).isEqualTo(count("billing_refunds", cleanWorkspace)).isEqualTo(1);
        assertThat(count("billing_discounts", interruptedWorkspace)).isEqualTo(count("billing_discounts", cleanWorkspace)).isEqualTo(1);

        assertThat(priceSnapshot(interruptedWorkspace, "price_recovery")).isEqualTo(priceSnapshot(cleanWorkspace, "price_recovery"));
        assertThat(subscriptionSnapshot(interruptedWorkspace, "sub_recovery")).isEqualTo(subscriptionSnapshot(cleanWorkspace, "sub_recovery"));
        assertThat(subscriptionItemSnapshots(interruptedWorkspace, "sub_recovery")).isEqualTo(subscriptionItemSnapshots(cleanWorkspace, "sub_recovery"));
        assertThat(invoiceSnapshot(interruptedWorkspace, "in_recovery")).isEqualTo(invoiceSnapshot(cleanWorkspace, "in_recovery"));
        assertThat(paymentSnapshot(interruptedWorkspace, "ch_recovery")).isEqualTo(paymentSnapshot(cleanWorkspace, "ch_recovery"));
        assertThat(refundSnapshot(interruptedWorkspace, "re_recovery")).isEqualTo(refundSnapshot(cleanWorkspace, "re_recovery"));
        assertThat(discountSnapshot(interruptedWorkspace, "di_recovery")).isEqualTo(discountSnapshot(cleanWorkspace, "di_recovery"));
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
        return count("billing_customers", workspaceId);
    }

    private int count(String table, UUID workspaceId) {
        return jdbc().sql("SELECT COUNT(*) FROM " + table + " WHERE workspace_id = :w")
                .param("w", workspaceId)
                .query(Integer.class)
                .single();
    }

    private String currentCheckpointCursor(UUID connectionId) {
        return StripeBackfillCheckpoint.parse(objectMapper, rawCheckpoint(connectionId)).cursor();
    }

    private String rawCheckpoint(UUID connectionId) {
        return jdbc().sql("SELECT sync_checkpoint FROM stripe_connections WHERE id = :id")
                .param("id", connectionId)
                .query(String.class)
                .optional()
                .orElse(null);
    }
}
