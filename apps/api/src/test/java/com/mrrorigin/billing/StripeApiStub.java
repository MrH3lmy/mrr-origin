package com.mrrorigin.billing;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

/**
 * Minimal embedded stub of the three Stripe endpoints {@code StripeConnectClient} calls
 * ({@code /oauth/token}, {@code /oauth/deauthorize}, {@code /v1/accounts/{id}}), so integration
 * tests exercise the real HTTP client code against a real (local) server instead of a hand-rolled
 * fake. No new test dependency: {@code com.sun.net.httpserver} ships with the JDK.
 */
final class StripeApiStub implements AutoCloseable {

    private final HttpServer server;

    final List<RecordedRequest> tokenRequests = new CopyOnWriteArrayList<>();
    final List<RecordedRequest> deauthorizeRequests = new CopyOnWriteArrayList<>();
    final List<RecordedRequest> accountRequests = new CopyOnWriteArrayList<>();

    private final AtomicReference<StubResponse> tokenResponse = new AtomicReference<>(StubResponse.json(
            200,
            """
            {"stripe_user_id":"acct_default","scope":"read_only","livemode":false,"token_type":"bearer"}"""));
    private final AtomicReference<StubResponse> deauthorizeResponse =
            new AtomicReference<>(StubResponse.json(200, "{\"stripe_user_id\":\"acct_default\"}"));
    private final AtomicReference<StubResponse> accountResponse =
            new AtomicReference<>(StubResponse.json(200, "{\"id\":\"acct_default\"}"));
    private final AtomicBoolean deauthorizeNetworkFailure = new AtomicBoolean(false);

    StripeApiStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the Stripe API stub", e);
        }
        server.createContext("/oauth/token", exchange -> handle(exchange, tokenRequests, tokenResponse));
        server.createContext("/oauth/deauthorize", exchange -> {
            if (deauthorizeNetworkFailure.compareAndSet(true, false)) {
                exchange.getRequestBody().readAllBytes();
                exchange.close();
                return;
            }
            handle(exchange, deauthorizeRequests, deauthorizeResponse);
        });
        server.createContext("/v1/accounts/", exchange -> handle(exchange, accountRequests, accountResponse));
        server.setExecutor(null);
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    String tokenUri() {
        return baseUrl() + "/oauth/token";
    }

    String deauthorizeUri() {
        return baseUrl() + "/oauth/deauthorize";
    }

    String apiBaseUri() {
        return baseUrl();
    }

    void respondToToken(int status, String jsonBody) {
        tokenResponse.set(StubResponse.json(status, jsonBody));
    }

    void respondToDeauthorize(int status, String jsonBody) {
        deauthorizeResponse.set(StubResponse.json(status, jsonBody));
    }

    void respondToAccount(int status, String jsonBody) {
        accountResponse.set(StubResponse.json(status, jsonBody));
    }

    /** The next {@code /oauth/deauthorize} call gets its connection closed with no response (one-shot). */
    void failNextDeauthorizeWithNetworkError() {
        deauthorizeNetworkFailure.set(true);
    }

    private void handle(
            com.sun.net.httpserver.HttpExchange exchange,
            List<RecordedRequest> recorded,
            AtomicReference<StubResponse> responseHolder)
            throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        recorded.add(new RecordedRequest(exchange.getRequestURI(), authorization, body));

        StubResponse response = responseHolder.get();
        byte[] payload = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    record RecordedRequest(URI uri, String authorizationHeader, String body) {

        /** Decodes the HTTP Basic-auth username, which is where {@code StripeConnectClient} puts the secret key. */
        String secretKeyUsed() {
            if (authorizationHeader == null || !authorizationHeader.startsWith("Basic ")) {
                return null;
            }
            String decoded = new String(
                    Base64.getDecoder().decode(authorizationHeader.substring("Basic ".length())),
                    StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            return colon >= 0 ? decoded.substring(0, colon) : decoded;
        }
    }

    private record StubResponse(int status, String body) {
        static StubResponse json(int status, String body) {
            return new StubResponse(status, body);
        }
    }
}
