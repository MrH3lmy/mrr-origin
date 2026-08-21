package com.mrrorigin.billing;

/**
 * Raw Stripe JSON object builders shared by the #12 normalization tests. Both the backfill list
 * stub ({@link StripeBillingListApiStub}) and directly-inserted {@code stripe_webhook_events} rows
 * consume the exact same object shapes these methods produce, since that is precisely what #12's
 * convergence guarantee is about: one JSON shape, normalized identically regardless of source.
 */
final class BillingFixtures {

    private BillingFixtures() {}

    static String customer(String id, String currency, long createdEpoch, boolean deleted, String discountJsonOrNull) {
        return """
                {"id":"%s","object":"customer","created":%d,"currency":%s,"deleted":%b%s}"""
                .formatted(id, createdEpoch, quoteOrNull(currency), deleted, trailingField("discount", discountJsonOrNull));
    }

    static String price(
            String id,
            String productId,
            String currency,
            Long unitAmount,
            String type,
            String recurringInterval,
            Integer recurringIntervalCount,
            boolean active) {
        String usageType = "recurring".equals(type) ? "licensed" : null;
        return price(id, productId, currency, unitAmount, type, recurringInterval, recurringIntervalCount, usageType, active);
    }

    /** @param usageTypeOrNull Stripe's {@code recurring.usage_type} ({@code licensed}/{@code metered}), or null to omit the field. */
    static String price(
            String id,
            String productId,
            String currency,
            Long unitAmount,
            String type,
            String recurringInterval,
            Integer recurringIntervalCount,
            String usageTypeOrNull,
            boolean active) {
        String recurring = recurringInterval == null
                ? "null"
                : """
                  {"interval":"%s","interval_count":%d%s}"""
                        .formatted(recurringInterval, recurringIntervalCount, trailingField("usage_type", quoteOrNull(usageTypeOrNull)));
        return """
                {"id":"%s","object":"price","product":"%s","currency":"%s","unit_amount":%s,"billing_scheme":"per_unit","type":"%s","recurring":%s,"active":%b}"""
                .formatted(id, productId, currency, unitAmount == null ? "null" : unitAmount, type, recurring, active);
    }

    static String subscriptionItem(String id, String priceId, int quantity) {
        return subscriptionItem(id, priceId, quantity, null);
    }

    /** @param discountsJoinedOrNull comma-joined item-level discount JSON objects (see {@link #discount}), or null for none */
    static String subscriptionItem(String id, String priceId, int quantity, String discountsJoinedOrNull) {
        return """
                {"id":"%s","object":"subscription_item","price":"%s","quantity":%d,"discounts":[%s]}"""
                .formatted(id, priceId, quantity, arrayContents(discountsJoinedOrNull));
    }

    static String subscription(
            String id,
            String customerId,
            String status,
            String currency,
            long currentPeriodStart,
            long currentPeriodEnd,
            boolean cancelAtPeriodEnd,
            Long trialStart,
            Long trialEnd,
            String itemsJoined,
            String discountsJoinedOrNull) {
        return subscription(
                id, customerId, status, currency, currentPeriodStart, currentPeriodEnd, cancelAtPeriodEnd, trialStart,
                trialEnd, itemsJoined, false, discountsJoinedOrNull);
    }

    /**
     * @param itemsJoined comma-joined embedded {@code items.data} entries (see {@link
     *     #subscriptionItem}); pass only a subset with {@code itemsHasMore=true} to simulate a
     *     paginated embedded items page that requires a supplemental {@code GET
     *     /v1/subscription_items} fetch to complete.
     * @param discountsJoinedOrNull comma-joined subscription-level discount JSON objects (see
     *     {@link #discount}), or null for none -- per
     *     https://docs.stripe.com/api/subscriptions/object this is a plural, possibly-multi-entry
     *     array, unlike Customer's singular {@code discount} field.
     */
    static String subscription(
            String id,
            String customerId,
            String status,
            String currency,
            long currentPeriodStart,
            long currentPeriodEnd,
            boolean cancelAtPeriodEnd,
            Long trialStart,
            Long trialEnd,
            String itemsJoined,
            boolean itemsHasMore,
            String discountsJoinedOrNull) {
        return """
                {"id":"%s","object":"subscription","customer":"%s","status":"%s","currency":"%s","current_period_start":%d,"current_period_end":%d,"cancel_at_period_end":%b,"cancel_at":null,"canceled_at":null,"ended_at":null,"trial_start":%s,"trial_end":%s,"collection_method":"charge_automatically","items":{"object":"list","data":[%s],"has_more":%b},"discounts":[%s]}"""
                .formatted(
                        id,
                        customerId,
                        status,
                        currency,
                        currentPeriodStart,
                        currentPeriodEnd,
                        cancelAtPeriodEnd,
                        epochOrNull(trialStart),
                        epochOrNull(trialEnd),
                        itemsJoined,
                        itemsHasMore,
                        arrayContents(discountsJoinedOrNull));
    }

