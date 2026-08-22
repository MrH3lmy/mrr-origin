package com.mrrorigin.revenue;

import java.time.OffsetDateTime;
import java.util.ArrayList;
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
     * Prefix for the synthetic {@code source_discount_reference} attached to a correction's marker
     * discount -- distinct from any real Stripe discount id (which never contains a colon), so it can
     * never collide with (or be mistaken for) an actual discount evidenced by a payload.
     */
    private static final String UNPROVEN_DISCOUNT_MARKER_PREFIX = "HISTORICAL_UNPROVEN:";

    /** Prefix for the deterministic {@code source_billing_reference} a correction state is recorded under. */
    private static final String CORRECTION_REFERENCE_PREFIX = "HISTORICAL_CORRECTION:";

    @Override
    public void invalidateUnprovenHistoricalDiscountWindow(
            UUID workspaceId, String stripeCustomerId, String stripeDiscountId, OffsetDateTime windowStart, OffsetDateTime windowEnd) {
        List<AffectedState> affected = findUnprovenAffectedStates(workspaceId, stripeCustomerId, stripeDiscountId, windowStart, windowEnd);
        if (affected.isEmpty()) {
            return;
        }
        List<SubscriptionState> corrections = new ArrayList<>();
        for (AffectedState state : affected) {
            // Deterministic per (discount, subscription, instant) -- not per internal row id, which
            // changes across delete-then-reinsert cycles -- so replaying the same discount event that
            // discovered this evidence a second time recomputes the identical reference and no-ops via
            // recordAndReplay's own ON CONFLICT(source_billing_reference) DO NOTHING, exactly like any
            // other duplicate event in this system.
            String correctionReference =
                    CORRECTION_REFERENCE_PREFIX + stripeDiscountId + ":" + state.subscriptionId() + ":" + state.effectiveAt().toInstant();
            // Frees the (subscription, effective_at) slot -- revenue_subscription_states allows at most
            // one row there -- exactly like recalculateSubscription's own clearSupersededState, and for
            // the identical reason: a different event now needs to become authoritative for that instant.
            clearSupersededState(workspaceId, state.subscriptionId(), state.effectiveAt(), correctionReference);
            List<Discount> discounts = new ArrayList<>(state.discounts());
            // Never a guessed percentage/amount: percent_off and amount_off both null makes
            // RevenueCalculationService's own existing normalize() guard (percent == amount, i.e. both
            // absent) throw UNSUPPORTED_DISCOUNT deterministically -- reusing tested validation instead
            // of adding a new "is this state provably unsafe" rule.
            discounts.add(new Discount(UNPROVEN_DISCOUNT_MARKER_PREFIX + stripeDiscountId, null, null, null, null, null, null, true));
            // Items and status are never re-derived from current billing_subscription_items/status --
            // only ever the ones this exact historical state itself already persisted, so no live
            // (possibly since-changed) data leaks into a correction of the past.
            corrections.add(new SubscriptionState(
                    workspaceId, stripeCustomerId, state.subscriptionId(), state.effectiveAt(), state.status(),
                    correctionReference, state.items(), discounts));
        }
        revenueCalculationService.recordAndReplay(corrections);
    }

    /**
     * {@code active}/{@code past_due} states (ADR-0004's only MRR-retaining statuses -- any other
     * status contributes zero MRR regardless of discount state, so a missing discount there was never
     * wrong) for this customer whose {@code effective_at} falls in the newly-widened window and whose
     * own persisted discounts do not already include {@code stripeDiscountId} -- i.e., were computed
     * without knowledge of it, exactly the ones a live recalculation right now would have resolved
     * differently.
     */
    private List<AffectedState> findUnprovenAffectedStates(
            UUID workspaceId, String stripeCustomerId, String stripeDiscountId, OffsetDateTime windowStart, OffsetDateTime windowEnd) {
        List<AffectedState> states = jdbc.sql(
                        """
                        SELECT id, stripe_subscription_id, effective_at, status
                        FROM revenue_subscription_states s
                        WHERE workspace_id = :workspaceId AND stripe_customer_id = :stripeCustomerId
                          AND effective_at >= :windowStart AND effective_at < :windowEnd
                          AND status IN ('active', 'past_due')
                          AND NOT EXISTS (
                              SELECT 1 FROM revenue_subscription_state_discounts d
                              WHERE d.workspace_id = s.workspace_id AND d.state_id = s.id
                                AND d.source_discount_reference = :stripeDiscountId
                          )
                        ORDER BY stripe_subscription_id, effective_at
                        """)
                .param("workspaceId", workspaceId)
                .param("stripeCustomerId", stripeCustomerId)
                .param("stripeDiscountId", stripeDiscountId)
                .param("windowStart", windowStart)
                .param("windowEnd", windowEnd)
                .query((rs, rowNum) -> new AffectedState(
                        (UUID) rs.getObject("id"),
                        rs.getString("stripe_subscription_id"),
                        rs.getObject("effective_at", OffsetDateTime.class),
                        rs.getString("status"),
                        new ArrayList<>(),
                        new ArrayList<>()))
                .list();
        for (AffectedState state : states) {
            state.items.addAll(jdbc.sql(
                            """
                            SELECT source_item_reference, currency, unit_amount_minor, quantity, recurring_interval,
                                   interval_count, usage_pricing
                            FROM revenue_subscription_state_items WHERE workspace_id = :w AND state_id = :s
                            ORDER BY source_item_reference
                            """)
                    .param("w", workspaceId)
                    .param("s", state.id())
                    .query((rs, rowNum) -> new Item(
                            rs.getString("source_item_reference"),
                            rs.getString("currency"),
                            (Long) rs.getObject("unit_amount_minor"),
                            rs.getBigDecimal("quantity"),
                            rs.getString("recurring_interval"),
                            (Integer) rs.getObject("interval_count"),
                            rs.getBoolean("usage_pricing")))
                    .list());
            state.discounts.addAll(jdbc.sql(
                            """
                            SELECT source_discount_reference, source_item_reference, percent_off, amount_off_minor,
                                   currency, start_at, end_at, customer_level
                            FROM revenue_subscription_state_discounts WHERE workspace_id = :w AND state_id = :s
                            ORDER BY source_discount_reference
                            """)
                    .param("w", workspaceId)
                    .param("s", state.id())
                    .query((rs, rowNum) -> new Discount(
                            rs.getString("source_discount_reference"),
                            rs.getString("source_item_reference"),
                            rs.getBigDecimal("percent_off"),
                            (Long) rs.getObject("amount_off_minor"),
                            rs.getString("currency"),
                            rs.getObject("start_at", OffsetDateTime.class),
                            rs.getObject("end_at", OffsetDateTime.class),
                            rs.getBoolean("customer_level")))
                    .list());
        }
        return states;
    }

    /** One already-materialized state found to need retroactive correction. See {@link #invalidateUnprovenHistoricalDiscountWindow}. */
    private record AffectedState(
            UUID id, String subscriptionId, OffsetDateTime effectiveAt, String status, List<Item> items, List<Discount> discounts) {}

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
                        discount.endAt(),
                        discount.customerLevel()))
                .toList();
    }
}
