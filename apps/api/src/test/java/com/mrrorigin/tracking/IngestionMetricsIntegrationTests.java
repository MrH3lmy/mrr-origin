package com.mrrorigin.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * P6 observability slice (#28/#90): proves the public-ingestion accepted/duplicate/rejected counters
 * reflect real HTTP outcomes, and that two workspaces' traffic aggregates into the same series
 * (never a per-workspace/per-project meter).
 */
@Testcontainers
class IngestionMetricsIntegrationTests extends AbstractTrackingIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    private double counter(String name, String tagKey, String tagValue) {
        Counter c = meterRegistry.find(name).tag(tagKey, tagValue).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void acceptedEventIncrementsAcceptedCounterAcrossTwoWorkspaces() throws Exception {
        double before = counter("mrrorigin.ingestion.events", "result", "accepted");

        UUID workspaceA = createWorkspace(OWNER);
        UUID projectA = createProject(workspaceA);
        String keyA = issueKey(workspaceA, projectA);
        allowDomain(workspaceA, projectA, "app-a.example");
        mockMvc.perform(ingest(keyA, "https://app-a.example",
                        pageViewBatch("batch-a", "evt-a", "visitor-a", OffsetDateTime.now().toString())))
                .andExpect(status().isOk());

        UUID workspaceB = createWorkspace(OTHER_OWNER);
        UUID projectB = createProject(workspaceB);
        String keyB = issueKey(workspaceB, projectB);
        allowDomain(workspaceB, projectB, "app-b.example");
        mockMvc.perform(ingest(keyB, "https://app-b.example",
                        pageViewBatch("batch-b", "evt-b", "visitor-b", OffsetDateTime.now().toString())))
                .andExpect(status().isOk());

        double after = counter("mrrorigin.ingestion.events", "result", "accepted");
        assertThat(after).isGreaterThanOrEqualTo(before + 2);
    }

    @Test
    void duplicateEventIncrementsDuplicateCounter() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);
        String key = issueKey(workspaceId, projectId);
        allowDomain(workspaceId, projectId, "app.example");
        String body = pageViewBatch("batch-dup", "evt-dup", "visitor-dup", OffsetDateTime.now().toString());
        mockMvc.perform(ingest(key, "https://app.example", body)).andExpect(status().isOk());

        double before = counter("mrrorigin.ingestion.events", "result", "duplicate");
        String secondBatchSameEvent = pageViewBatch("batch-dup-2", "evt-dup", "visitor-dup", OffsetDateTime.now().toString());
        mockMvc.perform(ingest(key, "https://app.example", secondBatchSameEvent)).andExpect(status().isOk());
        double after = counter("mrrorigin.ingestion.events", "result", "duplicate");

        assertThat(after).isGreaterThan(before);
    }

    @Test
    void invalidKeyIncrementsRejectedCounter() throws Exception {
        double before = counter("mrrorigin.ingestion.rejected", "reason", "invalid_key");

        mockMvc.perform(ingest("ik_live_does_not_exist_at_all", "https://app.example",
                        pageViewBatch("batch-bad-key", "evt-bad-key", "visitor-x", OffsetDateTime.now().toString())))
                .andExpect(status().isUnauthorized());

        double after = counter("mrrorigin.ingestion.rejected", "reason", "invalid_key");
        assertThat(after).isGreaterThan(before);
    }

    @Test
    void blockedOriginIncrementsRejectedCounter() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);
        String key = issueKey(workspaceId, projectId);
        // Deliberately not calling allowDomain -- the origin below is never allow-listed.

        double before = counter("mrrorigin.ingestion.rejected", "reason", "blocked_origin");
        mockMvc.perform(ingest(key, "https://not-allowed.example",
                        pageViewBatch("batch-blocked", "evt-blocked", "visitor-blocked", OffsetDateTime.now().toString())))
                .andExpect(status().isForbidden());
        double after = counter("mrrorigin.ingestion.rejected", "reason", "blocked_origin");

        assertThat(after).isGreaterThan(before);
    }
}
