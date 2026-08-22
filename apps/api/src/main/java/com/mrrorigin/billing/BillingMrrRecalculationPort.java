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
     * Per ADR-0011's retroactive-invalidation amendment: newly discovered historical evidence (a
     * customer-level discount identity proven to have existed earlier than previously known, surfaced
     * by {@code BillingLedgerUpsertService.upsertDiscountReportingApplied}'s widened {@code
     * first_seen_start_at}) can prove an already-materialized MRR-retaining subscription state is no
     * longer safely known: it may or may not have included this discount, and the discount's own
     * actual terms at that now-earlier instant are not recoverable from normalized state.
     *
     * <p>Every {@code active}/{@code past_due} subscription state for this customer whose {@code
     * effective_at} falls in {@code [windowStart, windowEnd)} and does not already reflect {@code
     * stripeDiscountId} is superseded with an explicit, unprovable-terms marker -- never a guessed
     * percentage or amount, and never left standing as its previous (now unsafe) supported figure. A
     * no-op when no such state exists. Idempotent: safe to call repeatedly for the same window (e.g.
     * on replay of the discount event that discovered this evidence).
     */
    void invalidateUnprovenHistoricalDiscountWindow(
            UUID workspaceId, String stripeCustomerId, String stripeDiscountId, OffsetDateTime windowStart, OffsetDateTime windowEnd);

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
            OffsetDateTime endAt,
            boolean customerLevel) {}
}
