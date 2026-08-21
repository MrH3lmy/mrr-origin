package com.mrrorigin.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * #12's duplicate-delivery and out-of-order guarantees at the normalization layer: reprocessing
 * the same event twice never creates a second row or a spurious status transition, and an older
 * snapshot arriving after a newer one is applied never regresses the ledger.
 */
@Testcontainers
class BillingLedgerIdempotencyIntegrationTests extends AbstractBillingLedgerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    @Test
    void duplicateWebhookDeliveryOfTheSameEventNormalizesExactlyOnce() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_dup", StripeConnectionMode.TEST);
        seedPrice(workspaceId, "price_dup");
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

    @Test
    void sameSourceVersionAppliesDeterministicallyRegardlessOfApplicationOrder() {
        // BillingSourceVersion's stable source key is what makes this possible: two
        // updates sharing an identical provider second must still converge on the SAME winner no
        // matter which one a caller happens to apply first. Proven directly against
        // BillingLedgerUpsertService, not through the webhook queue, so the outcome does not
        // depend on incidental claim ordering.
        UUID workspaceForward = createWorkspace();
        UUID workspaceReversed = createWorkspace();
        long sharedVersion = 1_800_000_000L;
        BillingSourceVersion.SourceVersion lower = new BillingSourceVersion.SourceVersion(sharedVersion, "W:evt_tie_a");
        BillingSourceVersion.SourceVersion higher = new BillingSourceVersion.SourceVersion(sharedVersion, "W:evt_tie_b");

        var trialing = subscriptionFor("sub_tie", "cus_tie", "trialing");
        var active = subscriptionFor("sub_tie", "cus_tie", "active");

        // Forward order: lower sequence (trialing) applied, then higher sequence (active).
        ledger.upsertSubscription(workspaceForward, trialing, lower, BillingLedgerSource.WEBHOOK);
        ledger.upsertSubscription(workspaceForward, active, higher, BillingLedgerSource.WEBHOOK);

        // Reversed order: higher sequence (active) applied FIRST, then lower sequence (trialing)
        // arrives after -- it must be rejected as stale despite sharing the same `version`.
        ledger.upsertSubscription(workspaceReversed, active, higher, BillingLedgerSource.WEBHOOK);
        ledger.upsertSubscription(workspaceReversed, trialing, lower, BillingLedgerSource.WEBHOOK);

        assertThat(subscriptionSnapshot(workspaceForward, "sub_tie").orElseThrow()).containsEntry("status", "active");
        assertThat(subscriptionSnapshot(workspaceReversed, "sub_tie").orElseThrow()).containsEntry("status", "active");
    }

    @Test
    void sameSecondWebhookEventsConvergeIndependentlyOfArrivalOrder() {
        UUID workspaceId = createWorkspace();
        UUID connectionId = insertActiveConnection(workspaceId, "acct_identical_ts", StripeConnectionMode.TEST);
        seedPrice(workspaceId, "price_x");
        Instant sameCreatedAt = Instant.parse("2026-02-01T00:00:00Z");
        Instant sameReceivedAt = Instant.parse("2026-02-01T00:00:05.123456Z");

        String trialingObject = BillingFixtures.subscription(
                "sub_identical_ts", "cus_identical_ts", "trialing", "usd", sameCreatedAt.getEpochSecond(),
                sameCreatedAt.getEpochSecond() + 2_592_000L, false, null, null,
                BillingFixtures.subscriptionItem("si_identical_ts", "price_x", 1), null);
        String activeObject = BillingFixtures.subscription(
                "sub_identical_ts", "cus_identical_ts", "active", "usd", sameCreatedAt.getEpochSecond(),
                sameCreatedAt.getEpochSecond() + 2_592_000L, false, null, null,
                BillingFixtures.subscriptionItem("si_identical_ts", "price_x", 1), null);

        // Deliver the lexically later event FIRST and the earlier event SECOND. A local arrival
        // sequence would incorrectly let the stale trialing state win; the stable Stripe event-ID
        // tie-breaker must keep the result independent of delivery/insert order.
        insertPendingWebhookEvent(
                connectionId, workspaceId, StripeConnectionMode.TEST, "evt_identical_ts_2", "customer.subscription.updated",
                sameCreatedAt, sameReceivedAt, activeObject);
        insertPendingWebhookEvent(
                connectionId, workspaceId, StripeConnectionMode.TEST, "evt_identical_ts_1", "customer.subscription.created",
                sameCreatedAt, sameReceivedAt, trialingObject);

        assertThat(drainWebhookQueue()).isEqualTo(2);

        // The stable higher event ID wins even though it arrived first.
        assertThat(subscriptionSnapshot(workspaceId, "sub_identical_ts").orElseThrow()).containsEntry("status", "active");
    }

    @Test
    void sameSecondBackfillCannotOverwriteANewerWebhook() {
        UUID forwardWorkspace = createWorkspace();
        UUID reversedWorkspace = createWorkspace();
        Instant providerSecond = Instant.parse("2026-02-01T00:00:00Z");

        var staleBackfill = subscriptionFor("sub_cross_source", "cus_cross_source", "trialing");
        var newerWebhook = subscriptionFor("sub_cross_source", "cus_cross_source", "active");
        var backfillVersion = BillingSourceVersion.forBackfillFetch(providerSecond.plusMillis(100));
        var webhookVersion = BillingSourceVersion.forWebhookEvent(
                providerSecond.atOffset(java.time.ZoneOffset.UTC), "evt_cross_source_active");

        ledger.upsertSubscription(forwardWorkspace, staleBackfill, backfillVersion, BillingLedgerSource.BACKFILL);
        ledger.upsertSubscription(forwardWorkspace, newerWebhook, webhookVersion, BillingLedgerSource.WEBHOOK);

        ledger.upsertSubscription(reversedWorkspace, newerWebhook, webhookVersion, BillingLedgerSource.WEBHOOK);
        ledger.upsertSubscription(reversedWorkspace, staleBackfill, backfillVersion, BillingLedgerSource.BACKFILL);

        assertThat(subscriptionSnapshot(forwardWorkspace, "sub_cross_source").orElseThrow()).containsEntry("status", "active");
        assertThat(subscriptionSnapshot(reversedWorkspace, "sub_cross_source").orElseThrow()).containsEntry("status", "active");
    }

    @Test
    void nextSecondBackfillBecomesAuthoritativeForThePriorSecond() {
        UUID forwardWorkspace = createWorkspace();
        UUID reversedWorkspace = createWorkspace();
        Instant webhookSecond = Instant.parse("2026-02-01T00:00:00Z");

        var webhookState = subscriptionFor("sub_reconciled", "cus_reconciled", "active");
        var reconciledBackfill = subscriptionFor("sub_reconciled", "cus_reconciled", "canceled");
        var webhookVersion = BillingSourceVersion.forWebhookEvent(
                webhookSecond.atOffset(java.time.ZoneOffset.UTC), "evt_reconciled_active");
        var backfillVersion = BillingSourceVersion.forBackfillFetch(webhookSecond.plusSeconds(1).plusMillis(100));

        ledger.upsertSubscription(forwardWorkspace, webhookState, webhookVersion, BillingLedgerSource.WEBHOOK);
        ledger.upsertSubscription(forwardWorkspace, reconciledBackfill, backfillVersion, BillingLedgerSource.BACKFILL);

        ledger.upsertSubscription(reversedWorkspace, reconciledBackfill, backfillVersion, BillingLedgerSource.BACKFILL);
        ledger.upsertSubscription(reversedWorkspace, webhookState, webhookVersion, BillingLedgerSource.WEBHOOK);

        assertThat(subscriptionSnapshot(forwardWorkspace, "sub_reconciled").orElseThrow()).containsEntry("status", "canceled");
        assertThat(subscriptionSnapshot(reversedWorkspace, "sub_reconciled").orElseThrow()).containsEntry("status", "canceled");
    }

    private static StripeBillingObjects.ParsedSubscription subscriptionFor(String stripeSubscriptionId, String customerId, String status) {
        return new StripeBillingObjects.ParsedSubscription(
                stripeSubscriptionId,
                customerId,
                status,
                "usd",
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                java.util.List.of(),
                java.util.List.of());
    }

    private int countRows(String table, UUID workspaceId) {
        return jdbc().sql("SELECT COUNT(*) FROM " + table + " WHERE workspace_id = :w")
                .param("w", workspaceId)
                .query(Integer.class)
                .single();
    }
}
