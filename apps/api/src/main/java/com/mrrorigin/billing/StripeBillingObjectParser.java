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

    static ParsedSubscription parseSubscription(JsonNode subscription) {
        List<ParsedSubscriptionItem> items = new ArrayList<>();
        JsonNode itemsList = subscription.get("items");
        JsonNode itemData = itemsList == null ? null : itemsList.get("data");
        if (itemData != null && itemData.isArray()) {
            for (JsonNode item : itemData) {
                items.add(parseSubscriptionItem(item));
            }
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
                parseNestedDiscount(subscription.get("discount")));
    }

    private static ParsedSubscriptionItem parseSubscriptionItem(JsonNode item) {
        JsonNode price = item.get("price");
        String priceId =
                price != null && price.isTextual() ? price.textValue() : requiredText(price, "id");
        return new ParsedSubscriptionItem(
                requiredText(item, "id"), priceId, (int) optionalLong(item, "quantity", 1));
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
        return parseDiscount(discount, deleted);
    }

    private static Optional<ParsedDiscount> parseNestedDiscount(JsonNode discount) {
        if (discount == null || discount.isNull()) {
            return Optional.empty();
        }
        return Optional.of(parseDiscount(discount, false));
    }

    private static ParsedDiscount parseDiscount(JsonNode discount, boolean deleted) {
        JsonNode coupon = discount.get("coupon");
        if (coupon == null || coupon.isNull()) {
            throw new StripeBillingNormalizationException("Stripe discount is missing required field: coupon");
        }
        String customerId = optionalText(discount, "customer");
        String subscriptionId = optionalText(discount, "subscription");
        if (customerId == null && subscriptionId == null) {
            throw new StripeBillingNormalizationException(
                    "Stripe discount has neither a customer nor a subscription owner");
        }
        Long amountOff = optionalLong(coupon, "amount_off");
        BigDecimal percentOff = optionalDecimal(coupon, "percent_off");
        return new ParsedDiscount(
                requiredText(discount, "id"),
                customerId,
                subscriptionId,
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
