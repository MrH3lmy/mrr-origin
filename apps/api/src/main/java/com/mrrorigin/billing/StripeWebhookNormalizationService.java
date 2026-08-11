package com.mrrorigin.billing;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.mrrorigin.billing.BillingSourceVersion.SourceVersion;

/**
 * Normalizes verified, durably stored Stripe webhook events (V5, #11) into the billing ledger
 * (V7, #12), so the webhook and backfill paths converge on the same normalized state per #12's
 * acceptance criteria. Only the bookkeeping columns V5 reserved for this worker --
 * {@code processing_state}, {@code attempt_count}, {@code last_attempted_at}, {@code last_error},
 * {@code updated_at} -- are ever written here; {@code raw_payload}/{@code payload} are read-only.
 *
 * <p><b>Claim, then work outside any transaction, then apply.</b> {@link #processBatch} runs in
 * three phases, deliberately never holding a database transaction open across a Stripe network
 * call:
 *
 * <ol>
 *   <li>{@link #claimBatch} -- one short transaction. {@code SELECT ... FOR UPDATE SKIP LOCKED}
 *       picks up to {@code batchSize} PENDING rows whose lease has expired (or never existed), and
 *       an {@code UPDATE ... FROM} in the same statement stamps a fresh lease ({@code
 *       last_attempted_at}, {@code attempt_count}) before the transaction commits and every lock
 *       releases. {@code processing_state} itself stays PENDING throughout -- no new state, no V5
 *       schema change -- the lease is what stops a second caller from re-claiming the same row
 *       while this one is still working it.
 *   <li>{@link #prepareNormalization} -- no transaction at all. Parses the event and performs
 *       whatever Stripe API calls completing it requires (paginated subscription items, or a
 *       fully-expanded re-fetch when discounts arrived unexpanded), returning a pure-DB closure to
 *       run next.
 *   <li>{@link #applyAndMarkProcessed} -- one short transaction per event: the closure's ledger
 *       write and marking the row PROCESSED commit atomically together.
 * </ol>
 *
 * <p>If the process crashes between phases, the row is simply left leased; once the lease expires
 * (see {@link #LEASE_DURATION}) another call to {@code processBatch} reclaims and reprocesses it
 * from scratch -- safe, since every ledger upsert is idempotent.
 */
@Service
class StripeWebhookNormalizationService {

    /**
     * How long a claimed-but-not-yet-completed row is protected from being reclaimed by another
     * caller. Comfortably longer than any single event's network + DB work is expected to take;
     * a crash mid-event simply costs waiting out the rest of this window before retry.
     */
    private static final Duration LEASE_DURATION = Duration.ofMinutes(5);

    /** Event types #12 normalizes; every other type is acknowledged (PROCESSED) with no ledger effect. */
    private static final Set<String> CUSTOMER_EVENTS = Set.of("customer.created", "customer.updated", "customer.deleted");
    private static final Set<String> PRICE_EVENTS = Set.of("price.created", "price.updated");
    private static final Set<String> SUBSCRIPTION_EVENTS =
            Set.of("customer.subscription.created", "customer.subscription.updated", "customer.subscription.deleted");
    private static final Set<String> INVOICE_EVENTS = Set.of(
            "invoice.created", "invoice.updated", "invoice.finalized", "invoice.paid", "invoice.payment_failed", "invoice.voided");
    private static final Set<String> CHARGE_EVENTS =
            Set.of("charge.succeeded", "charge.updated", "charge.refunded", "charge.failed");
    private static final Set<String> REFUND_EVENTS = Set.of("refund.created", "refund.updated");
    private static final Set<String> DISCOUNT_UPSERT_EVENTS = Set.of("customer.discount.created", "customer.discount.updated");
    private static final String DISCOUNT_DELETE_EVENT = "customer.discount.deleted";

