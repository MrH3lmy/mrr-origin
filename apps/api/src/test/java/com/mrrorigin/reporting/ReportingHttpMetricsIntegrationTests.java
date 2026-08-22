package com.mrrorigin.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * P6 observability slice (#28/#90): proves reporting latency/error visibility is already answered
 * by Spring's auto-instrumented {@code http.server.requests} timer -- criteria #11/#12 from the
 * issue ("reporting timer records successful request/operation" / "reporting failure path is
 * visible"), without a duplicate custom timer. See
 * docs/operations/observability-runbook.md's SLI catalog entry for this route.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ReportingHttpMetricsIntegrationTests {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:18-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    private static final String OWNER = "user-owner";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient db;

    @Autowired
    private MeterRegistry meterRegistry;

    private UUID workspace;
    private UUID project;

    @BeforeEach
    void setUp() {
        db.sql("TRUNCATE workspaces CASCADE").update();
        workspace = UUID.randomUUID();
        project = UUID.randomUUID();
        db.sql("INSERT INTO workspaces (id, name, slug) VALUES (:id, 'w', :slug)")
                .param("id", workspace)
                .param("slug", "w-" + workspace)
                .update();
        db.sql("INSERT INTO workspace_members (workspace_id, subject_id, role) VALUES (:w, :s, 'OWNER')")
                .param("w", workspace)
                .param("s", OWNER)
                .update();
        db.sql("INSERT INTO projects (id, workspace_id, name, domain, public_key) VALUES (:p, :w, 'p', 'one.example', :k)")
                .param("p", project)
                .param("w", workspace)
                .param("k", "pk-" + project)
                .update();
    }

    private RequestPostProcessor token(String subject) {
        return jwt().jwt(j -> j.subject(subject)
                .issuer("http://localhost:8081/realms/mrr-origin")
                .audience(List.of("mrr-origin-api")));
    }

    private long timerCount(String outcomeTag) {
        return meterRegistry.find("http.server.requests")
                .tag("uri", "/api/workspaces/{workspaceId}/projects/{projectId}/reporting/overview")
                .tag("outcome", outcomeTag)
                .timers()
                .stream()
                .mapToLong(Timer::count)
                .sum();
    }

    @Test
    void successfulReportingRequestIsRecordedByTheAutoInstrumentedTimer() throws Exception {
        long before = timerCount("SUCCESS");

        mockMvc.perform(get("/api/workspaces/{workspaceId}/projects/{projectId}/reporting/overview", workspace, project)
                        .queryParam("from", "2026-04-01T00:00:00Z")
                        .queryParam("to", "2026-05-01T00:00:00Z")
                        .with(token(OWNER)))
                .andExpect(status().isOk());

        long after = timerCount("SUCCESS");
        assertThat(after).isGreaterThan(before);
    }

    @Test
    void clientErrorOnReportingIsRecordedWithAClientErrorOutcome() throws Exception {
        long before = timerCount("CLIENT_ERROR");

        // from == to is rejected by RevenueOverviewService#require -> 400, mapped by the
        // IllegalArgumentException handler.
        mockMvc.perform(get("/api/workspaces/{workspaceId}/projects/{projectId}/reporting/overview", workspace, project)
                        .queryParam("from", "2026-04-01T00:00:00Z")
                        .queryParam("to", "2026-04-01T00:00:00Z")
                        .with(token(OWNER)))
                .andExpect(status().isBadRequest());

        long after = timerCount("CLIENT_ERROR");
        assertThat(after).isGreaterThan(before);
    }
}
