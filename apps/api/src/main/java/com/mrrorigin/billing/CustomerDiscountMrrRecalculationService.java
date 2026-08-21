package com.mrrorigin.billing;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.mrrorigin.billing.BillingLedgerUpsertService.AffectedSubscription;
import com.mrrorigin.billing.BillingMrrRecalculationPort.MrrDiscount;
import com.mrrorigin.billing.BillingMrrRecalculationPort.MrrItem;
import com.mrrorigin.billing.BillingMrrRecalculationPort.SubscriptionMrrSnapshot;
import com.mrrorigin.billing.BillingSourceVersion.SourceVersion;
import com.mrrorigin.billing.StripeBillingObjects.ParsedDiscount;

/**
 * Closes #86: a legacy top-level {@code customer.discount.created/updated/deleted} event (the
 * customer's singular {@code discount} field -- distinct from a subscription's compound {@code
 * discounts} array, which arrives embedded in {@code customer.subscription.*} and is already
 * recalculated by {@link BillingLedgerUpsertService#upsertSubscription}) is normalized into {@code
 * billing_discounts} but, per ADR-0010's documented known limitation, never triggered MRR
 * recalculation. This closes that silent-staleness gap without changing any approved MRR semantics:
 * {@code revenue.RevenueCalculationService} is never touched; every recalculation call below reuses
 * the exact same {@link BillingMrrRecalculationPort} the subscription path already uses.
 *
 * <p><b>Percentage discounts</b> fan out to every one of the customer's {@code active}/{@code
 * past_due} subscriptions (ADR-0004's only MRR-retaining statuses): a percentage composes
 * independently per subscription, so applying the same percentage to each is not a new allocation
 * rule -- it is the identical per-subscription math ADR-0004 already approves, run once per affected
 * subscription instead of once.
 *
 * <p><b>Fixed-amount discounts</b> are supported only when exactly one subscription is affected --
 * the existing engine's own single-item/currency-match guards then decide supported vs. unsupported
 * for that one subscription unchanged. When more than one subscription is affected, this throws
 * rather than guessing a split or duplicating the full amount per subscription: ADR-0004 already
 * refuses an allocation rule for a fixed discount spanning multiple recurring items for exactly this
 * reason, and a customer with multiple subscriptions is the same unresolved ambiguity one level up.
 * The throw rolls back the whole transaction (including the {@code billing_discounts} write) via
 * {@link StripeWebhookNormalizationService}'s existing FAILED/retry path -- an explicit, inspectable,
 * replayable rejection rather than a silently accepted update with stale MRR.
 *
 * <p><b>Delete</b> simply omits the discount from the reconstructed per-subscription state from its
 * effective end onward; ADR-0004's existing customer-movement classification produces the correct
 * expansion/reactivation with no new movement logic.
 *
 * <p>Each affected subscription's current items come from {@code billing_subscription_items}/{@code
 * billing_prices} (the ledger's own authoritative "current state", kept correct by full replacement
 * on every accepted subscription upsert) and its current subscription/item-level discounts come from
 * {@code billing_discounts} -- so a discount already present on that subscription is included
 * alongside the new customer-level one, letting {@code RevenueCalculationService}'s own pre-existing
 * "at most one active discount per subscription state" guard fail stacking combinations visibly,
 * exactly as it already does for two subscription-embedded discounts.
 *
 */
@Service
class CustomerDiscountMrrRecalculationService {

    private final BillingLedgerUpsertService ledger;
    private final BillingMrrRecalculationPort mrrRecalculation;

    CustomerDiscountMrrRecalculationService(BillingLedgerUpsertService ledger, BillingMrrRecalculationPort mrrRecalculation) {
        this.ledger = ledger;
        this.mrrRecalculation = mrrRecalculation;
    }

    void applyCustomerDiscount(UUID workspaceId, ParsedDiscount discount, SourceVersion sourceVersion, BillingLedgerSource source) {
        boolean applied = ledger.upsertDiscountReportingApplied(workspaceId, discount, sourceVersion, source);
        if (!applied) {
            // Stale/out-of-order relative to what's already stored: the ledger itself already
            // rejected this write, so nothing here is any newer than the currently recalculated MRR.
            return;
        }
        if (discount.stripeSubscriptionId() != null || discount.stripeSubscriptionItemId() != null) {
            // Not genuinely customer-scoped. ADR-0010 documents that this event type only ever
            // carries customer-scoped discounts in this codebase's parser; subscription/item-scoped
            // discount changes arrive embedded in customer.subscription.* and are already
            // recalculated there. Defensive no-op rather than an assumption-breaking recalculation.
            return;
        }

        List<AffectedSubscription> affected = ledger.activeSubscriptionsForCustomer(workspaceId, discount.stripeCustomerId());
        if (affected.isEmpty()) {
            // No currently MRR-retaining subscription exists for this customer yet; nothing is stale.
            return;
        }

        boolean isFixedAmount = discount.amountOff() != null && discount.percentOff() == null;
        if (!discount.deleted() && isFixedAmount && affected.size() > 1) {
            throw new StripeBillingNormalizationException(
                    "Customer-level fixed-amount discount " + discount.stripeDiscountId()
                            + " cannot be deterministically allocated across " + affected.size()
                            + " active/past_due subscriptions for customer " + discount.stripeCustomerId()
                            + " -- ADR-0004 has no approved allocation rule for a fixed amount spanning"
                            + " multiple subscriptions.");
        }

        OffsetDateTime effectiveAt = effectiveAt(discount, sourceVersion);
        for (AffectedSubscription subscription : affected) {
            List<MrrItem> items = ledger.currentMrrItems(workspaceId, subscription.id());
            List<String> stripeItemIds = items.stream().map(MrrItem::sourceReference).toList();
            List<MrrDiscount> discounts =
                    new ArrayList<>(ledger.currentDiscounts(workspaceId, subscription.stripeSubscriptionId(), stripeItemIds));
            if (!discount.deleted()) {
                discounts.add(toMrrDiscount(discount));
            }

            String sourceBillingReference = source.name() + ":" + sourceVersion.version() + ":" + sourceVersion.sequence()
                    + ":" + subscription.stripeSubscriptionId();

            mrrRecalculation.recalculateSubscription(new SubscriptionMrrSnapshot(
                    workspaceId,
                    discount.stripeCustomerId(),
                    subscription.stripeSubscriptionId(),
                    effectiveAt,
                    subscription.status(),
                    sourceBillingReference,
                    items,
                    discounts));
        }
    }

    /**
     * Create/update: the discount's own provider-declared {@code start} -- the ADR-0004 effective
     * date for a discount entering effect, always present (required by the parser). Delete: {@code
     * end} when Stripe provided one (the discount's own declared expiry), else the event's own
     * provider-declared second -- exactly ADR-0010's documented last-resort fallback for a transition
     * with no more specific field, extended to this event type rather than inventing a new rule.
     */
    private static OffsetDateTime effectiveAt(ParsedDiscount discount, SourceVersion sourceVersion) {
        if (discount.deleted()) {
            return discount.endAt() != null ? discount.endAt() : Instant.ofEpochSecond(sourceVersion.version()).atOffset(ZoneOffset.UTC);
        }
        return discount.startAt();
    }

    private static MrrDiscount toMrrDiscount(ParsedDiscount discount) {
        return new MrrDiscount(
                discount.stripeDiscountId(),
                discount.stripeSubscriptionItemId(),
                discount.percentOff(),
                discount.amountOff(),
                discount.currency() == null ? null : discount.currency().toUpperCase(Locale.ROOT),
                discount.startAt(),
                discount.endAt(),
                true);
    }
}
