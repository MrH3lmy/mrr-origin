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

    record ParsedPrice(
            String stripePriceId,
            String stripeProductId,
            String currency,
            Long unitAmount,
            String billingScheme,
            String type,
            String recurringInterval,
            Integer recurringIntervalCount,
            boolean active) {}

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
            Optional<ParsedDiscount> discount) {}

    record ParsedSubscriptionItem(String stripeSubscriptionItemId, String stripePriceId, int quantity) {}

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
            String stripeCouponId,
            BigDecimal percentOff,
            Long amountOff,
            String currency,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            boolean deleted) {}
}
