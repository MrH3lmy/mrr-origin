package com.mrrorigin.revenue;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.mrrorigin.billing.BillingMrrRecalculationPort;
import com.mrrorigin.revenue.RevenueModels.Discount;
import com.mrrorigin.revenue.RevenueModels.Item;
import com.mrrorigin.revenue.RevenueModels.SubscriptionState;

/**
 * Implements the {@code billing}-owned {@link BillingMrrRecalculationPort}: translates its DTOs
 * into {@link RevenueModels.SubscriptionState} and drives the tested deterministic engine. This is
 * the only class in the codebase that depends on both a {@code billing} type and a {@code revenue}
 * type -- exactly the allowed direction (ARCHITECTURE.md: {@code revenue} may depend on {@code
 * billing}) -- so {@code billing} itself never imports anything from {@code revenue}. See ADR-0010.
 */
@Service
class BillingMrrRecalculationAdapter implements BillingMrrRecalculationPort {

    private final RevenueCalculationService revenueCalculationService;
    private final JdbcClient jdbc;

    BillingMrrRecalculationAdapter(RevenueCalculationService revenueCalculationService, JdbcClient jdbc) {
        this.revenueCalculationService = revenueCalculationService;
        this.jdbc = jdbc;
    }

    @Override
    public void recalculateSubscription(SubscriptionMrrSnapshot snapshot) {
        requireResolvedPrices(snapshot);
        clearSupersededState(
                snapshot.workspaceId(), snapshot.stripeSubscriptionId(), snapshot.effectiveAt(), snapshot.sourceBillingReference());
        revenueCalculationService.recordAndReplay(new SubscriptionState(
                snapshot.workspaceId(),
                snapshot.stripeCustomerId(),
                snapshot.stripeSubscriptionId(),
                snapshot.effectiveAt(),
                snapshot.status(),
                snapshot.sourceBillingReference(),
                toItems(snapshot.items()),
                toDiscounts(snapshot.discounts())));
    }

    /**
     * Subscription payloads carry price IDs while their economics come from {@code billing_prices}.
     * Webhooks are not guaranteed to arrive in dependency order, so a subscription event can be
     * claimed before the referenced {@code price.created/updated} event has populated that table.
     *
     * <p>Committing that subscription with null economics would leave a durable unsupported MRR
     * snapshot, and processing the later price event would not by itself replay the subscription.
     * Treat this dependency miss as a transient processing failure instead. The surrounding
     * normalization transaction rolls back, the webhook is recorded as failed/retriable by the
     * existing worker, and replay after the price arrives converges from the original immutable
     * event. Existing-but-unsupported prices are not caught here: they still carry identifying
     * economics such as currency/interval and flow into the revenue engine's visible unsupported
     * reason contract.
     */
    private static void requireResolvedPrices(SubscriptionMrrSnapshot snapshot) {
        for (MrrItem item : snapshot.items()) {
            boolean unresolved = item.currency() == null
                    && item.unitAmountMinor() == null
                    && item.interval() == null
                    && item.intervalCount() == null;
            if (unresolved) {
                throw new IllegalStateException(
                        "Referenced billing price has not been normalized yet for item " + item.sourceReference());
            }
        }
    }

    /**
     * {@code revenue_subscription_states} holds at most one row per {@code (subscription,
     * effective_at)} -- correct per ADR-0004: "equal effective timestamps are grouped before
     * classification... producing at most one net movement." Stripe's own provider timestamps only
     * carry whole-second precision (see {@code BillingSourceVersion}), so two genuinely distinct,
     * sequence-ordered changes to the same subscription can legitimately resolve to the same whole
     * second (proven real, not contrived, by {@code BillingLedgerIdempotencyIntegrationTests}' own
     * same-second convergence coverage).
     *
     * <p>This never fabricates a timestamp -- {@code effective_at} passed to {@code recordAndReplay}
     * is always exactly the caller's provider-declared value. Instead, when a different event already
     * occupies that exact {@code (subscription, effective_at)} slot, it is cleared first so the
     * incoming, more-recently-applied content becomes the sole record at that instant -- the {@code
     * DELETE}'s cascade removes its items/discounts too. A true replay of the *same* event
     * (matching {@code source_billing_reference}) is left alone; {@code recordAndReplay}'s own
     * {@code ON CONFLICT ... DO NOTHING} then correctly no-ops it, id and all.
     *
     * <p>This is deterministic regardless of delivery/processing order because {@code
     * BillingLedgerUpsertService.upsertSubscription}'s own {@code (source_version, source_sequence)}
     * version guard already only ever lets this method be called, for one subscription, in
     * non-decreasing ordering-pair order -- a write that would represent a regression relative to the
     * currently stored ledger state is rejected before reaching MRR recalculation at all. So whichever
     * call happens to run last for a given slot is always the ordering-pair winner, in either
     * processing order: proven by the same {@code BillingLedgerIdempotencyIntegrationTests} scenarios
     * this class's tests reuse.
     */
    private void clearSupersededState(
            UUID workspaceId, String stripeSubscriptionId, OffsetDateTime effectiveAt, String sourceBillingReference) {
        jdbc.sql(
                        """
                        DELETE FROM revenue_subscription_states
                        WHERE workspace_id = :workspaceId AND stripe_subscription_id = :subscriptionId
                          AND effective_at = :effectiveAt AND source_billing_reference <> :sourceBillingReference
                        """)
                .param("workspaceId", workspaceId)
                .param("subscriptionId", stripeSubscriptionId)
                .param("effectiveAt", effectiveAt)
                .param("sourceBillingReference", sourceBillingReference)
                .update();
    }

    private static List<Item> toItems(List<MrrItem> items) {
        return items.stream()
                .map(item -> new Item(
                        item.sourceReference(),
                        item.currency(),
                        item.unitAmountMinor(),
                        item.quantity(),
                        item.interval(),
                        item.intervalCount(),
                        item.usagePricing()))
                .toList();
    }

    private static List<Discount> toDiscounts(List<MrrDiscount> discounts) {
        return discounts.stream()
                .map(discount -> new Discount(
                        discount.sourceReference(),
                        discount.itemReference(),
                        discount.percentOff(),
                        discount.amountOffMinor(),
                        discount.currency(),
                        discount.startAt(),
                        discount.endAt()))
                .toList();
    }
}
