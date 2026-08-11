package com.mrrorigin.billing;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import tools.jackson.databind.JsonNode;

import com.mrrorigin.billing.StripeBillingObjects.ParsedCustomer;
import com.mrrorigin.billing.StripeBillingObjects.ParsedDiscount;
import com.mrrorigin.billing.StripeBillingObjects.ParsedInvoice;
import com.mrrorigin.billing.StripeBillingObjects.ParsedPayment;
import com.mrrorigin.billing.StripeBillingObjects.ParsedPrice;
import com.mrrorigin.billing.StripeBillingObjects.ParsedRefund;
import com.mrrorigin.billing.StripeBillingObjects.ParsedSubscription;
import com.mrrorigin.billing.StripeBillingObjects.ParsedSubscriptionItem;

/**
 * Parses raw Stripe JSON (a backfill list item, or a webhook event's {@code data.object}) into the
 * provider-neutral shapes {@link BillingLedgerUpsertService} consumes. Both sources hand this
 * class the same Stripe object representation, so there is exactly one parsing/validation path per
 * object type regardless of which pipeline produced the JSON.
 */
final class StripeBillingObjectParser {

    private StripeBillingObjectParser() {}

    static ParsedCustomer parseCustomer(JsonNode customer) {
        boolean deleted = optionalBoolean(customer, "deleted", false);
        return new ParsedCustomer(
                requiredText(customer, "id"),
                optionalText(customer, "currency"),
                deleted,
                requiredEpoch(customer, "created"),
                parseNestedDiscount(customer.get("discount")));
    }

    static String subscriptionId(JsonNode subscription) {
        return requiredText(subscription, "id");
    }

    static ParsedPrice parsePrice(JsonNode price) {
        JsonNode recurring = price.get("recurring");
        boolean isRecurring = recurring != null && !recurring.isNull();
        return new ParsedPrice(
                requiredText(price, "id"),
                requiredText(price, "product"),
                requiredText(price, "currency"),
                optionalLong(price, "unit_amount"),
                requiredText(price, "billing_scheme"),
                requiredText(price, "type"),
                isRecurring ? requiredText(recurring, "interval") : null,
                isRecurring ? (int) requiredLong(recurring, "interval_count") : null,
                requiredBoolean(price, "active"));
    }

    /**
     * Convenience form for callers that already know the subscription's embedded {@code items}
     * page is complete (i.e. {@link #embeddedItemsHaveMore} is false, or the caller has already
     * decided not to complete it). Real call sites should prefer the two-argument overload after
     * resolving any remaining pages via {@code StripeSubscriptionItemsResolver}.
     */
    static ParsedSubscription parseSubscription(JsonNode subscription) {
        return parseSubscription(subscription, List.of());
    }

    /**
     * @param supplementalItemNodes raw item objects fetched separately (via the dedicated {@code
     *     GET /v1/subscription_items} endpoint) to complete a paginated embedded {@code items} page.
     *     Must be the FULL remainder, never a partial page -- see {@code
     *     StripeSubscriptionItemsResolver} -- since this method has no way to tell a complete list
     *     from a truncated one once merged.
     */
    static ParsedSubscription parseSubscription(JsonNode subscription, List<JsonNode> supplementalItemNodes) {
        List<ParsedSubscriptionItem> items = new ArrayList<>();
        for (JsonNode item : embeddedItemNodes(subscription)) {
            items.add(parseSubscriptionItem(item));
        }
        for (JsonNode item : supplementalItemNodes) {
            items.add(parseSubscriptionItem(item));
        }
        return new ParsedSubscription(
                requiredText(subscription, "id"),
                requiredText(subscription, "customer"),
                requiredText(subscription, "status"),
                requiredText(subscription, "currency"),
                optionalEpoch(subscription, "current_period_start"),
                optionalEpoch(subscription, "current_period_end"),
                optionalBoolean(subscription, "cancel_at_period_end", false),
                optionalEpoch(subscription, "cancel_at"),
                optionalEpoch(subscription, "canceled_at"),
                optionalEpoch(subscription, "ended_at"),
                optionalEpoch(subscription, "trial_start"),
                optionalEpoch(subscription, "trial_end"),
                optionalText(subscription, "collection_method"),
                List.copyOf(items),
                parseDiscountsArray(subscription.get("discounts"), null));
    }

