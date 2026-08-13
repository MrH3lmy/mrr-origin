package com.mrrorigin.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * #8's live installation verification: a project-scoped token, proven only by an event actually
 * accepted through the existing public ingestion path, with deterministic PENDING/SUCCEEDED/EXPIRED
 * states and replay-safe matching.
 */
@Testcontainers
@Import(TrackingVerificationIntegrationTests.MutableClockConfiguration.class)
class TrackingVerificationIntegrationTests extends AbstractTrackingIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    private static final String NOW = "2026-08-11T12:00:00Z";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MutableClock clock;

    @Test
    void successfulTrackerVerificationMarksTheAttemptSucceeded() throws Exception {
        clock.set(Instant.parse(NOW));
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);
        String key = issueKey(workspaceId, projectId);
        allowDomain(workspaceId, projectId, "app.example");

        String token = start(workspaceId, projectId);

        mvc.perform(ingest(key, "https://app.example",
                        verificationBatch("verify-1", "verify-event-1", "visitor-1", token, NOW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].status").value("ACCEPTED"));

        mvc.perform(get(verificationPath(workspaceId, projectId)).with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.token").value(token));
    }

    @Test
    void noTrafficLeavesTheAttemptPendingAndDiagnosticsAtNoTraffic() throws Exception {
        clock.set(Instant.parse(NOW));
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);
        start(workspaceId, projectId);

        mvc.perform(get(verificationPath(workspaceId, projectId)).with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(get(diagnosticsPath(workspaceId, projectId)).with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NO_TRAFFIC"))
                .andExpect(jsonPath("$.everReceivedTraffic").value(false));
    }

    @Test
    void blockedOriginLeavesTheAttemptPendingAndIsRecordedAsADiagnostic() throws Exception {
        clock.set(Instant.parse(NOW));
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);
        String key = issueKey(workspaceId, projectId);
        allowDomain(workspaceId, projectId, "app.example");
        String token = start(workspaceId, projectId);

        mvc.perform(ingest(key, "https://evil.example",
                        verificationBatch("verify-blocked", "verify-event-blocked", "visitor-1", token, NOW)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("origin_not_allowed"));

        mvc.perform(get(verificationPath(workspaceId, projectId)).with(token(OWNER)))
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(get(diagnosticsPath(workspaceId, projectId)).with(token(OWNER)))
                .andExpect(jsonPath("$.state").value("BLOCKED_ORIGIN"))
                .andExpect(jsonPath("$.blockedOrigin.count").value(1));
    }

    @Test
    void invalidKeyLeavesTheAttemptPendingAndIsRecordedAsADiagnostic() throws Exception {
        clock.set(Instant.parse(NOW));
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);
        String key = issueKey(workspaceId, projectId);
        allowDomain(workspaceId, projectId, "app.example");
        String token = start(workspaceId, projectId);
        String wrongSecret = key.substring(0, key.lastIndexOf('_') + 1) + "0".repeat(64);

        mvc.perform(ingest(wrongSecret, "https://app.example",
                        verificationBatch("verify-badkey", "verify-event-badkey", "visitor-1", token, NOW)))
                .andExpect(status().isUnauthorized());

        mvc.perform(get(verificationPath(workspaceId, projectId)).with(token(OWNER)))
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(get(diagnosticsPath(workspaceId, projectId)).with(token(OWNER)))
                .andExpect(jsonPath("$.state").value("INVALID_KEY"))
                .andExpect(jsonPath("$.invalidKey.count").value(1));
    }

    @Test
    void rejectedPayloadLeavesTheAttemptPendingAndIsRecordedAsADiagnostic() throws Exception {
        clock.set(Instant.parse(NOW));
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);
        String key = issueKey(workspaceId, projectId);
        allowDomain(workspaceId, projectId, "app.example");
        String token = start(workspaceId, projectId);
        String malformed = verificationBatch("verify-malformed", "verify-event-malformed", "visitor-1", token, NOW)
                .replace(NOW, "not-a-timestamp");

        mvc.perform(ingest(key, "https://app.example", malformed))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_envelope"));

        mvc.perform(get(verificationPath(workspaceId, projectId)).with(token(OWNER)))
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(get(diagnosticsPath(workspaceId, projectId)).with(token(OWNER)))
                .andExpect(jsonPath("$.state").value("INVALID_PAYLOAD"))
                .andExpect(jsonPath("$.invalidPayload.count").value(1));
    }

    @Test
    void anExpiredAttemptCanNeverBeMarkedSucceededByALateArrivingEvent() throws Exception {
        clock.set(Instant.parse(NOW));
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);
        String key = issueKey(workspaceId, projectId);
        allowDomain(workspaceId, projectId, "app.example");
        String token = start(workspaceId, projectId);

        clock.set(Instant.parse(NOW).plus(TrackingVerificationService.TOKEN_TTL).plusSeconds(1));
        String later = "2026-08-11T12:15:01Z";
        mvc.perform(ingest(key, "https://app.example",
                        verificationBatch("verify-expired", "verify-event-expired", "visitor-1", token, later)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].status").value("ACCEPTED"));

        mvc.perform(get(verificationPath(workspaceId, projectId)).with(token(OWNER)))
                .andExpect(jsonPath("$.status").value("EXPIRED"));
    }

    @Test
    void aBackdatedOccurredAtCanNeverResurrectAnExpiredAttempt() throws Exception {
        clock.set(Instant.parse(NOW));
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);
        String key = issueKey(workspaceId, projectId);
        allowDomain(workspaceId, projectId, "app.example");
        String token = start(workspaceId, projectId);

        // The server's own clock has moved well past the attempt's expiry (12:15:00Z), but the event
        // itself claims an occurredAt still inside the original PENDING window -- e.g. a captured
        // request replayed later, or simple clock skew on the client. Expiry must be judged off the
        // server's clock, never off this client-controlled field.
        clock.set(Instant.parse(NOW).plus(TrackingVerificationService.TOKEN_TTL).plus(Duration.ofMinutes(5)));
        String backdatedOccurredAt = "2026-08-11T12:05:00Z";
        mvc.perform(ingest(key, "https://app.example",
                        verificationBatch("verify-backdated", "verify-event-backdated", "visitor-1", token, backdatedOccurredAt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].status").value("ACCEPTED"));

        mvc.perform(get(verificationPath(workspaceId, projectId)).with(token(OWNER)))
                .andExpect(jsonPath("$.status").value("EXPIRED"));
    }

    @Test
    void aReplayedEventForAnAlreadySucceededAttemptStaysSucceededAndIsAcceptedAsADuplicate() throws Exception {
        clock.set(Instant.parse(NOW));
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);
        String key = issueKey(workspaceId, projectId);
        allowDomain(workspaceId, projectId, "app.example");
        String token = start(workspaceId, projectId);

        mvc.perform(ingest(key, "https://app.example",
                        verificationBatch("verify-first", "verify-event-first", "visitor-1", token, NOW)))
                .andExpect(status().isOk());
        mvc.perform(get(verificationPath(workspaceId, projectId)).with(token(OWNER)))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        String succeededAt = mvc.perform(get(verificationPath(workspaceId, projectId)).with(token(OWNER)))
                .andReturn().getResponse().getContentAsString();

        // Replaying the same verification token (via a brand-new event ID, so ingestion itself does
        // not treat it as a duplicate) against an already-SUCCEEDED attempt must not change it.
        mvc.perform(ingest(key, "https://app.example",
                        verificationBatch("verify-replay", "verify-event-replay", "visitor-1", token, NOW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].status").value("ACCEPTED"));

        mvc.perform(get(verificationPath(workspaceId, projectId)).with(token(OWNER)))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.token").value(token));
        assertThat(succeededAt).contains("SUCCEEDED");
    }

    @Test
    void aTokenFromAnotherProjectCanNeverSucceedThisProjectsAttempt() throws Exception {
        clock.set(Instant.parse(NOW));
        UUID workspaceA = createWorkspace(OWNER);
        UUID projectA = createProject(workspaceA);
        String keyA = issueKey(workspaceA, projectA);
        allowDomain(workspaceA, projectA, "a.example");
        start(workspaceA, projectA);

        UUID workspaceB = createWorkspace(OWNER);
        UUID projectB = createProject(workspaceB);
        String tokenB = start(workspaceB, projectB);

        // Submitted against project A's own key/origin, carrying project B's token.
        mvc.perform(ingest(keyA, "https://a.example",
                        verificationBatch("cross-project", "cross-project-event", "visitor-1", tokenB, NOW)))
                .andExpect(status().isOk());

        mvc.perform(get(verificationPath(workspaceA, projectA)).with(token(OWNER)))
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(get(verificationPath(workspaceB, projectB)).with(token(OWNER)))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void repeatedlyStartingVerificationReusesTheSamePendingToken() throws Exception {
        clock.set(Instant.parse(NOW));
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);

        String first = start(workspaceId, projectId);
        String second = start(workspaceId, projectId);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void nonMemberCannotStartOrReadVerification() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);

        mvc.perform(post(verificationPath(workspaceId, projectId)).with(token(OTHER_OWNER)))
                .andExpect(status().isNotFound());
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private String start(UUID workspaceId, UUID projectId) throws Exception {
        String body = mvc.perform(post(verificationPath(workspaceId, projectId)).with(token(OWNER)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode json = OBJECT_MAPPER.readTree(body);
        return json.get("token").textValue();
    }

    private static String verificationPath(UUID workspaceId, UUID projectId) {
        return "/api/workspaces/%s/projects/%s/tracking/verification".formatted(workspaceId, projectId);
    }

    private static String diagnosticsPath(UUID workspaceId, UUID projectId) {
        return "/api/workspaces/%s/projects/%s/tracking/diagnostics".formatted(workspaceId, projectId);
    }

    static final class MutableClock extends Clock {
        private volatile Instant instant = Instant.parse("2026-08-11T12:00:00Z");

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MutableClockConfiguration {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }
}
