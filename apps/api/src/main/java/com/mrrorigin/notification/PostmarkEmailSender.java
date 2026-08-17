package com.mrrorigin.notification;

import java.io.IOException;
import java.io.InputStream;

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
 * codebase -- no vendor SDK dependency. Permanent-vs-transient classification is primarily by HTTP
 * status (review fix, correcting an earlier version that trusted a small, incomplete allowlist of
 * Postmark {@code ErrorCode}s and left every other non-2xx status -- including ordinary permanent
 * request/configuration errors like 400/402/403/409 -- classified transient, wasting the full retry
 * budget on something that could never succeed): {@code 429} and {@code 5xx} are transient (rate
 * limit, provider-side trouble); every other non-2xx status is permanent (bad request, auth,
 * configuration, or an address Postmark has rejected) -- see ADR-0007's "Timeout and error behavior".
 */
@Component
class PostmarkEmailSender implements EmailSender {

    private static final String API_URL = "https://api.postmarkapp.com/email";

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
        // Carries the delivery row's own id through to Postmark (Metadata + a tracing header) so a
        // rare provider-side duplicate after an ambiguous network outcome is traceable on both sides --
        // see ADR-0007 / the delivery plan's "Delivery guarantee" section. Never claims this makes
        // duplicates impossible, only traceable.
        body.putObject("Metadata").put("deliveryId", message.deliveryId());

        try {
            return restClient
                    .post()
                    .uri(API_URL)
                    .headers(headers -> {
                        headers.set("Accept", "application/json");
                        headers.set("X-Postmark-Server-Token", properties.postmarkServerToken());
                        headers.set("X-MRR-Origin-Delivery-Id", message.deliveryId());
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
                                isPermanent(status));
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

    /**
     * {@code 429} (rate limit) and {@code 5xx} (provider-side trouble) are transient -- everything
     * else non-2xx is treated as permanent, since it means the request itself, our configuration, or
     * the recipient address was rejected in a way a bare retry cannot fix (review fix -- see class doc).
     */
    private static boolean isPermanent(HttpStatusCode status) {
        int code = status.value();
        if (code == 429 || code >= 500) {
            return false;
        }
        // This method is called only for non-2xx responses. Redirects are not a usable send
        // outcome for this fixed provider endpoint and a bare retry cannot correct one.
        return true;
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