    /** The subscription's embedded {@code items.data} page, as delivered (possibly partial). */
    static List<JsonNode> embeddedItemNodes(JsonNode subscription) {
        JsonNode itemsList = subscription == null ? null : subscription.get("items");
        JsonNode itemData = itemsList == null ? null : itemsList.get("data");
        List<JsonNode> result = new ArrayList<>();
        if (itemData != null && itemData.isArray()) {
            for (JsonNode item : itemData) {
                result.add(item);
            }
        }
        return result;
    }

    /** Whether the subscription's embedded {@code items} page is a partial page (more items exist). */
    static boolean embeddedItemsHaveMore(JsonNode subscription) {
        JsonNode itemsList = subscription == null ? null : subscription.get("items");
        JsonNode hasMore = itemsList == null ? null : itemsList.get("has_more");
        return hasMore != null && hasMore.isBoolean() && hasMore.booleanValue();
    }

    /** The last embedded item's ID, used as {@code starting_after} to fetch the remaining pages. */
    static String lastEmbeddedItemId(JsonNode subscription) {
        List<JsonNode> items = embeddedItemNodes(subscription);
        if (items.isEmpty()) {
            return null;
        }
        JsonNode id = items.get(items.size() - 1).get("id");
        return id == null ? null : id.textValue();
    }

    static ParsedSubscriptionItem parseSubscriptionItem(JsonNode item) {
        JsonNode price = item.get("price");
        String priceId =
                price != null && price.isTextual() ? price.textValue() : requiredText(price, "id");
        String itemId = requiredText(item, "id");
        return new ParsedSubscriptionItem(
                itemId, priceId, (int) optionalLong(item, "quantity", 1), parseDiscountsArray(item.get("discounts"), itemId));
    }

    static ParsedInvoice parseInvoice(JsonNode invoice) {
        return new ParsedInvoice(
                requiredText(invoice, "id"),
                requiredText(invoice, "customer"),
                optionalText(invoice, "subscription"),
                requiredText(invoice, "status"),
                requiredText(invoice, "currency"),
                requiredLong(invoice, "amount_due"),
                requiredLong(invoice, "amount_paid"),
                requiredLong(invoice, "amount_remaining"),
                optionalEpoch(invoice, "period_start"),
                optionalEpoch(invoice, "period_end"),
                requiredEpoch(invoice, "created"));
    }

    static ParsedPayment parseCharge(JsonNode charge) {
        return new ParsedPayment(
                requiredText(charge, "id"),
                optionalText(charge, "customer"),
                optionalText(charge, "invoice"),
                requiredLong(charge, "amount"),
                requiredText(charge, "currency"),
                requiredText(charge, "status"),
                requiredBoolean(charge, "paid"),
                optionalBoolean(charge, "refunded", false),
                optionalLong(charge, "amount_refunded", 0),
                requiredEpoch(charge, "created"));
    }

    static ParsedRefund parseRefund(JsonNode refund) {
        String chargeId = optionalText(refund, "charge");
        if (chargeId == null) {
            throw new StripeBillingNormalizationException("Stripe refund is missing required field: charge");
        }
        return new ParsedRefund(
                requiredText(refund, "id"),
                chargeId,
                requiredLong(refund, "amount"),
                requiredText(refund, "currency"),
                requiredText(refund, "status"),
                optionalText(refund, "reason"),
                requiredEpoch(refund, "created"));
    }

    /** Top-level discount object, as delivered by {@code customer.discount.*} webhook events. */
    static ParsedDiscount parseTopLevelDiscount(JsonNode discount, boolean deleted) {
        return parseDiscount(discount, deleted, null);
    }

    /** Customer's singular {@code discount} field -- unlike subscriptions, a customer has at most one. */
    private static Optional<ParsedDiscount> parseNestedDiscount(JsonNode discount) {
        if (discount == null || discount.isNull()) {
            return Optional.empty();
        }
        return Optional.of(parseDiscount(discount, false, null));
    }

