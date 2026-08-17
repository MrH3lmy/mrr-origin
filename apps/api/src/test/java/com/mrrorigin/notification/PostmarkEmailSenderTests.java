package com.mrrorigin.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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
 * network call, exercising the exact request shape and the permanent-vs-transient error
 * classification described there.
 */
class PostmarkEmailSenderTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EmailProperties properties = new EmailProperties("token-abc", "from@example.com", "reply@example.com", "outbound", Duration.ofSeconds(5));
    private final EmailMessage message = new EmailMessage(
            "to@example.com", "from@example.com", "reply@example.com", "Weekly summary", "text body", "<p>html body</p>");

    @Test
    void sendsAndReturnsProviderMessageIdOnSuccess() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        server.expect(requestTo("https://api.postmarkapp.com/email"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Postmark-Server-Token", "token-abc"))
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
    void classifiesInactiveRecipientAsPermanent() {
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
                .satisfies(exception -> assertThat(((EmailSendException) exception).permanent()).isTrue());
    }

    @Test
    void classifiesServerErrorAsTransient() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        server.expect(requestTo("https://api.postmarkapp.com/email")).andRespond(withServerError());

        PostmarkEmailSender sender = new PostmarkEmailSender(restClient, properties, objectMapper);

        assertThatThrownBy(() -> sender.send(message))
                .isInstanceOf(EmailSendException.class)
                .satisfies(exception -> assertThat(((EmailSendException) exception).permanent()).isFalse());
    }
}
