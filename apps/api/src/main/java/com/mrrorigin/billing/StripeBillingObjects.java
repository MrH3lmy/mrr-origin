package com.mrrorigin.billing;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Provider-neutral, parsed representations of the Stripe object types #12 normalizes. Both the
 * backfill client (list responses) and the webhook normalizer ({@code data.object}) produce these
 * same shapes via {@link StripeBillingObjectParser}, so {@link BillingLedgerUpsertService} has
 * exactly one code path per object type regardless of which source produced it.
 */
final class StripeBillingObjects {

    private StripeBillingObjects() {}

    record ParsedCustomer(
            String stripeCustomerId,
            String currency,
            boolean deleted,
            OffsetDateTime providerCreatedAt,
            Optional<ParsedDiscount> discount) {}

    /**
     * @param usageType Stripe's {@code recurring.usage_type} ({@code licensed} or {@code metered}),
     *     {@code null} for a non-recurring price. A metered price can still carry a non-null {@code
     *     unitAmount} (the per-unit rate), so this is required -- separately from {@code unitAmount}
     *     being present -- to identify usage-derived recurring revenue per ADR-0004.
     */
    record ParsedPrice(
            String stripePriceId,
            String stripeProductId,
            String currency,
            Long unitAmount,
            String billingScheme,
            String type,
            String recurringInterval,
            Integer recurringIntervalCount,
            String usageType,
            boolean active) {

        /**
         * V25 adds {@code billing_prices.usage_type} to an existing table, so rows written before
         * that migration legitimately read back with {@code NULL} even when they are recurring.
         * There is no safe way to infer licensed vs metered from {@code unit_amount} or
         * {@code billing_scheme}: Stripe metered/per-unit prices can carry a non-null unit amount.
         *
         * <p>Fail closed for those legacy/unknown recurring rows by exposing them to the MRR wiring
         * as metered until Stripe refreshes the price and persists the real usage type. This can
         * temporarily make an old licensed price unsupported, but it can never fabricate recurring
         * revenue from a metered price. Non-recurring prices retain {@code null}.
         */
        @Override
        public String usageType() {
            return usageType == null && "recurring".equals(type) ? "metered" : usageType;
        }
    }

    /**
     * Per https://docs.stripe.com/api/subscriptions/object, a subscription carries a plural,
     * expandable {@code discounts} array (compound discounts), not the singular {@code discount}
     * field Customer objects use -- {@code discounts} here can hold zero or more entries.
     */
    record ParsedSubscription(
            String stripeSubscriptionId,
            String stripeCustomerId,
            String status,
            String currency,
            OffsetDateTime currentPeriodStart,
            OffsetDateTime currentPeriodEnd,
            boolean cancelAtPeriodEnd,
            OffsetDateTime cancelAt,
            OffsetDateTime canceledAt,
            OffsetDateTime endedAt,
            OffsetDateTime trialStart,
            OffsetDateTime trialEnd,
            String collectionMethod,
            List<ParsedSubscriptionItem> items,
            List<ParsedDiscount> discounts) {}

    /** {@code discounts} mirrors the subscription-level field: an item can itself carry compound discounts. */
    record ParsedSubscriptionItem(
            String stripeSubscriptionItemId, String stripePriceId, int quantity, List<ParsedDiscount> discounts) {}

    record ParsedInvoice(
            String stripeInvoiceId,
            String stripeCustomerId,
            String stripeSubscriptionId,
            String status,
            String currency,
            long amountDue,
            long amountPaid,
            long amountRemaining,
            OffsetDateTime periodStart,
            OffsetDateTime periodEnd,
            OffsetDateTime providerCreatedAt) {}

    record ParsedPayment(
            String stripeChargeId,
            String stripeCustomerId,
            String stripeInvoiceId,
            long amount,
            String currency,
            String status,
            boolean paid,
            boolean refunded,
            long amountRefunded,
            OffsetDateTime providerCreatedAt) {}

    record ParsedRefund(
            String stripeRefundId,
            String stripeChargeId,
            long amount,
            String currency,
            String status,
            String reason,
            OffsetDateTime providerCreatedAt) {}

    record ParsedDiscount(
            String stripeDiscountId,
            String stripeCustomerId,
            String stripeSubscriptionId,
            String stripeSubscriptionItemId,
            String stripeCouponId,
            BigDecimal percentOff,
            Long amountOff,
            String currency,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            boolean deleted) {}
}