    static String invoice(
            String id,
            String customerId,
            String subscriptionIdOrNull,
            String status,
            String currency,
            long amountDue,
            long amountPaid,
            long amountRemaining,
            long periodStart,
            long periodEnd,
            long created) {
        return """
                {"id":"%s","object":"invoice","customer":"%s","subscription":%s,"status":"%s","currency":"%s","amount_due":%d,"amount_paid":%d,"amount_remaining":%d,"period_start":%d,"period_end":%d,"created":%d}"""
                .formatted(
                        id,
                        customerId,
                        quoteOrNull(subscriptionIdOrNull),
                        status,
                        currency,
                        amountDue,
                        amountPaid,
                        amountRemaining,
                        periodStart,
                        periodEnd,
                        created);
    }

    static String charge(
            String id,
            String customerId,
            String invoiceIdOrNull,
            long amount,
            String currency,
            String status,
            boolean paid,
            boolean refunded,
            long amountRefunded,
            long created) {
        return """
                {"id":"%s","object":"charge","customer":%s,"invoice":%s,"amount":%d,"currency":"%s","status":"%s","paid":%b,"refunded":%b,"amount_refunded":%d,"created":%d}"""
                .formatted(
                        id,
                        quoteOrNull(customerId),
                        quoteOrNull(invoiceIdOrNull),
                        amount,
                        currency,
                        status,
                        paid,
                        refunded,
                        amountRefunded,
                        created);
    }

    static String refund(
            String id, String chargeId, long amount, String currency, String status, String reasonOrNull, long created) {
        return """
                {"id":"%s","object":"refund","charge":"%s","amount":%d,"currency":"%s","status":"%s","reason":%s,"created":%d}"""
                .formatted(id, chargeId, amount, currency, status, quoteOrNull(reasonOrNull), created);
    }

    static String discount(
            String id,
            String customerIdOrNull,
            String subscriptionIdOrNull,
            String couponId,
            Long percentOff,
            Long amountOff,
            String currencyOrNull,
            long start,
            Long endOrNull) {
        return """
                {"id":"%s","object":"discount","coupon":{"id":"%s","percent_off":%s,"amount_off":%s,"currency":%s},"customer":%s,"subscription":%s,"start":%d,"end":%s}"""
                .formatted(
                        id,
                        couponId,
                        percentOff == null ? "null" : percentOff,
                        amountOff == null ? "null" : amountOff,
                        quoteOrNull(currencyOrNull),
                        quoteOrNull(customerIdOrNull),
                        quoteOrNull(subscriptionIdOrNull),
                        start,
                        epochOrNull(endOrNull));
    }

    /** Wraps a Stripe object as the raw JSON payload of a webhook event envelope, per V5's shape. */
    static String webhookEnvelope(String eventId, String stripeAccountId, String type, long created, boolean livemode, String object) {
        return """
                {"id":"%s","object":"event","type":"%s","api_version":"2024-06-20","created":%d,"account":"%s","livemode":%b,"data":{"object":%s}}"""
                .formatted(eventId, type, created, stripeAccountId, livemode, object);
    }

    private static String quoteOrNull(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static String epochOrNull(Long value) {
        return value == null ? "null" : String.valueOf(value);
    }

    private static String trailingField(String name, String rawJsonValueOrNull) {
        return rawJsonValueOrNull == null ? "" : ",\"" + name + "\":" + rawJsonValueOrNull;
    }

    private static String arrayContents(String joinedOrNull) {
        return joinedOrNull == null ? "" : joinedOrNull;
    }
}
