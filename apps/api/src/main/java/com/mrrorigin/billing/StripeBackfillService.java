package com.mrrorigin.billing;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.mrrorigin.billing.BillingSourceVersion.SourceVersion;
import com.mrrorigin.billing.StripeBackfillClient.StripePage;
import com.mrrorigin.billing.StripeBackfillPageRunner.PageApplyOutcome;
import com.mrrorigin.billing.StripeBackfillPageRunner.PageApplyStatus;

/**
 * Orchestrates a bounded slice of one connection's resumable Stripe backfill: fetch one page at a
 * time (never an unbounded scan), normalize it, and durably advance the checkpoint, per #12.
 *
 * <p>Each page fetch (network I/O against Stripe) deliberately happens outside any database
 * transaction; only {@link StripeBackfillPageRunner#applyPage} -- normalizing the page and moving
 * the checkpoint -- is transactional. This includes completing any paginated subscription item
 * lists ({@link StripeSubscriptionItemsResolver}): resolved before {@code applyPage} is called, not
 * from inside its normalizer callback, so no network round-trip ever happens while the connection
 * row's lock is held. A page is only ever "in flight" (fetched but not yet durably applied) for the
 * instant between fetch and {@code applyPage}, which is exactly the window its atomicity closes.
 *
 * <p>Backfill only ever runs for a connection that is currently ACTIVE and VERIFIED. Eligibility is
 * checked three times, for three different purposes: once before the loop even starts ({@link
 * #requireEligible}, throws -- the caller shouldn't have invoked backfill at all), once at the top
 * of every iteration before issuing that page's Stripe request (a plain re-check -- no point
 * spending an API call once the connection is known to be ineligible), and once more inside {@link
 * StripeBackfillPageRunner#applyPage} under the same lock that guards the checkpoint write (closes
 * the residual race: the connection could still be disconnected/revoked while that page's fetch was
 * in flight).
 */
@Service
class StripeBackfillService {

    private final StripeConnectionRepository connections;
    private final StripeBackfillClient client;
    private final StripeBackfillPageRunner pageRunner;
    private final StripeSubscriptionItemsResolver subscriptionItemsResolver;
    private final BillingLedgerUpsertService ledger;
    private final ObjectMapper objectMapper;

    StripeBackfillService(
            StripeConnectionRepository connections,
            StripeBackfillClient client,
            StripeBackfillPageRunner pageRunner,
            StripeSubscriptionItemsResolver subscriptionItemsResolver,
            BillingLedgerUpsertService ledger,
            ObjectMapper objectMapper) {
        this.connections = connections;
        this.client = client;
        this.pageRunner = pageRunner;
        this.subscriptionItemsResolver = subscriptionItemsResolver;
        this.ledger = ledger;
        this.objectMapper = objectMapper;
    }

    /**
     * Runs until {@code maxPages} pages have been successfully APPLIED for the given connection, or
     * the backfill completes, or the connection stops being eligible. STALE outcomes (a page whose
     * expected phase/cursor no longer matches -- see {@link StripeBackfillPageRunner}) do not count
     * against {@code maxPages}; the loop simply re-reads the current checkpoint and tries again,
     * bounded by a generous safety cap on total iterations so a pathological run of races can never
     * spin forever. Safe to call repeatedly (e.g. by a future scheduler in #15): each call resumes
     * from whatever checkpoint the previous call (or none) left behind.
     *
     * @throws StripeBackfillIneligibleConnectionException if the connection is not currently
     *     ACTIVE and VERIFIED
     */
    BackfillRunOutcome runBatch(UUID connectionId, int maxPages) {
        requireEligible(loadConnection(connectionId));

        int pagesApplied = 0;
        int iterations = 0;
        int maxIterations = Math.max(maxPages * 4, 10);
        PageApplyOutcome last = null;

        while (pagesApplied < maxPages && iterations < maxIterations) {
            iterations++;
            StripeConnection connection = loadConnection(connectionId);
            if (!isEligible(connection)) {
                StripeBackfillCheckpoint currentCheckpoint =
                        StripeBackfillCheckpoint.parse(objectMapper, connection.syncCheckpoint());
                last = PageApplyOutcome.connectionIneligible(currentCheckpoint.phase(), currentCheckpoint.isComplete());
                break;
            }

            StripeBackfillCheckpoint checkpoint =
                    StripeBackfillCheckpoint.parse(objectMapper, connection.syncCheckpoint());
            if (checkpoint.isComplete()) {
                last = PageApplyOutcome.applied(checkpoint.phase(), true);
                break;
            }

            UUID workspaceId = connection.workspaceId();
            StripeConnectionMode mode = connection.mode();
            String stripeAccountId = connection.stripeAccountId();
            String cursor = checkpoint.cursor();
            StripeBackfillPhase phase = checkpoint.phase();

            SourceVersion sourceVersion = BillingSourceVersion.forBackfillFetch(Instant.now());
            StripePage page = fetchPage(phase, mode, stripeAccountId, cursor);
            Map<String, List<JsonNode>> supplementalItemsBySubscriptionId = phase == StripeBackfillPhase.SUBSCRIPTIONS
                    ? resolveSupplementalItemsForPage(mode, stripeAccountId, page.data())
                    : Map.of();
            Consumer<JsonNode> normalizer =
                    normalizerFor(phase, workspaceId, sourceVersion, supplementalItemsBySubscriptionId);

            last = pageRunner.applyPage(connectionId, phase, cursor, page.data(), page.hasMore(), normalizer);
            if (last.status() == PageApplyStatus.APPLIED) {
                pagesApplied++;
            }
            if (last.status() == PageApplyStatus.CONNECTION_INELIGIBLE) {
                // The connection was disconnected/revoked between this page's fetch and its
                // (rejected) application. Stop immediately rather than fetching further pages that
                // would only be rejected the same way.
                break;
            }
            if (last.complete()) {
                break;
            }
            // STALE: a concurrent run already advanced past this page. Loop again -- the next
            // iteration re-reads the checkpoint and fetches from wherever it actually is now.
        }

        if (last == null) {
            // maxPages was 0: report the connection's current position without doing any work.
            StripeBackfillCheckpoint checkpoint =
                    StripeBackfillCheckpoint.parse(objectMapper, loadConnection(connectionId).syncCheckpoint());
            last = PageApplyOutcome.applied(checkpoint.phase(), checkpoint.isComplete());
        }
        boolean connectionEligible = last.status() != PageApplyStatus.CONNECTION_INELIGIBLE;
        return new BackfillRunOutcome(pagesApplied, last.phase(), last.complete(), connectionEligible);
    }

