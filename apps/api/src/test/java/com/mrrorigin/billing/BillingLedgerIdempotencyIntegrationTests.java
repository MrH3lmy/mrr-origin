package com.mrrorigin.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * #12's duplicate-delivery and out-of-order guarantees at the normalization layer: reprocessing
 * the same event twice never creates a second row or a spurious status transition, and an older
 * snapshot arriving after a newer one is applied never regresses the ledger.
 */
@Testcontainers
class BillingLedgerIdempotencyIntegrationTests extends AbstractBillingLedgerIntegrationTest {

    @Test
    void duplicateWebhookDeliveryOfTheSameEventNormalizesExactlyOnce() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_dup", StripeConnectionMode.TEST);
        Instant createdAt = Instant.parse("2026-02-01T00:00:00Z");
        String object = BillingFixtures.subscription(
                "sub_dup",
                "cus_dup",
                "active",
                "usd",
                createdAt.getEpochSecond(),
                createdAt.getEpochSecond() + 2_592_000L,
                false,
                null,
                null,
                BillingFixtures.subscriptionItem("si_dup", "price_dup", 2),
                null);

        // Two separate PENDING rows carrying the identical object state and identical
        // stripe_created_at -- e.g. our own worker crashing after normalizing but before marking
        // PROCESSED, and being re-fed the same logical delivery on restart.
        insertPendingWebhookEvent(
                connectionId, workspaceId, StripeConnectionMode.TEST, "evt_dup_1", "customer.subscription.updated", createdAt, object);
        insertPendingWebhookEvent(
                connectionId, workspaceId, StripeConnectionMode.TEST, "evt_dup_2", "customer.subscription.updated", createdAt, object);

        assertThat(drainWebhookQueue()).isEqualTo(2);

        assertThat(countRows("billing_subscriptions", workspaceId)).isEqualTo(1);
        assertThat(subscriptionItemSnapshots(workspaceId, "sub_dup")).hasSize(1);
        // Same source_version both times: no status change is recorded twice.
        assertThat(subscriptionStatusEventCount(workspaceId, "sub_dup")).isEqualTo(1);
    }

    @Test
    void anOlderSnapshotArrivingAfterANewerOneNeverRegressesTheLedger() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_ooo", StripeConnectionMode.TEST);
        Instant older = Instant.parse("2026-02-01T00:00:00Z");
        Instant newer = older.plusSeconds(600);

        String newState = BillingFixtures.invoice("in_ooo", "cus_ooo", null, "paid", "usd", 500, 500, 0, older.getEpochSecond(), newer.getEpochSecond(), older.getEpochSecond());
        String staleState = BillingFixtures.invoice("in_ooo", "cus_ooo", null, "open", "usd", 500, 0, 500, older.getEpochSecond(), newer.getEpochSecond(), older.getEpochSecond());

        // The newer state is normalized first (e.g. Stripe redelivering out of order, or a backfill
        // page racing ahead of a delayed webhook), then a stale/delayed event for the same object
        // arrives afterward.
        insertPendingWebhookEvent(connectionId, workspaceId, StripeConnectionMode.TEST, "evt_new", "invoice.paid", newer, newState);
        assertThat(drainWebhookQueue()).isEqualTo(1);
        assertThat(invoiceSnapshot(workspaceId, "in_ooo").orElseThrow()).containsEntry("status", "paid").containsEntry("amount_paid", 500L);

        insertPendingWebhookEvent(connectionId, workspaceId, StripeConnectionMode.TEST, "evt_stale", "invoice.created", older, staleState);
        assertThat(drainWebhookQueue()).isEqualTo(1);

        assertThat(invoiceSnapshot(workspaceId, "in_ooo").orElseThrow()).containsEntry("status", "paid").containsEntry("amount_paid", 500L);
    }

    @Test
    void aGenuinelyNewerWebhookAfterAStaleOneCorrectlyWins() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_ooo2", StripeConnectionMode.TEST);
        Instant older = Instant.parse("2026-02-01T00:00:00Z");
        Instant newer = older.plusSeconds(600);

        String openState = BillingFixtures.invoice("in_ooo2", "cus_ooo2", null, "open", "usd", 500, 0, 500, older.getEpochSecond(), newer.getEpochSecond(), older.getEpochSecond());
        String paidState = BillingFixtures.invoice("in_ooo2", "cus_ooo2", null, "paid", "usd", 500, 500, 0, older.getEpochSecond(), newer.getEpochSecond(), older.getEpochSecond());

        insertPendingWebhookEvent(connectionId, workspaceId, StripeConnectionMode.TEST, "evt_open", "invoice.created", older, openState);
        assertThat(drainWebhookQueue()).isEqualTo(1);
        assertThat(invoiceSnapshot(workspaceId, "in_ooo2").orElseThrow()).containsEntry("status", "open");

        insertPendingWebhookEvent(connectionId, workspaceId, StripeConnectionMode.TEST, "evt_paid", "invoice.paid", newer, paidState);
        assertThat(drainWebhookQueue()).isEqualTo(1);
        assertThat(invoiceSnapshot(workspaceId, "in_ooo2").orElseThrow()).containsEntry("status", "paid").containsEntry("amount_paid", 500L);
    }

    private int countRows(String table, UUID workspaceId) {
        return jdbc().sql("SELECT COUNT(*) FROM " + table + " WHERE workspace_id = :w")
                .param("w", workspaceId)
                .query(Integer.class)
                .single();
    }
}