    /**
     * A {@code discounts} array field (subscriptions, and subscription items). Per
     * https://docs.stripe.com/api/subscriptions/object this is expandable: without expansion, an
     * entry is just a coupon/discount ID string, which cannot be safely normalized without its
     * full data. Such entries are skipped, not guessed -- callers that control the request should
     * request expansion; a discount that never arrives expanded simply stays unnormalized rather
     * than corrupting the rest of the subscription/event.
     *
     * @param ownerSubscriptionItemId non-null when parsing an item's own {@code discounts} field,
     *     attributing every entry to that subscription item regardless of what (if anything) the
     *     entry itself says about its owner.
     */
    private static List<ParsedDiscount> parseDiscountsArray(JsonNode discounts, String ownerSubscriptionItemId) {
        if (discounts == null || discounts.isNull()) {
            return List.of();
        }
        if (!discounts.isArray()) {
            throw new StripeBillingNormalizationException("Stripe object field has the wrong type: discounts");
        }
        List<ParsedDiscount> result = new ArrayList<>();
        for (JsonNode discount : discounts) {
            if (discount == null || discount.isNull() || !discount.isObject()) {
                continue;
            }
            result.add(parseDiscount(discount, false, ownerSubscriptionItemId));
        }
        return List.copyOf(result);
    }

    private static ParsedDiscount parseDiscount(JsonNode discount, boolean deleted, String ownerSubscriptionItemId) {
        JsonNode coupon = discount.get("coupon");
        if (coupon == null || coupon.isNull()) {
            throw new StripeBillingNormalizationException("Stripe discount is missing required field: coupon");
        }
        String customerId = optionalText(discount, "customer");
        String subscriptionId = optionalText(discount, "subscription");
        String subscriptionItemId = ownerSubscriptionItemId != null ? ownerSubscriptionItemId : optionalText(discount, "subscription_item");
        if (customerId == null && subscriptionId == null && subscriptionItemId == null) {
            throw new StripeBillingNormalizationException(
                    "Stripe discount has no customer, subscription, or subscription-item owner");
        }
        Long amountOff = optionalLong(coupon, "amount_off");
        BigDecimal percentOff = optionalDecimal(coupon, "percent_off");
        return new ParsedDiscount(
                requiredText(discount, "id"),
                customerId,
                subscriptionId,
                subscriptionItemId,
                requiredText(coupon, "id"),
                percentOff,
                amountOff,
                optionalText(coupon, "currency"),
                requiredEpoch(discount, "start"),
                optionalEpoch(discount, "end"),
                deleted);
    }

    // ---- field helpers ------------------------------------------------------------------------

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new StripeBillingNormalizationException("Stripe object is missing required field: " + field);
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new StripeBillingNormalizationException("Stripe object field has the wrong type: " + field);
        }
        return value.textValue();
    }

    private static boolean requiredBoolean(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isBoolean()) {
            throw new StripeBillingNormalizationException("Stripe object is missing required field: " + field);
        }
        return value.booleanValue();
    }

    private static boolean optionalBoolean(JsonNode node, String field, boolean fallback) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isBoolean()) {
            throw new StripeBillingNormalizationException("Stripe object field has the wrong type: " + field);
        }
        return value.booleanValue();
    }

    private static long requiredLong(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new StripeBillingNormalizationException("Stripe object is missing required field: " + field);
        }
        return value.longValue();
    }

    private static Long optionalLong(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new StripeBillingNormalizationException("Stripe object field has the wrong type: " + field);
        }
        return value.longValue();
    }

    private static long optionalLong(JsonNode node, String field, long fallback) {
        Long value = optionalLong(node, field);
        return value == null ? fallback : value;
    }

    private static BigDecimal optionalDecimal(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isNumber()) {
            throw new StripeBillingNormalizationException("Stripe object field has the wrong type: " + field);
        }
        return value.decimalValue();
    }

    private static OffsetDateTime requiredEpoch(JsonNode node, String field) {
        OffsetDateTime value = optionalEpoch(node, field);
        if (value == null) {
            throw new StripeBillingNormalizationException("Stripe object is missing required field: " + field);
        }
        return value;
    }

    private static OffsetDateTime optionalEpoch(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new StripeBillingNormalizationException("Stripe object field has the wrong type: " + field);
        }
        try {
            return Instant.ofEpochSecond(value.longValue()).atOffset(ZoneOffset.UTC);
        } catch (DateTimeException | ArithmeticException outOfRange) {
            throw new StripeBillingNormalizationException("Stripe object field out of range: " + field);
        }
    }
}