    private Map<String, List<JsonNode>> resolveSupplementalItemsForPage(
            StripeConnectionMode mode, String stripeAccountId, List<JsonNode> subscriptions) {
        Map<String, List<JsonNode>> bySubscriptionId = new HashMap<>();
        for (JsonNode subscription : subscriptions) {
            List<JsonNode> supplemental = subscriptionItemsResolver.resolveSupplementalItems(mode, stripeAccountId, subscription);
            if (!supplemental.isEmpty()) {
                bySubscriptionId.put(StripeBillingObjectParser.subscriptionId(subscription), supplemental);
            }
        }
        return bySubscriptionId;
    }

    private StripeConnection loadConnection(UUID connectionId) {
        return connections
                .findById(connectionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Stripe connection not found"));
    }

    private void requireEligible(StripeConnection connection) {
        if (!isEligible(connection)) {
            throw new StripeBackfillIneligibleConnectionException(
                    "Stripe connection " + connection.id() + " is not ACTIVE/VERIFIED (status=" + connection.status()
                            + ", verificationStatus=" + connection.verificationStatus() + ")");
        }
    }

    private boolean isEligible(StripeConnection connection) {
        return connection.status() == StripeConnectionStatus.ACTIVE && connection.verificationStatus() == StripeVerificationStatus.VERIFIED;
    }

    private StripePage fetchPage(StripeBackfillPhase phase, StripeConnectionMode mode, String stripeAccountId, String cursor) {
        return switch (phase) {
            case CUSTOMERS -> client.listCustomers(mode, stripeAccountId, cursor);
            case PRICES -> client.listPrices(mode, stripeAccountId, cursor);
            case SUBSCRIPTIONS -> client.listSubscriptions(mode, stripeAccountId, cursor);
            case INVOICES -> client.listInvoices(mode, stripeAccountId, cursor);
            case CHARGES -> client.listCharges(mode, stripeAccountId, cursor);
            case REFUNDS -> client.listRefunds(mode, stripeAccountId, cursor);
            case DONE -> new StripePage(List.of(), false);
        };
    }

    private Consumer<JsonNode> normalizerFor(
            StripeBackfillPhase phase,
            UUID workspaceId,
            SourceVersion sourceVersion,
            Map<String, List<JsonNode>> supplementalItemsBySubscriptionId) {
        return switch (phase) {
            case CUSTOMERS -> item -> ledger.upsertCustomer(
                    workspaceId, StripeBillingObjectParser.parseCustomer(item), sourceVersion, BillingLedgerSource.BACKFILL);
            case PRICES -> item -> ledger.upsertPrice(
                    workspaceId, StripeBillingObjectParser.parsePrice(item), sourceVersion, BillingLedgerSource.BACKFILL);
            case SUBSCRIPTIONS -> item -> {
                List<JsonNode> supplemental = supplementalItemsBySubscriptionId.getOrDefault(
                        StripeBillingObjectParser.subscriptionId(item), List.of());
                ledger.upsertSubscription(
                        workspaceId,
                        StripeBillingObjectParser.parseSubscription(item, supplemental),
                        sourceVersion,
                        BillingLedgerSource.BACKFILL);
            };
            case INVOICES -> item -> ledger.upsertInvoice(
                    workspaceId, StripeBillingObjectParser.parseInvoice(item), sourceVersion, BillingLedgerSource.BACKFILL);
            case CHARGES -> item -> ledger.upsertPayment(
                    workspaceId, StripeBillingObjectParser.parseCharge(item), sourceVersion, BillingLedgerSource.BACKFILL);
            case REFUNDS -> item -> ledger.upsertRefund(
                    workspaceId, StripeBillingObjectParser.parseRefund(item), sourceVersion, BillingLedgerSource.BACKFILL);
            case DONE -> item -> {};
        };
    }

    record BackfillRunOutcome(int pagesProcessed, StripeBackfillPhase phase, boolean complete, boolean connectionEligible) {}
}
