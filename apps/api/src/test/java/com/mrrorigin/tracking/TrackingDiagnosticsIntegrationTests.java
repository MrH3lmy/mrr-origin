package com.mrrorigin.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
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

/**
 * #8's project tracking diagnostics: RECEIVING once traffic is accepted, identity coverage, tenant
 * isolation, and that no report can ever leak a raw ingestion secret, a secret hash, or another
 * project's data.
 */
@Testcontainers
@Import(TrackingDiagnosticsIntegrationTests.FixedClockConfiguration.class)
class TrackingDiagnosticsIntegrationTests extends AbstractTrackingIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    private static final String NOW = "2026-08-11T12:00:00Z";

    @Autowired
    private MockMvc mvc;

    @Test
    void receivingStateAfterAnAcceptedEventReportsTheLastEventTypeAndTimestamp() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);
        String key = issueKey(workspaceId, projectId);
        allowDomain(workspaceId, projectId, "app.example");

        mvc.perform(ingest(key, "https://app.example", pageViewBatch("batch-1", "event-1", "visitor-1", NOW)))
                .andExpect(status().isOk());

        mvc.perform(get(diagnosticsPath(workspaceId, projectId)).with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RECEIVING"))
                .andExpect(jsonPath("$.everReceivedTraffic").value(true))
                .andExpect(jsonPath("$.lastAcceptedEventType").value("page_view"))
                .andExpect(jsonPath("$.lastAcceptedEventAt").value(NOW));
    }

    @Test
    void identityCoverageCountsIdentifiedVersusTotalVisitors() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);
        String key = issueKey(workspaceId, projectId);
        allowDomain(workspaceId, projectId, "app.example");

        mvc.perform(ingest(key, "https://app.example", pageViewBatch("batch-1", "event-1", "anon-visitor", NOW)))
                .andExpect(status().isOk());
        mvc.perform(ingest(key, "https://app.example",
                        identifyBatch("batch-2", "event-2", "known-visitor", "known-session", "user-1", NOW)))
                .andExpect(status().isOk());

        mvc.perform(get(diagnosticsPath(workspaceId, projectId)).with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identityCoverage.totalVisitors").value(2))
                .andExpect(jsonPath("$.identityCoverage.identifiedVisitors").value(1));
    }

    @Test
    void diagnosticsNeverExposeTheRawIngestionKeyOrItsHash() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);
        String key = issueKey(workspaceId, projectId);
        allowDomain(workspaceId, projectId, "app.example");
        mvc.perform(ingest(key, "https://app.example", pageViewBatch("batch-1", "event-1", "visitor-1", NOW)))
                .andExpect(status().isOk());
        String wrongSecret = key.substring(0, key.lastIndexOf('_') + 1) + "0".repeat(64);
        mvc.perform(ingest(wrongSecret, "https://app.example", pageViewBatch("batch-2", "event-2", "visitor-2", NOW)))
                .andExpect(status().isUnauthorized());

        String secretHash = jdbc()
                .sql("SELECT secret_hash FROM project_ingestion_keys WHERE workspace_id = :w AND project_id = :p")
                .param("w", workspaceId)
                .param("p", projectId)
                .query(String.class)
                .single();

        String body = mvc.perform(get(diagnosticsPath(workspaceId, projectId)).with(token(OWNER)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("\"count\":1"); // the INVALID_KEY diagnostic really was recorded
        assertThat(body).doesNotContain(key);
        assertThat(body).doesNotContain(wrongSecret);
        assertThat(body).doesNotContainIgnoringCase(secretHash);
        assertThat(body).doesNotContain("secretHash");
        assertThat(body).doesNotContain("pageUrl");
    }

    @Test
    void anotherWorkspacesProjectDiagnosticsAreNotFound() throws Exception {
        UUID workspaceA = createWorkspace(OWNER);
        UUID projectA = createProject(workspaceA);
        UUID workspaceB = createWorkspace(OWNER);
        UUID projectB = createProject(workspaceB);

        mvc.perform(get(diagnosticsPath(workspaceA, projectB)).with(token(OWNER)))
                .andExpect(status().isNotFound());
        mvc.perform(get(diagnosticsPath(workspaceB, projectA)).with(token(OWNER)))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonMemberCannotReadDiagnostics() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);

        mvc.perform(get(diagnosticsPath(workspaceId, projectId)).with(token(OTHER_OWNER)))
                .andExpect(status().isNotFound());
    }

    private static String identifyBatch(
            String batchId, String eventId, String visitorId, String sessionId, String externalUserId, String occurredAt) {
        return """
                {"version":1,"batchId":"%s","events":[
                  {"eventId":"%s","visitorId":"%s","sessionId":"%s","type":"identify",\
                   "occurredAt":"%s","payload":{"externalUserId":"%s"}}
                ]}
                """.formatted(batchId, eventId, visitorId, sessionId, occurredAt, externalUserId);
    }

    private static String diagnosticsPath(UUID workspaceId, UUID projectId) {
        return "/api/workspaces/%s/projects/%s/tracking/diagnostics".formatted(workspaceId, projectId);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse(NOW), ZoneOffset.UTC);
        }
    }
}
