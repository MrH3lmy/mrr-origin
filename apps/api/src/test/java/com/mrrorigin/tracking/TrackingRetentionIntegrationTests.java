package com.mrrorigin.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** #8's configurable, bounded, cutoff-based tracking-data retention. */
@Testcontainers
@Import(TrackingRetentionIntegrationTests.FixedClockConfiguration.class)
class TrackingRetentionIntegrationTests extends AbstractTrackingIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Test
    void defaultRetentionAppliesUntilExplicitlyConfigured() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);

        mvc.perform(get(retentionPath(workspaceId, projectId)).with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retentionDays").value(TrackingRetentionSettingsService.DEFAULT_RETENTION_DAYS));

        mvc.perform(put(retentionPath(workspaceId, projectId)).with(token(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"retentionDays\":45}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retentionDays").value(45));
        mvc.perform(get(retentionPath(workspaceId, projectId)).with(token(OWNER)))
                .andExpect(jsonPath("$.retentionDays").value(45));
    }

    @Test
    void memberWithoutManagePermissionCannotUpdateRetention() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        addMember(workspaceId, VIEWER, "VIEWER");
        UUID projectId = createProject(workspaceId);

        mvc.perform(put(retentionPath(workspaceId, projectId)).with(token(VIEWER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"retentionDays\":45}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void cutoffIsExclusiveAtTheBoundary() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);
        setRetentionDays(workspaceId, projectId, 30);
        UUID visitorId = insertVisitor(workspaceId, projectId, "visitor-1");

        OffsetDateTime cutoff = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusDays(30);
        UUID beforeCutoff = insertEnvelope(
                workspaceId, projectId, visitorId, null, null, "before-cutoff", cutoff.minusSeconds(1), cutoff.minusSeconds(1));
        UUID atCutoff = insertEnvelope(workspaceId, projectId, visitorId, null, null, "at-cutoff", cutoff, cutoff);
        UUID afterCutoff = insertEnvelope(
                workspaceId, projectId, visitorId, null, null, "after-cutoff",
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));

        mvc.perform(post(retentionRunPath(workspaceId, projectId)).with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.envelopesDeleted").value(1))
                .andExpect(jsonPath("$.complete").value(true));

        assertThat(envelopeIds(workspaceId, projectId)).containsExactlyInAnyOrder(atCutoff, afterCutoff);
        assertThat(envelopeIds(workspaceId, projectId)).doesNotContain(beforeCutoff);
    }

    @Test
    void retentionDeletesAtMostMaxRowsPerBatchAndConvergesAcrossRepeatedCalls() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);
        setRetentionDays(workspaceId, projectId, 1);
        UUID visitorId = insertVisitor(workspaceId, projectId, "visitor-1");
        OffsetDateTime old = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusDays(10);
        for (int i = 0; i < 25; i++) {
            insertEnvelope(workspaceId, projectId, visitorId, null, null, "old-event-" + i, old, old);
        }

        String firstBatch = mvc.perform(post(retentionRunPath(workspaceId, projectId)).param("maxRows", "10").with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.envelopesDeleted").value(10))
                .andExpect(jsonPath("$.complete").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(firstBatch).contains("\"envelopesDeleted\":10");

        mvc.perform(post(retentionRunPath(workspaceId, projectId)).param("maxRows", "10").with(token(OWNER)))
                .andExpect(jsonPath("$.envelopesDeleted").value(10))
                .andExpect(jsonPath("$.complete").value(false));

        // Third (and final) bounded batch drains the remaining 5 -- fewer than maxRows, so complete.
        mvc.perform(post(retentionRunPath(workspaceId, projectId)).param("maxRows", "10").with(token(OWNER)))
                .andExpect(jsonPath("$.envelopesDeleted").value(5))
                .andExpect(jsonPath("$.complete").value(true));

        assertThat(envelopeIds(workspaceId, projectId)).isEmpty();

        // Idempotent / safe under retry: calling again once fully drained deletes nothing further.
        mvc.perform(post(retentionRunPath(workspaceId, projectId)).param("maxRows", "10").with(token(OWNER)))
                .andExpect(jsonPath("$.envelopesDeleted").value(0))
                .andExpect(jsonPath("$.complete").value(true));
    }

    @Test
    void retentionAlsoRemovesOrphanedBatchesAndOldFailureDiagnosticsButNeverTouchpointsOrVisitors() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);
        setRetentionDays(workspaceId, projectId, 1);
        UUID visitorId = insertVisitor(workspaceId, projectId, "visitor-1");
        UUID sessionId = insertSession(workspaceId, projectId, visitorId, "session-1");
        insertTouchpoint(workspaceId, projectId, visitorId, sessionId);
        OffsetDateTime old = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusDays(10);
        UUID batchId = insertBatch(workspaceId, projectId, "old-batch", old);
        insertEnvelope(workspaceId, projectId, visitorId, sessionId, batchId, "old-event", old, old);
        insertFailure(workspaceId, projectId, "BLOCKED_ORIGIN", old);

        mvc.perform(post(retentionRunPath(workspaceId, projectId)).with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.envelopesDeleted").value(1))
                .andExpect(jsonPath("$.batchesDeleted").value(1))
                .andExpect(jsonPath("$.failuresDeleted").value(1));

        assertThat(count("tracking_ingestion_batches", workspaceId, projectId)).isZero();
        assertThat(count("tracking_ingestion_failures", workspaceId, projectId)).isZero();
        assertThat(count("touchpoints", workspaceId, projectId)).isEqualTo(1);
        assertThat(count("visitors", workspaceId, projectId)).isEqualTo(1);
        assertThat(count("tracking_sessions", workspaceId, projectId)).isEqualTo(1);
    }

    private void setRetentionDays(UUID workspaceId, UUID projectId, int days) throws Exception {
        mvc.perform(put(retentionPath(workspaceId, projectId)).with(token(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"retentionDays\":" + days + "}"))
                .andExpect(status().isOk());
    }

    private java.util.List<UUID> envelopeIds(UUID workspaceId, UUID projectId) {
        return jdbc().sql("SELECT id FROM tracking_event_envelopes WHERE workspace_id = :w AND project_id = :p")
                .param("w", workspaceId)
                .param("p", projectId)
                .query(UUID.class)
                .list();
    }

    private int count(String table, UUID workspaceId, UUID projectId) {
        return jdbc().sql("SELECT COUNT(*) FROM " + table + " WHERE workspace_id = :w AND project_id = :p")
                .param("w", workspaceId)
                .param("p", projectId)
                .query(Integer.class)
                .single();
    }

    private static String retentionPath(UUID workspaceId, UUID projectId) {
        return "/api/workspaces/%s/projects/%s/tracking/retention".formatted(workspaceId, projectId);
    }

    private static String retentionRunPath(UUID workspaceId, UUID projectId) {
        return retentionPath(workspaceId, projectId) + "/run";
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
