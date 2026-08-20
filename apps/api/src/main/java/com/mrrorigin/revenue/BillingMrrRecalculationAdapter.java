package com.mrrorigin.revenue;

import java.util.List;

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

    BillingMrrRecalculationAdapter(RevenueCalculationService revenueCalculationService) {
        this.revenueCalculationService = revenueCalculationService;
    }

    @Override
    public void recalculateSubscription(SubscriptionMrrSnapshot snapshot) {
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
