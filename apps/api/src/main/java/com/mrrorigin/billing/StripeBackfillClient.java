package com.mrrorigin.billing;

import java.util.List;

import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Bounded, cursor-paginated reads of the Stripe list endpoints #12 backfills. Per ADR-0003, every
 * call authenticates with the centrally configured platform secret key plus the {@code
 * Stripe-Account} header for the connected account being synchronized -- never a per-workspace
 * credential. Each call fetches exactly one page (Stripe's {@code limit}/{@code starting_after}
 * cursor convention); the caller ({@link StripeBackfillService}) decides whether and when to fetch
 * the next page, so a single Stripe outage or process restart never loses more than the
 * in-flight page.
 */
@Component
class StripeBackfillClient {

    static final int PAGE_SIZE = 100;

    private final RestClient restClient;
    private final StripeConnectProperties properties;
    private final ObjectMapper objectMapper;

    StripeBackfillClient(StripeConnectProperties properties, ObjectMapper objectMapper) {
        this.restClient = RestClient.create();
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    StripePage listCustomers(StripeConnectionMode mode, String stripeAccountId, String startingAfter) {
        return listPage(mode, stripeAccountId, "/v1/customers", startingAfter, new LinkedMultiValueMap<>());
    }

    StripePage listPrices(StripeConnectionMode mode, String stripeAccountId, String startingAfter) {
        return listPage(mode, stripeAccountId, "/v1/prices", startingAfter, new LinkedMultiValueMap<>());
    }

    StripePage listSubscriptions(StripeConnectionMode mode, String stripeAccountId, String startingAfter) {
        // expand[] requests full Discount objects, at both the subscription level and each embedded
        // item's own level, rather than bare coupon/discount ID strings, per
        // https://docs.stripe.com/api/subscriptions/object -- without this, discounts would arrive
        // unexpanded and StripeBillingObjectParser would have nothing to normalize them from.
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("status", "all");
        params.add("expand[]", "data.discounts");
        params.add("expand[]", "data.items.data.discounts");
        return listPage(mode, stripeAccountId, "/v1/subscriptions", startingAfter, params);
    }

    /**
     * Fetches one subscription's complete, fully expanded current representation directly, for
     * callers (webhook normalization) that received a payload whose {@code discounts} arrived
     * unexpanded and cannot request expansion on the original (push-delivered) event itself.
     */
    JsonNode getSubscription(StripeConnectionMode mode, String stripeAccountId, String stripeSubscriptionId) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(properties.apiBaseUri() + "/v1/subscriptions/" + stripeSubscriptionId)
                .queryParam("expand[]", "discounts")
                .queryParam("expand[]", "items.data.discounts");
        try {
            return restClient
                    .get()
                    .uri(uri.build().toUri())
                    .headers(headers -> {
                        headers.setBasicAuth(properties.secretKey(mode), "");
                        headers.set("Stripe-Account", stripeAccountId);
                    })
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (!status.is2xxSuccessful()) {
                            throw new StripeBackfillException(
                                    "Stripe subscription request failed with status " + status.value() + ": " + stripeSubscriptionId);
                        }
                        return parseObject(response.bodyTo(byte[].class));
                    });
        } catch (ResourceAccessException networkFailure) {
            throw new StripeBackfillException("Stripe could not be reached for subscription: " + stripeSubscriptionId);
        }
    }

    /**
     * Fetches a page of a single subscription's items directly, for completing a subscription
     * whose embedded {@code items} page was itself paginated ({@code has_more=true}). Per
     * https://docs.stripe.com/api/subscriptions/object, the embedded page is not guaranteed
     * complete, and replacing a subscription's full stored item set from a partial page would
     * silently drop items. Also requests discount expansion, for the same reason as {@link
     * #listSubscriptions}: without it, any item-level discounts on the supplemental page would
     * arrive as bare IDs.
     */
    StripePage listSubscriptionItems(StripeConnectionMode mode, String stripeAccountId, String stripeSubscriptionId, String startingAfter) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("subscription", stripeSubscriptionId);
        params.add("expand[]", "data.discounts");
        return listPage(mode, stripeAccountId, "/v1/subscription_items", startingAfter, params);
    }

    StripePage listInvoices(StripeConnectionMode mode, String stripeAccountId, String startingAfter) {
        return listPage(mode, stripeAccountId, "/v1/invoices", startingAfter, new LinkedMultiValueMap<>());
    }

    StripePage listCharges(StripeConnectionMode mode, String stripeAccountId, String startingAfter) {
        return listPage(mode, stripeAccountId, "/v1/charges", startingAfter, new LinkedMultiValueMap<>());
    }

    StripePage listRefunds(StripeConnectionMode mode, String stripeAccountId, String startingAfter) {
        return listPage(mode, stripeAccountId, "/v1/refunds", startingAfter, new LinkedMultiValueMap<>());
    }

    private StripePage listPage(
            StripeConnectionMode mode,
            String stripeAccountId,
            String path,
            String startingAfter,
            MultiValueMap<String, String> extraParams) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(properties.apiBaseUri() + path)
                .queryParam("limit", PAGE_SIZE);
        if (startingAfter != null && !startingAfter.isBlank()) {
            uri.queryParam("starting_after", startingAfter);
        }
        extraParams.forEach((key, values) -> values.forEach(value -> uri.queryParam(key, value)));

        try {
            return restClient
                    .get()
                    .uri(uri.build().toUri())
                    .headers(headers -> {
                        headers.setBasicAuth(properties.secretKey(mode), "");
                        headers.set("Stripe-Account", stripeAccountId);
                    })
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (!status.is2xxSuccessful()) {
                            throw new StripeBackfillException(
                                    "Stripe list request failed with status " + status.value() + ": " + path);
                        }
                        return parsePage(response.bodyTo(byte[].class));
                    });
        } catch (ResourceAccessException networkFailure) {
            throw new StripeBackfillException("Stripe could not be reached for: " + path);
        }
    }

    private JsonNode parseObject(byte[] body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isObject()) {
                throw new StripeBackfillException("Stripe response was not a JSON object");
            }
            return root;
        } catch (StripeBackfillException alreadyMapped) {
            throw alreadyMapped;
        } catch (RuntimeException malformed) {
            throw new StripeBackfillException("Stripe response could not be parsed");
        }
    }

    private StripePage parsePage(byte[] body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (RuntimeException malformed) {
            throw new StripeBackfillException("Stripe list response could not be parsed");
        }
        JsonNode data = root == null ? null : root.get("data");
        JsonNode hasMore = root == null ? null : root.get("has_more");
        if (data == null || !data.isArray() || hasMore == null || !hasMore.isBoolean()) {
            throw new StripeBackfillException("Stripe list response was not a valid list object");
        }
        List<JsonNode> items = new java.util.ArrayList<>();
        for (JsonNode item : data) {
            items.add(item);
        }
        return new StripePage(List.copyOf(items), hasMore.booleanValue());
    }

    record StripePage(List<JsonNode> data, boolean hasMore) {

        String lastId() {
            if (data.isEmpty()) {
                return null;
            }
            JsonNode last = data.get(data.size() - 1);
            JsonNode id = last.get("id");
            return id == null ? null : id.textValue();
        }
    }
}
