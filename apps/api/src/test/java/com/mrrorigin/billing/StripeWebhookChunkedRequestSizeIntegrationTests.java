package com.mrrorigin.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Exercises the webhook body-size cap over a real socket rather than MockMvc, so a genuinely
 * chunked, unknown-content-length request is exercised end to end -- per #11 blocker #2.
 * {@link StripeWebhookIngestionIntegrationTests} covers the fixed-length/incorrect-Content-Length
 * cases, which MockMvc can express directly.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StripeWebhookChunkedRequestSizeIntegrationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void chunkedOversizedDeliveryIsRejectedWithPayloadTooLarge() throws Exception {
        int oversizedLength = (1024 * 1024) + 4096;
        byte[] body = new byte[oversizedLength];
        Arrays.fill(body, (byte) 'a');

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/stripe/webhooks/test"))
                .header("Content-Type", "application/json")
                .header("Stripe-Signature", "t=0,v1=deadbeef")
                // A supplier-based InputStream body has no declared length up front, so
                // HttpClient sends it with chunked transfer encoding -- the same "server can't
                // know the size in advance" shape as a real chunked Stripe delivery.
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM stripe_webhook_events").query(Integer.class).single())
                .isZero();
    }
}
