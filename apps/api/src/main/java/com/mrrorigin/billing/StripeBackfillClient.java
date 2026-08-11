package com.mrrorigin.billing;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
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
        return listPage(mode, stripeAccountId, "/v1/customers", startingAfter, Map.of());
    }

    StripePage listPrices(StripeConnectionMode mode, String stripeAccountId, String startingAfter) {
        return listPage(mode, stripeAccountId, "/v1/prices", startingAfter, Map.of());
    }

    StripePage listSubscriptions(StripeConnectionMode mode, String stripeAccountId, String startingAfter) {
        return listPage(mode, stripeAccountId, "/v1/subscriptions", startingAfter, Map.of("status", "all"));
    }

    StripePage listInvoices(StripeConnectionMode mode, String stripeAccountId, String startingAfter) {
        return listPage(mode, stripeAccountId, "/v1/invoices", startingAfter, Map.of());
    }

    StripePage listCharges(StripeConnectionMode mode, String stripeAccountId, String startingAfter) {
        return listPage(mode, stripeAccountId, "/v1/charges", startingAfter, Map.of());
    }

    StripePage listRefunds(StripeConnectionMode mode, String stripeAccountId, String startingAfter) {
        return listPage(mode, stripeAccountId, "/v1/refunds", startingAfter, Map.of());
    }

    private StripePage listPage(
            StripeConnectionMode mode,
            String stripeAccountId,
            String path,
            String startingAfter,
            Map<String, String> extraParams) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(properties.apiBaseUri() + path)
                .queryParam("limit", PAGE_SIZE);
        if (startingAfter != null && !startingAfter.isBlank()) {
            uri.queryParam("starting_after", startingAfter);
        }
        extraParams.forEach(uri::queryParam);

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
