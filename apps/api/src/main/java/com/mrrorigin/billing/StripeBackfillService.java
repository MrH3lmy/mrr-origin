package com.mrrorigin.billing;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.mrrorigin.billing.StripeBackfillClient.StripePage;

/**
 * Orchestrates a bounded slice of one connection's resumable Stripe backfill: fetch one page at a
 * time (never an unbounded scan), normalize it, and durably advance the checkpoint, per #12.
 *
 * <p>Each page fetch (network I/O against Stripe) deliberately happens outside any database
 * transaction; only {@link StripeBackfillPageRunner#applyPage} -- normalizing the page and moving
 * the checkpoint -- is transactional. This keeps no transaction open across a network call, and
 * means a page is only ever "in flight" (fetched but not yet durably applied) for the instant
 * between those two steps, which is exactly the window {@code applyPage}'s atomicity closes.
 */
@Service
class StripeBackfillService {

    private final StripeConnectionRepository connections;
    private final StripeBackfillClient client;
    private final StripeBackfillPageRunner pageRunner;
    private final BillingLedgerUpsertService ledger;
    private final ObjectMapper objectMapper;

    StripeBackfillService(
            StripeConnectionRepository connections,
            StripeBackfillClient client,
            StripeBackfillPageRunner pageRunner,
            BillingLedgerUpsertService ledger,
            ObjectMapper objectMapper) {
        this.connections = connections;
        this.client = client;
        this.pageRunner = pageRunner;
        this.ledger = ledger;
        this.objectMapper = objectMapper;
    }

    /**
     * Runs up to {@code maxPages} pages of backfill for the given connection, stopping early if
     * the backfill completes. Safe to call repeatedly (e.g. by a future scheduler in #15): each
     * call resumes from whatever checkpoint the previous call (or none) left behind.
     */
    BackfillRunOutcome runBatch(UUID connectionId, int maxPages) {
        int pagesProcessed = 0;
        StripeBackfillPageRunner.PageApplyOutcome last = null;

        while (pagesProcessed < maxPages) {
            StripeConnection connection = connections
                    .findById(connectionId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Stripe connection not found"));
            StripeBackfillCheckpoint checkpoint =
                    StripeBackfillCheckpoint.parse(objectMapper, connection.syncCheckpoint());
            if (checkpoint.isComplete()) {
                last = new StripeBackfillPageRunner.PageApplyOutcome(checkpoint.phase(), true);
                break;
            }

            UUID workspaceId = connection.workspaceId();
            StripeConnectionMode mode = connection.mode();
            String stripeAccountId = connection.stripeAccountId();

            long sourceVersion = Instant.now().getEpochSecond();
            StripePage page = fetchPage(checkpoint.phase(), mode, stripeAccountId, checkpoint.cursor());
            Consumer<JsonNode> normalizer = normalizerFor(checkpoint.phase(), workspaceId, sourceVersion);

            last = pageRunner.applyPage(connectionId, checkpoint.phase(), page.data(), page.hasMore(), normalizer);
            pagesProcessed++;
            if (last.complete()) {
                break;
            }
        }

        if (last == null) {
            // maxPages was 0: report the connection's current position without doing any work.
            StripeConnection connection = connections
                    .findById(connectionId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Stripe connection not found"));
            StripeBackfillCheckpoint checkpoint =
                    StripeBackfillCheckpoint.parse(objectMapper, connection.syncCheckpoint());
            last = new StripeBackfillPageRunner.PageApplyOutcome(checkpoint.phase(), checkpoint.isComplete());
        }
        return new BackfillRunOutcome(pagesProcessed, last.phase(), last.complete());
    }

    private StripePage fetchPage(StripeBackfillPhase phase, StripeConnectionMode mode, String stripeAccountId, String cursor) {
        return switch (phase) {
            case CUSTOMERS -> client.listCustomers(mode, stripeAccountId, cursor);
            case PRICES -> client.listPrices(mode, stripeAccountId, cursor);
            case SUBSCRIPTIONS -> client.listSubscriptions(mode, stripeAccountId, cursor);
            case INVOICES -> client.listInvoices(mode, stripeAccountId, cursor);
            case CHARGES -> client.listCharges(mode, stripeAccountId, cursor);
            case REFUNDS -> client.listRefunds(mode, stripeAccountId, cursor);
            case DONE -> new StripePage(java.util.List.of(), false);
        };
    }

    private Consumer<JsonNode> normalizerFor(StripeBackfillPhase phase, UUID workspaceId, long sourceVersion) {
        return switch (phase) {
            case CUSTOMERS -> item -> ledger.upsertCustomer(
                    workspaceId, StripeBillingObjectParser.parseCustomer(item), sourceVersion, BillingLedgerSource.BACKFILL);
            case PRICES -> item -> ledger.upsertPrice(
                    workspaceId, StripeBillingObjectParser.parsePrice(item), sourceVersion, BillingLedgerSource.BACKFILL);
            case SUBSCRIPTIONS -> item -> ledger.upsertSubscription(
                    workspaceId, StripeBillingObjectParser.parseSubscription(item), sourceVersion, BillingLedgerSource.BACKFILL);
            case INVOICES -> item -> ledger.upsertInvoice(
                    workspaceId, StripeBillingObjectParser.parseInvoice(item), sourceVersion, BillingLedgerSource.BACKFILL);
            case CHARGES -> item -> ledger.upsertPayment(
                    workspaceId, StripeBillingObjectParser.parseCharge(item), sourceVersion, BillingLedgerSource.BACKFILL);
            case REFUNDS -> item -> ledger.upsertRefund(
                    workspaceId, StripeBillingObjectParser.parseRefund(item), sourceVersion, BillingLedgerSource.BACKFILL);
            case DONE -> item -> {};
        };
    }

    record BackfillRunOutcome(int pagesProcessed, StripeBackfillPhase phase, boolean complete) {}
}
