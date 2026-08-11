package com.mrrorigin.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class StripeConnectionIntegrationTests {

    private static final String ALICE = "user-alice";
    private static final String BOB = "user-bob";
    private static final String TEST_SECRET_KEY = "sk_test_platform_secret";
    private static final String LIVE_SECRET_KEY = "sk_live_platform_secret";

    private static final StripeApiStub STRIPE_STUB = new StripeApiStub();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    @DynamicPropertySource
    static void stripeProperties(DynamicPropertyRegistry registry) {
        registry.add("mrrorigin.stripe.connect.test-client-id", () -> "ca_test_123");
        registry.add("mrrorigin.stripe.connect.test-secret-key", () -> TEST_SECRET_KEY);
        registry.add("mrrorigin.stripe.connect.live-client-id", () -> "ca_live_123");
        registry.add("mrrorigin.stripe.connect.live-secret-key", () -> LIVE_SECRET_KEY);
        registry.add("mrrorigin.stripe.connect.token-uri", STRIPE_STUB::tokenUri);
        registry.add("mrrorigin.stripe.connect.deauthorize-uri", STRIPE_STUB::deauthorizeUri);
        registry.add("mrrorigin.stripe.connect.api-base-uri", STRIPE_STUB::apiBaseUri);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataSource dataSource;

    private JdbcClient jdbc;

    @Autowired
    void setJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @BeforeEach
    void resetState() {
        new org.springframework.jdbc.core.JdbcTemplate(dataSource)
                .execute("TRUNCATE TABLE projects, workspace_members, workspaces, stripe_connections, "
                        + "stripe_oauth_states CASCADE");
        STRIPE_STUB.tokenRequests.clear();
        STRIPE_STUB.deauthorizeRequests.clear();
        STRIPE_STUB.accountRequests.clear();
        STRIPE_STUB.respondToToken(
                200,
                """
                {"stripe_user_id":"acct_default","scope":"read_only","livemode":false,"token_type":"bearer"}""");
        STRIPE_STUB.respondToAccount(200, "{\"id\":\"acct_default\"}");
        STRIPE_STUB.respondToDeauthorize(200, "{\"stripe_user_id\":\"acct_default\"}");
    }

    @Test
    void anonymousAndNonManagerRequestsAreRejected() throws Exception {
        UUID workspaceId = createWorkspace(ALICE);
        addMember(workspaceId, ALICE, BOB, "MEMBER");

        mockMvc.perform(post("/api/workspaces/{id}/stripe-connection/oauth/start", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"TEST\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/workspaces/{id}/stripe-connection/oauth/start", workspaceId)
                        .with(token(BOB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"TEST\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/workspaces/{id}/stripe-connection", workspaceId).with(token(BOB)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/workspaces/{id}/stripe-connection", workspaceId).with(token(BOB)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/workspaces/{id}/stripe-connection/oauth/start", workspaceId)
                        .with(token(ALICE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"TEST\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void crossWorkspaceConnectionAccessIsHiddenAndDenied() throws Exception {
        UUID aliceWorkspace = createWorkspace(ALICE);
        UUID bobWorkspace = createWorkspace(BOB);
        connectSuccessfully(aliceWorkspace, ALICE, StripeConnectionMode.TEST, "acct_alice", false);

        mockMvc.perform(get("/api/workspaces/{id}/stripe-connection", aliceWorkspace).with(token(BOB)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/workspaces/{id}/stripe-connection", aliceWorkspace).with(token(BOB)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/workspaces/{id}/stripe-connection/oauth/start", aliceWorkspace)
                        .with(token(BOB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"TEST\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/workspaces/{id}/stripe-connection", bobWorkspace).with(token(BOB)))
                .andExpect(status().isNotFound());
    }

    @Test
    void expiredStateIsRejectedWithoutCallingStripe() throws Exception {
        UUID workspaceId = createWorkspace(ALICE);
        String state = startOauth(workspaceId, ALICE, StripeConnectionMode.TEST);
        expireState(workspaceId);

        mockMvc.perform(callbackRequestBuilder(state, "ac_expired", "read_only", null)).andExpect(status().isBadRequest());
        assertThat(STRIPE_STUB.tokenRequests).isEmpty();
    }

    @Test
    void replayedStateIsRejectedOnSecondCallbackAndStripeIsCalledOnlyOnce() throws Exception {
        UUID workspaceId = createWorkspace(ALICE);
        String state = startOauth(workspaceId, ALICE, StripeConnectionMode.TEST);
        STRIPE_STUB.respondToToken(
                200,
                """
                {"stripe_user_id":"acct_replay","scope":"read_only","livemode":false,"token_type":"bearer"}""");

        mockMvc.perform(callbackRequestBuilder(state, "ac_once", "read_only", null)).andExpect(status().isOk());
        assertThat(STRIPE_STUB.tokenRequests).hasSize(1);

        mockMvc.perform(callbackRequestBuilder(state, "ac_once", "read_only", null)).andExpect(status().isBadRequest());
        assertThat(STRIPE_STUB.tokenRequests).hasSize(1);
    }

    @Test
    void malformedStateIsRejectedWithoutCallingStripe() throws Exception {
        mockMvc.perform(callbackRequestBuilder("not-a-real-state-token", "ac_malformed", "read_only", null))
                .andExpect(status().isBadRequest());
        assertThat(STRIPE_STUB.tokenRequests).isEmpty();
    }

    @Test
    void mismatchedScopeIsRejectedAndConsumesTheStateAnyway() throws Exception {
        UUID workspaceId = createWorkspace(ALICE);
        String state = startOauth(workspaceId, ALICE, StripeConnectionMode.TEST);

        mockMvc.perform(callbackRequestBuilder(state, "ac_mismatch", "read_write", null)).andExpect(status().isBadRequest());
        assertThat(STRIPE_STUB.tokenRequests).isEmpty();

        // The state was consumed even though the callback failed; it cannot be retried with a
        // corrected scope.
        mockMvc.perform(callbackRequestBuilder(state, "ac_mismatch", "read_only", null)).andExpect(status().isBadRequest());
        assertThat(STRIPE_STUB.tokenRequests).isEmpty();
    }

    @Test
    void deniedAuthorizationIsRejectedWithoutCallingStripe() throws Exception {
        UUID workspaceId = createWorkspace(ALICE);
        String state = startOauth(workspaceId, ALICE, StripeConnectionMode.TEST);

        mockMvc.perform(callbackRequestBuilder(state, null, null, "access_denied")).andExpect(status().isBadRequest());
        assertThat(STRIPE_STUB.tokenRequests).isEmpty();
    }

    @Test
    void successfulCallbackPersistsOnlySafeMetadataAndVerifiesTheConnection() throws Exception {
        UUID workspaceId = createWorkspace(ALICE);
        String state = startOauth(workspaceId, ALICE, StripeConnectionMode.TEST);
        STRIPE_STUB.respondToToken(
                200,
                """
                {"stripe_user_id":"acct_success","scope":"read_only","livemode":false,
                 "token_type":"bearer","access_token":"sk_should_never_be_read","refresh_token":"rt_ignored"}""");

        MvcResult result = mockMvc.perform(callbackRequestBuilder(state, "ac_success", "read_only", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stripeAccountId").value("acct_success"))
                .andExpect(jsonPath("$.mode").value("TEST"))
                .andExpect(jsonPath("$.grantedScope").value("read_only"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.verificationStatus").value("VERIFIED"))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertThat(responseBody).doesNotContain("access_token", "refresh_token", "sk_should_never_be_read", "rt_ignored");

        mockMvc.perform(get("/api/workspaces/{id}/stripe-connection", workspaceId).with(token(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stripeAccountId").value("acct_success"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void noTokenOrSecretMaterialIsEverPersisted() throws Exception {
        UUID workspaceId = createWorkspace(ALICE);
        connectSuccessfully(workspaceId, ALICE, StripeConnectionMode.TEST, "acct_no_secrets", false);

        Map<String, Object> stored = jdbc.sql("SELECT * FROM stripe_connections WHERE workspace_id = :workspaceId")
                .param("workspaceId", workspaceId)
                .query()
                .singleRow();
        assertThat(stored).containsKeys("stripe_account_id", "mode", "granted_scope", "status", "verification_status");

        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM information_schema.columns
                        WHERE table_name = 'stripe_connections'
                          AND column_name IN ('access_token', 'refresh_token', 'client_secret', 'platform_key')
                        """)
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    void reconnectingUpdatesTheSingleRowInPlace() throws Exception {
        UUID workspaceId = createWorkspace(ALICE);
        connectSuccessfully(workspaceId, ALICE, StripeConnectionMode.TEST, "acct_first", false);
        UUID firstId = jdbc.sql("SELECT id FROM stripe_connections WHERE workspace_id = :workspaceId")
                .param("workspaceId", workspaceId)
                .query(UUID.class)
                .single();

        connectSuccessfully(workspaceId, ALICE, StripeConnectionMode.TEST, "acct_second", false);

        assertThat(jdbc.sql("SELECT COUNT(*) FROM stripe_connections WHERE workspace_id = :workspaceId")
                        .param("workspaceId", workspaceId)
                        .query(Integer.class)
                        .single())
                .isOne();
        UUID secondId = jdbc.sql("SELECT id FROM stripe_connections WHERE workspace_id = :workspaceId")
                .param("workspaceId", workspaceId)
                .query(UUID.class)
                .single();
        assertThat(secondId).isEqualTo(firstId);
        assertThat(jdbc.sql("SELECT stripe_account_id FROM stripe_connections WHERE id = :id")
                        .param("id", firstId)
                        .query(String.class)
                        .single())
                .isEqualTo("acct_second");
    }

    @Test
    void revokedOrInvalidAuthorizationProducesAnActionableHealthState() throws Exception {
        UUID workspaceId = createWorkspace(ALICE);
        String state = startOauth(workspaceId, ALICE, StripeConnectionMode.TEST);
        STRIPE_STUB.respondToToken(
                200,
                """
                {"stripe_user_id":"acct_revoked","scope":"read_only","livemode":false,"token_type":"bearer"}""");
        STRIPE_STUB.respondToAccount(401, "{\"error\":{\"type\":\"invalid_request_error\"}}");

        mockMvc.perform(callbackRequestBuilder(state, "ac_revoked", "read_only", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.verificationStatus").value("FAILED"));

        mockMvc.perform(get("/api/workspaces/{id}/stripe-connection", workspaceId).with(token(ALICE)))
                .andExpect(jsonPath("$.status").value("REVOKED"));
    }

    @Test
    void transientVerificationFailureDoesNotFalselyMarkTheConnectionRevoked() throws Exception {
        UUID workspaceId = createWorkspace(ALICE);
        String state = startOauth(workspaceId, ALICE, StripeConnectionMode.TEST);
        STRIPE_STUB.respondToAccount(500, "{\"error\":{\"type\":\"api_error\"}}");

        mockMvc.perform(callbackRequestBuilder(state, "ac_transient", "read_only", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.verificationStatus").value("FAILED"));
    }

    @Test
    void disconnectStopsIngestionIdempotentlyWithoutDeletingHistory() throws Exception {
        UUID workspaceId = createWorkspace(ALICE);
        connectSuccessfully(workspaceId, ALICE, StripeConnectionMode.TEST, "acct_disconnect", false);

        mockMvc.perform(delete("/api/workspaces/{id}/stripe-connection", workspaceId).with(token(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISCONNECTED"))
                .andExpect(jsonPath("$.stripeAccountId").value("acct_disconnect"));
        assertThat(STRIPE_STUB.deauthorizeRequests).hasSize(1);

        mockMvc.perform(delete("/api/workspaces/{id}/stripe-connection", workspaceId).with(token(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISCONNECTED"));
        assertThat(STRIPE_STUB.deauthorizeRequests).hasSize(1);

        assertThat(jdbc.sql("SELECT COUNT(*) FROM stripe_connections WHERE workspace_id = :workspaceId")
                        .param("workspaceId", workspaceId)
                        .query(Integer.class)
                        .single())
                .isOne();
        mockMvc.perform(get("/api/workspaces/{id}/stripe-connection", workspaceId).with(token(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stripeAccountId").value("acct_disconnect"));
    }

    @Test
    void testAndLiveModeUseSeparatePlatformSecretKeys() throws Exception {
        UUID testWorkspace = createWorkspace(ALICE);
        UUID liveWorkspace = createWorkspace(BOB);

        STRIPE_STUB.respondToToken(
                200,
                """
                {"stripe_user_id":"acct_test_mode","scope":"read_only","livemode":false,"token_type":"bearer"}""");
        String testState = startOauth(testWorkspace, ALICE, StripeConnectionMode.TEST);
        mockMvc.perform(callbackRequestBuilder(testState, "ac_test_mode", "read_only", null)).andExpect(status().isOk());

        STRIPE_STUB.respondToToken(
                200,
                """
                {"stripe_user_id":"acct_live_mode","scope":"read_only","livemode":true,"token_type":"bearer"}""");
        String liveState = startOauth(liveWorkspace, BOB, StripeConnectionMode.LIVE);
        mockMvc.perform(callbackRequestBuilder(liveState, "ac_live_mode", "read_only", null)).andExpect(status().isOk());

        assertThat(STRIPE_STUB.tokenRequests).hasSize(2);
        StripeApiStub.RecordedRequest testRequest = STRIPE_STUB.tokenRequests.stream()
                .filter(r -> r.body().contains("code=ac_test_mode"))
                .findFirst()
                .orElseThrow();
        StripeApiStub.RecordedRequest liveRequest = STRIPE_STUB.tokenRequests.stream()
                .filter(r -> r.body().contains("code=ac_live_mode"))
                .findFirst()
                .orElseThrow();
        assertThat(testRequest.secretKeyUsed()).isEqualTo(TEST_SECRET_KEY);
        assertThat(liveRequest.secretKeyUsed()).isEqualTo(LIVE_SECRET_KEY);

        StripeApiStub.RecordedRequest testAccountCheck = STRIPE_STUB.accountRequests.stream()
                .filter(r -> r.uri().toString().contains("acct_test_mode"))
                .findFirst()
                .orElseThrow();
        StripeApiStub.RecordedRequest liveAccountCheck = STRIPE_STUB.accountRequests.stream()
                .filter(r -> r.uri().toString().contains("acct_live_mode"))
                .findFirst()
                .orElseThrow();
        assertThat(testAccountCheck.secretKeyUsed()).isEqualTo(TEST_SECRET_KEY);
        assertThat(liveAccountCheck.secretKeyUsed()).isEqualTo(LIVE_SECRET_KEY);
    }

    @Test
    void livemodeMismatchBetweenRequestedModeAndStripeResponseIsRejected() throws Exception {
        UUID workspaceId = createWorkspace(ALICE);
        String state = startOauth(workspaceId, ALICE, StripeConnectionMode.TEST);
        STRIPE_STUB.respondToToken(
                200,
                """
                {"stripe_user_id":"acct_wrong_mode","scope":"read_only","livemode":true,"token_type":"bearer"}""");

        mockMvc.perform(callbackRequestBuilder(state, "ac_wrong_mode", "read_only", null)).andExpect(status().isBadGateway());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM stripe_connections WHERE workspace_id = :workspaceId")
                        .param("workspaceId", workspaceId)
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    private void connectSuccessfully(
            UUID workspaceId, String subject, StripeConnectionMode mode, String accountId, boolean livemode)
            throws Exception {
        String state = startOauth(workspaceId, subject, mode);
        STRIPE_STUB.respondToToken(
                200,
                """
                {"stripe_user_id":"%s","scope":"read_only","livemode":%s,"token_type":"bearer"}"""
                        .formatted(accountId, livemode));
        STRIPE_STUB.respondToAccount(200, "{\"id\":\"" + accountId + "\"}");
        mockMvc.perform(callbackRequestBuilder(state, "ac_" + UUID.randomUUID(), "read_only", null))
                .andExpect(status().isOk());
    }

    private String startOauth(UUID workspaceId, String subject, StripeConnectionMode mode) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/workspaces/{id}/stripe-connection/oauth/start", workspaceId)
                        .with(token(subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("mode", mode.name()))))
                .andExpect(status().isOk())
                .andReturn();
        String authorizationUrl = json(result).get("authorizationUrl").asText();
        return UriComponentsBuilder.fromUriString(authorizationUrl)
                .build()
                .getQueryParams()
                .getFirst("state");
    }

    private void expireState(UUID workspaceId) {
        // expires_at must stay after created_at (a DB CHECK constraint), so both are backdated.
        jdbc.sql("""
                        UPDATE stripe_oauth_states
                        SET created_at = :createdAt, expires_at = :expiresAt
                        WHERE workspace_id = :workspaceId
                        """)
                .param("createdAt", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(20))
                .param("expiresAt", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5))
                .param("workspaceId", workspaceId)
                .update();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder callbackRequestBuilder(
            String state, String code, String scope, String error) {
        var builder = get("/api/stripe/connections/oauth/callback").queryParam("state", state);
        if (code != null) {
            builder = builder.queryParam("code", code);
        }
        if (scope != null) {
            builder = builder.queryParam("scope", scope);
        }
        if (error != null) {
            builder = builder.queryParam("error", error);
        }
        return builder;
    }

    private UUID createWorkspace(String subject) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/workspaces")
                        .with(token(subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Workspace " + subject,
                                "slug", "workspace-" + subject + "-" + UUID.randomUUID(),
                                "reportingCurrency", "USD"))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(json(result).get("id").asText());
    }

    private void addMember(UUID workspaceId, String actingSubject, String subjectId, String role) throws Exception {
        mockMvc.perform(post("/api/workspaces/{workspaceId}/members", workspaceId)
                        .with(token(actingSubject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("subjectId", subjectId, "role", role))))
                .andExpect(status().isCreated());
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private RequestPostProcessor token(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject)
                .issuer("http://localhost:8081/realms/mrr-origin")
                .audience(List.of("mrr-origin-api")));
    }
}
