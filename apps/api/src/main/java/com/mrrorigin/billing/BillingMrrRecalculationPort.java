package com.mrrorigin.billing;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Narrow seam {@code billing} calls, and {@code revenue} implements, to recalculate deterministic
 * MRR after a subscription normalization actually applies. {@code billing} may not depend on
 * {@code revenue} (see {@code ARCHITECTURE.md}'s module table), so this interface -- and the DTOs
 * below it -- are owned entirely by {@code billing}; the implementing adapter lives in {@code
 * revenue}, which is already allowed to depend on {@code billing}. No type from {@code
 * com.mrrorigin.revenue} is referenced here or anywhere else in this module.
 *
 * <p>Per ADR-0010, the caller invokes this once per accepted (non-stale) {@code
 * customer.subscription.*} normalization -- webhook or backfill -- inside the same database
 * transaction as the ledger write, so the normalized billing state and the resulting MRR
 * movements/snapshots commit or roll back together.
 */
public interface BillingMrrRecalculationPort {

    void recalculateSubscription(SubscriptionMrrSnapshot snapshot);

    /**
     * The complete state of one subscription at one provider-declared effective instant, mirroring
     * exactly what {@code RevenueCalculationService.recordAndReplay} requires -- {@code billing}'s
     * own shape so this module never imports {@code RevenueModels}.
     */
    record SubscriptionMrrSnapshot(
            UUID workspaceId,
            String stripeCustomerId,
            String stripeSubscriptionId,
            OffsetDateTime effectiveAt,
            String status,
            String sourceBillingReference,
            List<MrrItem> items,
            List<MrrDiscount> discounts) {
        public SubscriptionMrrSnapshot {
            items = items == null ? List.of() : List.copyOf(items);
            discounts = discounts == null ? List.of() : List.copyOf(discounts);
        }
    }

    record MrrItem(
            String sourceReference,
            String currency,
            Long unitAmountMinor,
            BigDecimal quantity,
            String interval,
            Integer intervalCount,
            boolean usagePricing) {}

    record MrrDiscount(
            String sourceReference,
            String itemReference,
            BigDecimal percentOff,
            Long amountOffMinor,
            String currency,
            OffsetDateTime startAt,
            OffsetDateTime endAt) {}
}
