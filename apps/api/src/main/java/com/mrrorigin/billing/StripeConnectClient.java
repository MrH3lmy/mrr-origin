package com.mrrorigin.billing;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Calls Stripe's Connect OAuth endpoints and the Account-retrieve endpoint, per
 * https://docs.stripe.com/connect/oauth-reference and https://docs.stripe.com/connect/authentication.
 * Every call authenticates with the centrally configured platform secret key (HTTP Basic, per
 * Stripe's documented convention) -- never a per-workspace credential.
 */
@Component
class StripeConnectClient {

    private final RestClient restClient;
    private final StripeConnectProperties properties;

    StripeConnectClient(StripeConnectProperties properties) {
        this.restClient = RestClient.create();
        this.properties = properties;
    }

    /**
     * POSTs to Stripe's {@code /oauth/token} endpoint. Per Stripe's reference, the {@code access_token}
     * and {@code refresh_token} fields on the response are deprecated for authenticating requests and
     * are intentionally not read here; only the account id, granted scope, and live-mode flag are used.
     */
    TokenExchangeResult exchangeAuthorizationCode(StripeConnectionMode mode, String authorizationCode) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", authorizationCode);

        return restClient
                .post()
                .uri(properties.tokenUri())
                .headers(headers -> headers.setBasicAuth(properties.secretKey(mode), ""))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .exchange((request, response) -> {
                    if (response.getStatusCode().is2xxSuccessful()) {
                        TokenResponse body = readBody(response, TokenResponse.class);
                        if (body == null
                                || body.stripeUserId() == null
                                || body.scope() == null
                                || body.livemode() == null) {
                            throw new StripeConnectException("Stripe token response was incomplete");
                        }
                        return new TokenExchangeResult(body.stripeUserId(), body.scope(), body.livemode());
                    }
                    // Stripe's error_description for this endpoint can echo the authorization code back
                    // verbatim (e.g. "invalid_grant: authorization code does not exist: ac_..."), so it is
                    // never read, logged, or included in any exception message here.
                    throw new StripeConnectException("Stripe rejected the authorization code");
                });
    }

    /** Best-effort: an already-disconnected account on Stripe's side still leaves us with no access. */
    void deauthorize(StripeConnectionMode mode, String stripeAccountId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.clientId(mode));
        form.add("stripe_user_id", stripeAccountId);

        try {
            restClient
                    .post()
                    .uri(properties.deauthorizeUri())
                    .headers(headers -> headers.setBasicAuth(properties.secretKey(mode), ""))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .exchange((request, response) -> null);
        } catch (RuntimeException ignored) {
            // Best-effort: our local disconnect proceeds regardless of Stripe's response.
        }
    }

    /** Confirms the platform key can still act on this account, via {@code GET /v1/accounts/{id}}. */
    StripeVerificationOutcome verifyAccountAccess(StripeConnectionMode mode, String stripeAccountId) {
        try {
            return restClient
                    .get()
                    .uri(properties.apiBaseUri() + "/v1/accounts/{id}", stripeAccountId)
                    .headers(headers -> headers.setBasicAuth(properties.secretKey(mode), ""))
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.is2xxSuccessful()) {
                            return StripeVerificationOutcome.VERIFIED;
                        }
                        if (status.value() == 401 || status.value() == 403) {
                            return StripeVerificationOutcome.UNAUTHORIZED;
                        }
                        return StripeVerificationOutcome.TRANSIENT_FAILURE;
                    });
        } catch (RuntimeException networkFailure) {
            return StripeVerificationOutcome.TRANSIENT_FAILURE;
        }
    }

    private static <T> T readBody(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response, Class<T> type) {
        try {
            return response.bodyTo(type);
        } catch (RuntimeException malformed) {
            throw new StripeConnectException("Stripe response could not be parsed");
        }
    }

    record TokenExchangeResult(String stripeAccountId, String scope, boolean livemode) {}

    private record TokenResponse(
            @JsonProperty("stripe_user_id") String stripeUserId, String scope, Boolean livemode) {}
}
