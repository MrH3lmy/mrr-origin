package com.mrrorigin.billing;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpServer;

/**
 * Minimal embedded stub of the Stripe list endpoints {@link StripeBackfillClient} pages through
 * (customers, prices, subscriptions, invoices, charges, refunds), so backfill integration tests
 * exercise real HTTP, real cursor pagination, and the real {@code Stripe-Account}/Basic-auth
 * headers instead of a hand-rolled fake client. Mirrors {@code StripeApiStub}'s approach for the
 * three OAuth endpoints -- no new test dependency, {@code com.sun.net.httpserver} ships with the
 * JDK.
 *
 * <p>Each object type is backed by a full, caller-supplied, ordered list of raw Stripe JSON
 * objects; the stub performs genuine {@code limit}/{@code starting_after} slicing itself, exactly
 * like Stripe's real list endpoints, so tests only need to describe the data, not the pagination.
 */
final class StripeBillingListApiStub implements AutoCloseable {

    private final HttpServer server;
    private final Map<String, List<String>> itemsByPath = new ConcurrentHashMap<>();
    final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();

    private static final Pattern STARTING_AFTER = Pattern.compile("(?:^|&)starting_after=([^&]*)");
    private static final Pattern LIMIT = Pattern.compile("(?:^|&)limit=([^&]*)");
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

    StripeBillingListApiStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the Stripe billing list API stub", e);
        }
        for (String path : List.of(
                "/v1/customers",
                "/v1/prices",
                "/v1/subscriptions",
                "/v1/subscription_items",
                "/v1/invoices",
                "/v1/charges",
                "/v1/refunds")) {
            server.createContext(path, this::handle);
        }
        server.setExecutor(null);
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
    }

    String apiBaseUri() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Sets the full ordered set of raw Stripe JSON objects this path's list endpoint serves. */
    void seed(String path, List<String> rawJsonObjects) {
        itemsByPath.put(path, List.copyOf(rawJsonObjects));
    }

    private void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        URI uri = exchange.getRequestURI();
        String query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String stripeAccount = exchange.getRequestHeaders().getFirst("Stripe-Account");
        requests.add(new RecordedRequest(uri.getPath(), query, authorization, stripeAccount));

        List<String> all = itemsByPath.getOrDefault(uri.getPath(), List.of());
        int limit = parseInt(LIMIT, query, 10);
        String startingAfter = parseString(STARTING_AFTER, query);

        int startIndex = 0;
        if (startingAfter != null && !startingAfter.isBlank()) {
            for (int i = 0; i < all.size(); i++) {
                if (idOf(all.get(i)).equals(startingAfter)) {
                    startIndex = i + 1;
                    break;
                }
            }
        }
        int endIndex = Math.min(all.size(), startIndex + limit);
        List<String> page = startIndex >= all.size() ? List.of() : all.subList(startIndex, endIndex);
        boolean hasMore = endIndex < all.size();

        String body = "{\"object\":\"list\",\"has_more\":" + hasMore + ",\"data\":[" + String.join(",", page) + "]}";
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private static String idOf(String rawJsonObject) {
        Matcher matcher = ID.matcher(rawJsonObject);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static int parseInt(Pattern pattern, String query, int fallback) {
        String value = parseString(pattern, query);
        return value == null ? fallback : Integer.parseInt(value);
    }

    private static String parseString(Pattern pattern, String query) {
        Matcher matcher = pattern.matcher(query);
        return matcher.find() ? URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8) : null;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    record RecordedRequest(String path, String query, String authorizationHeader, String stripeAccountHeader) {}

    /** Convenience accumulator for building a seeded list one raw JSON object at a time. */
    static final class Items {
        private final Map<String, List<String>> byPath = new LinkedHashMap<>();

        Items add(String path, String rawJsonObject) {
            byPath.computeIfAbsent(path, key -> new ArrayList<>()).add(rawJsonObject);
            return this;
        }

        void applyTo(StripeBillingListApiStub stub) {
            byPath.forEach(stub::seed);
        }
    }
}
