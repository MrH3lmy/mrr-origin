package com.mrrorigin.notification;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import com.mrrorigin.notification.EmailSender.EmailMessage;
import com.mrrorigin.notification.EmailSender.EmailSendException;
import com.mrrorigin.notification.EmailSender.EmailSendResult;

/**
 * Postmark {@code POST /email} adapter (#59, ADR-0007). Plain {@link RestClient} call, the same
 * pattern {@code StripeBackfillClient} already establishes for external provider integration in this
 * codebase -- no vendor SDK dependency. {@code PostmarkErrorCode}s that mean the address itself is
 * permanently invalid/inactive short-circuit to {@link EmailSendException#permanent()}; every other
 * failure (network error, timeout, 5xx, rate limit) is transient, per ADR-0007's "Timeout and error
 * behavior" section.
 */
@Component
class PostmarkEmailSender implements EmailSender {

    private static final String API_URL = "https://api.postmarkapp.com/email";

    /** Postmark ErrorCodes meaning the recipient address itself will never accept mail; see ADR-0007. */
    private static final Set<Integer> PERMANENT_ERROR_CODES = Set.of(300, 406, 401);

    private final RestClient restClient;
    private final EmailProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * Takes an already-built {@link RestClient} (see {@link EmailClientConfiguration} for where the
     * connect/read timeout is applied) rather than building one itself, so tests can build their own
     * {@code RestClient} from a builder bound to {@code MockRestServiceServer} and construct this class
     * directly against it -- the same reason ADR-0007's test strategy names {@code
     * MockRestServiceServer} explicitly. Timeout configuration deliberately lives in the {@code @Bean}
     * method, not here: {@code MockRestServiceServer.bindTo(builder)} must be the last customization
     * applied to a builder, so any further {@code requestFactory(...)} call in this constructor would
     * silently discard the mock in tests.
     */
    PostmarkEmailSender(@Qualifier("postmarkRestClient") RestClient restClient, EmailProperties properties, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public EmailSendResult send(EmailMessage message) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("From", message.fromAddress());
        body.put("To", message.toAddress());
        if (message.replyToAddress() != null && !message.replyToAddress().isBlank()) {
            body.put("ReplyTo", message.replyToAddress());
        }
        body.put("Subject", message.subject());
        body.put("TextBody", message.textBody());
        body.put("HtmlBody", message.htmlBody());
        body.put("MessageStream", properties.messageStream());

        try {
            return restClient
                    .post()
                    .uri(API_URL)
                    .headers(headers -> {
                        headers.set("Accept", "application/json");
                        headers.set("X-Postmark-Server-Token", properties.postmarkServerToken());
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsBytes(body))
                    .exchange((request, response) -> {
                        JsonNode parsed = parseResponse(readBody(response));
                        HttpStatusCode status = response.getStatusCode();
                        if (status.is2xxSuccessful()) {
                            return new EmailSendResult(textField(parsed, "MessageID"));
                        }
                        int errorCode = intField(parsed, "ErrorCode");
                        String errorMessage = textField(parsed, "Message");
                        throw new EmailSendException(
                                "Postmark rejected the send (status=" + status.value() + ", errorCode=" + errorCode + "): "
                                        + errorMessage,
                                PERMANENT_ERROR_CODES.contains(errorCode));
                    });
        } catch (ResourceAccessException networkFailure) {
            throw new EmailSendException("Postmark could not be reached: " + networkFailure.getMessage(), false, networkFailure);
        }
    }

    private static byte[] readBody(ConvertibleClientHttpResponse response) {
        try (InputStream in = response.getBody()) {
            return in.readAllBytes();
        } catch (IOException readFailure) {
            throw new EmailSendException("Postmark response body could not be read", false, readFailure);
        }
    }

    private JsonNode parseResponse(byte[] body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isObject()) {
                throw new EmailSendException("Postmark response was not a JSON object", false);
            }
            return root;
        } catch (EmailSendException alreadyMapped) {
            throw alreadyMapped;
        } catch (RuntimeException malformed) {
            throw new EmailSendException("Postmark response could not be parsed", false, malformed);
        }
    }

    private static String textField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isTextual() ? null : value.textValue();
    }

    private static int intField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? -1 : value.intValue();
    }
}