    private final JdbcClient jdbc;
    private final BillingLedgerUpsertService ledger;
    private final StripeSubscriptionItemsResolver subscriptionItemsResolver;
    private final StripeBackfillClient stripeClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    StripeWebhookNormalizationService(
            JdbcClient jdbc,
            BillingLedgerUpsertService ledger,
            StripeSubscriptionItemsResolver subscriptionItemsResolver,
            StripeBackfillClient stripeClient,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.subscriptionItemsResolver = subscriptionItemsResolver;
        this.stripeClient = stripeClient;
        this.objectMapper = objectMapper;
        // Used programmatically (not the @Transactional annotation) for both claimBatch and
        // applyAndMarkProcessed: processBatch calls them on `this`, and an annotation-driven
        // transaction is silently skipped on such self-invocation (it only applies through the
        // Spring-generated proxy). TransactionTemplate opens/commits the transaction directly,
        // regardless of how it's called.
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    NormalizationRunOutcome processBatch(int batchSize) {
        List<PendingEvent> claimed = claimBatch(batchSize);

        int processed = 0;
        int skipped = 0;
        int failed = 0;
        for (PendingEvent event : claimed) {
            try {
                // Parsing and any Stripe API calls needed to complete the event (paginated
                // subscription items, or a fully-expanded re-fetch) happen here, outside of any
                // database transaction and after the claim transaction has already committed --
                // only the resulting pure-DB action runs inside applyAndMarkProcessed below.
                BooleanSupplier action = prepareNormalization(event);
                boolean handled = applyAndMarkProcessed(event.id(), action);
                if (handled) {
                    processed++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException failure) {
                markFailed(event.id(), failure.getMessage());
                failed++;
            }
        }
        return new NormalizationRunOutcome(claimed.size(), processed, skipped, failed);
    }

    private List<PendingEvent> claimBatch(int batchSize) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime leaseCutoff = now.minus(LEASE_DURATION);
        List<PendingEvent> claimed = transactionTemplate.execute(status -> jdbc.sql(
                        """
                        WITH claimable AS (
                            SELECT id FROM stripe_webhook_events
                            WHERE processing_state = 'PENDING'
                              AND (last_attempted_at IS NULL OR last_attempted_at < :leaseCutoff)
                            ORDER BY received_at ASC
                            LIMIT :batchSize
                            FOR UPDATE SKIP LOCKED
                        ),
                        claimed AS (
                            UPDATE stripe_webhook_events swe
                            SET last_attempted_at = :now, attempt_count = attempt_count + 1, updated_at = :now
                            FROM claimable
                            WHERE swe.id = claimable.id
                            RETURNING swe.id, swe.workspace_id, swe.event_type, swe.payload,
                                      swe.stripe_created_at, swe.received_at, swe.ingest_sequence,
                                      swe.connection_id
                        )
                        SELECT claimed.id AS id, claimed.workspace_id AS workspace_id,
                               claimed.event_type AS event_type, claimed.payload AS payload,
                               claimed.stripe_created_at AS stripe_created_at,
                               claimed.received_at AS received_at,
                               claimed.ingest_sequence AS ingest_sequence,
                               sc.mode AS connection_mode, sc.stripe_account_id AS stripe_account_id
                        FROM claimed JOIN stripe_connections sc ON sc.id = claimed.connection_id
                        """)
                .param("batchSize", batchSize)
                .param("now", now)
                .param("leaseCutoff", leaseCutoff)
                .query((rs, rowNum) -> new PendingEvent(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("workspace_id")),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getObject("stripe_created_at", OffsetDateTime.class),
                        rs.getObject("received_at", OffsetDateTime.class),
                        rs.getLong("ingest_sequence"),
                        StripeConnectionMode.valueOf(rs.getString("connection_mode")),
                        rs.getString("stripe_account_id")))
                .list());
        return claimed == null ? List.of() : claimed;
    }

