package com.mrrorigin.billing;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Normalizes verified, durably stored Stripe webhook events (V5, #11) into the billing ledger
 * (V7, #12), so the webhook and backfill paths converge on the same normalized state per #12's
 * acceptance criteria. Only the bookkeeping columns V5 reserved for this worker --
 * {@code processing_state}, {@code attempt_count}, {@code last_attempted_at}, {@code last_error},
 * {@code updated_at} -- are ever written here; {@code raw_payload}/{@code payload} are read-only.
 *
 * <p>{@code FOR UPDATE SKIP LOCKED} lets multiple concurrent callers (a future scheduled worker
 * running with more than one thread, or an overlapping retry) drain the same PENDING queue without
 * double-processing a row or blocking on each other's rows.
 */
@Service
class StripeWebhookNormalizationService {

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
    private final ObjectMapper objectMapper;
    private final TransactionTemplate perEventTransaction;

    StripeWebhookNormalizationService(
            JdbcClient jdbc, BillingLedgerUpsertService ledger, ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.objectMapper = objectMapper;
        // REQUIRES_NEW: a genuinely separate, independently committed/rolled-back transaction per
        // event, not participating in the outer batch transaction. BillingLedgerUpsertService's own
        // @Transactional methods mark their (REQUIRED) transaction rollback-only on any exception,
        // so without this, one failing event would silently doom every other event already applied
        // in the same batch. If the process crashes between an event's inner commit and the outer
        // transaction's commit (which only advances processing_state), that event is simply
        // reselected as PENDING next run and reapplied -- safe, since every ledger upsert is
        // idempotent.
        this.perEventTransaction = new TransactionTemplate(transactionManager);
        this.perEventTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    NormalizationRunOutcome processBatch(int batchSize) {
        List<PendingEvent> events = jdbc.sql(
                        """
                        SELECT id, workspace_id, event_type, payload, stripe_created_at
                        FROM stripe_webhook_events
                        WHERE processing_state = 'PENDING'
                        ORDER BY received_at ASC
                        LIMIT :batchSize
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("batchSize", batchSize)
                .query((rs, rowNum) -> new PendingEvent(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("workspace_id")),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getObject("stripe_created_at", OffsetDateTime.class)))
                .list();

        int processed = 0;
        int skipped = 0;
        int failed = 0;
        for (PendingEvent event : events) {
            try {
                boolean handled = Boolean.TRUE.equals(perEventTransaction.execute(status -> normalize(event)));
                markProcessed(event.id());
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
        return new NormalizationRunOutcome(events.size(), processed, skipped, failed);
    }

    private boolean normalize(PendingEvent event) {
        JsonNode root = objectMapper.readTree(event.payload());
        JsonNode data = root == null ? null : root.get("data");
        JsonNode dataObject = data == null ? null : data.get("object");
        if (dataObject == null || !dataObject.isObject()) {
            throw new StripeBillingNormalizationException("Stripe webhook event payload has no data.object");
        }

        long sourceVersion = event.stripeCreatedAt().toEpochSecond();
        UUID workspaceId = event.workspaceId();
        String type = event.eventType();

        if (CUSTOMER_EVENTS.contains(type)) {
            ledger.upsertCustomer(
                    workspaceId, StripeBillingObjectParser.parseCustomer(dataObject), sourceVersion, BillingLedgerSource.WEBHOOK);
            return true;
        }
        if (PRICE_EVENTS.contains(type)) {
            ledger.upsertPrice(
                    workspaceId, StripeBillingObjectParser.parsePrice(dataObject), sourceVersion, BillingLedgerSource.WEBHOOK);
            return true;
        }
        if (SUBSCRIPTION_EVENTS.contains(type)) {
            ledger.upsertSubscription(
                    workspaceId, StripeBillingObjectParser.parseSubscription(dataObject), sourceVersion, BillingLedgerSource.WEBHOOK);
            return true;
        }
        if (INVOICE_EVENTS.contains(type)) {
            ledger.upsertInvoice(
                    workspaceId, StripeBillingObjectParser.parseInvoice(dataObject), sourceVersion, BillingLedgerSource.WEBHOOK);
            return true;
        }
        if (CHARGE_EVENTS.contains(type)) {
            ledger.upsertPayment(
                    workspaceId, StripeBillingObjectParser.parseCharge(dataObject), sourceVersion, BillingLedgerSource.WEBHOOK);
            return true;
        }
        if (REFUND_EVENTS.contains(type)) {
            ledger.upsertRefund(
                    workspaceId, StripeBillingObjectParser.parseRefund(dataObject), sourceVersion, BillingLedgerSource.WEBHOOK);
            return true;
        }
        if (DISCOUNT_UPSERT_EVENTS.contains(type)) {
            ledger.upsertDiscount(
                    workspaceId,
                    StripeBillingObjectParser.parseTopLevelDiscount(dataObject, false),
                    sourceVersion,
                    BillingLedgerSource.WEBHOOK);
            return true;
        }
        if (DISCOUNT_DELETE_EVENT.equals(type)) {
            ledger.upsertDiscount(
                    workspaceId,
                    StripeBillingObjectParser.parseTopLevelDiscount(dataObject, true),
                    sourceVersion,
                    BillingLedgerSource.WEBHOOK);
            return true;
        }
        return false;
    }

    private void markProcessed(UUID eventId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql(
                        """
                        UPDATE stripe_webhook_events
                        SET processing_state = 'PROCESSED', attempt_count = attempt_count + 1,
                            last_attempted_at = :now, last_error = NULL, updated_at = :now
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
                        SET processing_state = 'FAILED', attempt_count = attempt_count + 1,
                            last_attempted_at = :now, last_error = :error, updated_at = :now
                        WHERE id = :id
                        """)
                .param("id", eventId)
                .param("now", now)
                .param("error", error)
                .update();
    }

    private record PendingEvent(
            UUID id, UUID workspaceId, String eventType, String payload, OffsetDateTime stripeCreatedAt) {}

    record NormalizationRunOutcome(int fetched, int processed, int skipped, int failed) {}
}
