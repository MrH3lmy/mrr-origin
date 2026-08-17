package com.mrrorigin.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;

import com.mrrorigin.notification.EmailSender.EmailMessage;
import com.mrrorigin.notification.EmailSender.EmailSendException;
import com.mrrorigin.notification.EmailSender.EmailSendResult;

/**
 * ADR-0007's test strategy for the real provider client: {@code MockRestServiceServer}, no live
 * network call, exercising the exact request shape, the delivery-id metadata/tracing header (the
 * delivery plan's "Delivery guarantee"), and the permanent-vs-transient and ambiguous-vs-definite
 * classifications described there.
 */
class PostmarkEmailSenderTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EmailProperties properties = new EmailProperties(
            "token-abc", "from@example.com", "reply@example.com", "outbound", Duration.ofSeconds(5), "https://app.example.test");
    private final EmailMessage message = new EmailMessage(
            "to@example.com", "from@example.com", "reply@example.com", "Weekly summary", "text body", "<p>html body</p>",
            "11111111-1111-1111-1111-111111111111");

    @Test
    void sendsAndReturnsProviderMessageIdOnSuccess() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        server.expect(requestTo("https://api.postmarkapp.com/email"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Postmark-Server-Token", "token-abc"))
                .andExpect(header("X-MRR-Origin-Delivery-Id", "11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.Metadata.deliveryId").value("11111111-1111-1111-1111-111111111111"))
                .andRespond(withSuccess(
                        "{\"To\":\"to@example.com\",\"SubmittedAt\":\"2026-08-17T00:00:00Z\","
                                + "\"MessageID\":\"msg-123\",\"ErrorCode\":0,\"Message\":\"OK\"}",
                        MediaType.APPLICATION_JSON));

        PostmarkEmailSender sender = new PostmarkEmailSender(restClient, properties, objectMapper);
        EmailSendResult result = sender.send(message);

        assertThat(result.providerMessageId()).isEqualTo("msg-123");
        server.verify();
    }

    @Test
    void classifiesInactiveRecipientAsPermanentAndNotAmbiguous() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        server.expect(requestTo("https://api.postmarkapp.com/email"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"ErrorCode\":406,\"Message\":\"Recipient is inactive\"}"));

        PostmarkEmailSender sender = new PostmarkEmailSender(restClient, properties, objectMapper);

        assertThatThrownBy(() -> sender.send(message))
                .isInstanceOf(EmailSendException.class)
                .satisfies(exception -> {
                    assertThat(((EmailSendException) exception).permanent()).isTrue();
                    // A definite HTTP error response is not ambiguous -- Postmark gave a clear answer.
                    assertThat(((EmailSendException) exception).ambiguous()).isFalse();
                });
    }

    @Test
    void classifiesServerErrorAsTransientAndNotAmbiguous() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        server.expect(requestTo("https://api.postmarkapp.com/email")).andRespond(withServerError());

        PostmarkEmailSender sender = new PostmarkEmailSender(restClient, properties, objectMapper);

        assertThatThrownBy(() -> sender.send(message))
                .isInstanceOf(EmailSendException.class)
                .satisfies(exception -> {
                    assertThat(((EmailSendException) exception).permanent()).isFalse();
                    assertThat(((EmailSendException) exception).ambiguous()).isFalse();
                });
    }

    @Test
    void classifiesNetworkFailureAsTransientAndAmbiguous() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        server.expect(requestTo("https://api.postmarkapp.com/email"))
                .andRespond(request -> {
                    throw new SocketTimeoutException("simulated read timeout");
                });

        PostmarkEmailSender sender = new PostmarkEmailSender(restClient, properties, objectMapper);

        // A network-level failure means we cannot tell whether Postmark ever received the request --
        // this is exactly the case the delivery plan's "Delivery guarantee" section requires be
        // recorded as ambiguous, never silently folded into an ordinary transient failure.
        assertThatThrownBy(() -> sender.send(message))
                .isInstanceOf(EmailSendException.class)
                .satisfies(exception -> {
                    assertThat(((EmailSendException) exception).permanent()).isFalse();
                    assertThat(((EmailSendException) exception).ambiguous()).isTrue();
                });
    }
}