    private boolean applyAndMarkProcessed(UUID eventId, BooleanSupplier action) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            boolean handled = action.getAsBoolean();
            markProcessed(eventId);
            return handled;
        }));
    }

    private BooleanSupplier prepareNormalization(PendingEvent event) {
        JsonNode root = objectMapper.readTree(event.payload());
        JsonNode data = root == null ? null : root.get("data");
        JsonNode dataObject = data == null ? null : data.get("object");
        if (dataObject == null || !dataObject.isObject()) {
            throw new StripeBillingNormalizationException("Stripe webhook event payload has no data.object");
        }

        SourceVersion sourceVersion =
                BillingSourceVersion.forWebhookEvent(event.stripeCreatedAt(), event.receivedAt(), event.ingestSequence());
        UUID workspaceId = event.workspaceId();
        String type = event.eventType();

        if (CUSTOMER_EVENTS.contains(type)) {
            var parsed = StripeBillingObjectParser.parseCustomer(dataObject);
            return () -> {
                ledger.upsertCustomer(workspaceId, parsed, sourceVersion, BillingLedgerSource.WEBHOOK);
                return true;
            };
        }
        if (PRICE_EVENTS.contains(type)) {
            var parsed = StripeBillingObjectParser.parsePrice(dataObject);
            return () -> {
                ledger.upsertPrice(workspaceId, parsed, sourceVersion, BillingLedgerSource.WEBHOOK);
                return true;
            };
        }
        if (SUBSCRIPTION_EVENTS.contains(type)) {
            JsonNode subscriptionNode = dataObject;
            if (StripeBillingObjectParser.hasUnexpandedDiscounts(subscriptionNode)) {
                // Webhooks cannot be delivered with expand[] applied, so a push-delivered discount
                // reference can arrive as a bare ID. Rather than silently normalizing a partial
                // discount set, fetch the subscription's current, fully-expanded representation
                // directly and use that as the authoritative source for this event instead.
                subscriptionNode = stripeClient.getSubscription(
                        event.connectionMode(), event.stripeAccountId(), StripeBillingObjectParser.subscriptionId(subscriptionNode));
            }
            List<JsonNode> supplementalItems =
                    subscriptionItemsResolver.resolveSupplementalItems(event.connectionMode(), event.stripeAccountId(), subscriptionNode);
            var parsed = StripeBillingObjectParser.parseSubscription(subscriptionNode, supplementalItems);
            return () -> {
                ledger.upsertSubscription(workspaceId, parsed, sourceVersion, BillingLedgerSource.WEBHOOK);
                return true;
            };
        }
        if (INVOICE_EVENTS.contains(type)) {
            var parsed = StripeBillingObjectParser.parseInvoice(dataObject);
            return () -> {
                ledger.upsertInvoice(workspaceId, parsed, sourceVersion, BillingLedgerSource.WEBHOOK);
                return true;
            };
        }
        if (CHARGE_EVENTS.contains(type)) {
            var parsed = StripeBillingObjectParser.parseCharge(dataObject);
            return () -> {
                ledger.upsertPayment(workspaceId, parsed, sourceVersion, BillingLedgerSource.WEBHOOK);
                return true;
            };
        }
        if (REFUND_EVENTS.contains(type)) {
            var parsed = StripeBillingObjectParser.parseRefund(dataObject);
            return () -> {
                ledger.upsertRefund(workspaceId, parsed, sourceVersion, BillingLedgerSource.WEBHOOK);
                return true;
            };
        }
        if (DISCOUNT_UPSERT_EVENTS.contains(type)) {
            var parsed = StripeBillingObjectParser.parseTopLevelDiscount(dataObject, false);
            return () -> {
                ledger.upsertDiscount(workspaceId, parsed, sourceVersion, BillingLedgerSource.WEBHOOK);
                return true;
            };
        }
        if (DISCOUNT_DELETE_EVENT.equals(type)) {
            var parsed = StripeBillingObjectParser.parseTopLevelDiscount(dataObject, true);
            return () -> {
                ledger.upsertDiscount(workspaceId, parsed, sourceVersion, BillingLedgerSource.WEBHOOK);
                return true;
            };
        }
        return () -> false;
    }

    /** Attempt/lease bookkeeping (attempt_count, last_attempted_at) was already stamped at claim time. */
    private void markProcessed(UUID eventId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql(
                        """
                        UPDATE stripe_webhook_events
                        SET processing_state = 'PROCESSED', last_error = NULL, updated_at = :now
                        WHERE id = :id
                        """)
                .param("id", eventId)
                .param("now", now)
                .update();
    }

    private void markFailed(UUID eventId, String error) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql(
                        """
                        UPDATE stripe_webhook_events
                        SET processing_state = 'FAILED', last_error = :error, updated_at = :now
                        WHERE id = :id
                        """)
                .param("id", eventId)
                .param("now", now)
                .param("error", error)
                .update();
    }

    private record PendingEvent(
            UUID id,
            UUID workspaceId,
            String eventType,
            String payload,
            OffsetDateTime stripeCreatedAt,
            OffsetDateTime receivedAt,
            long ingestSequence,
            StripeConnectionMode connectionMode,
            String stripeAccountId) {}

    record NormalizationRunOutcome(int fetched, int processed, int skipped, int failed) {}
}
