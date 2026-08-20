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

    /**
     * Bound on the same-second disambiguation loop below -- generously beyond any realistic number
     * of distinct provider-sequenced changes to one subscription inside a single second.
     */
    private static final int MAX_DISAMBIGUATION_ATTEMPTS = 1000;

    private final RevenueCalculationService revenueCalculationService;
    private final JdbcClient jdbc;

    BillingMrrRecalculationAdapter(RevenueCalculationService revenueCalculationService, JdbcClient jdbc) {
        this.revenueCalculationService = revenueCalculationService;
        this.jdbc = jdbc;
    }

    @Override
    public void recalculateSubscription(SubscriptionMrrSnapshot snapshot) {
        OffsetDateTime effectiveAt = disambiguate(snapshot.workspaceId(), snapshot.stripeSubscriptionId(), snapshot.effectiveAt());
        revenueCalculationService.recordAndReplay(new SubscriptionState(
                snapshot.workspaceId(),
                snapshot.stripeCustomerId(),
                snapshot.stripeSubscriptionId(),
                effectiveAt,
                snapshot.status(),
                snapshot.sourceBillingReference(),
                toItems(snapshot.items()),
                toDiscounts(snapshot.discounts())));
    }

    /**
     * {@code revenue_subscription_states} holds at most one row per {@code (subscription,
     * effective_at)} -- correct, since a subscription cannot truly have two different states at the
     * literal same instant. Stripe's own provider timestamps only carry whole-second precision
     * (see {@code BillingSourceVersion}), so two genuinely distinct, sequence-ordered changes to the
     * same subscription can legitimately resolve to the same whole second (proven by
     * BillingLedgerIdempotencyIntegrationTests' same-second convergence coverage). When that
     * happens, this nudges the later one forward by the smallest possible increment so both remain
     * individually recorded in their correct relative order, rather than colliding on the unique
     * constraint. This does not fabricate a false real-world timestamp -- it only breaks a tie
     * between two changes Stripe itself only distinguishes by sequence, not by time.
     */
    private OffsetDateTime disambiguate(UUID workspaceId, String stripeSubscriptionId, OffsetDateTime effectiveAt) {
        OffsetDateTime candidate = effectiveAt;
        for (int attempt = 0; attempt < MAX_DISAMBIGUATION_ATTEMPTS; attempt++) {
            boolean free = jdbc.sql(
                            """
                            SELECT 1 FROM revenue_subscription_states
                            WHERE workspace_id = :workspaceId AND stripe_subscription_id = :subscriptionId
                              AND effective_at = :effectiveAt
                            """)
                    .param("workspaceId", workspaceId)
                    .param("subscriptionId", stripeSubscriptionId)
                    .param("effectiveAt", candidate)
                    .query(Integer.class)
                    .optional()
                    .isEmpty();
            if (free) {
                return candidate;
            }
            candidate = candidate.plusNanos(1_000);
        }
        throw new IllegalStateException(
                "Could not find a free effective_at slot for subscription " + stripeSubscriptionId + " near " + effectiveAt);
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
