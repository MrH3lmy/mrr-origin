package com.mrrorigin.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * P6 observability slice (#28/#90): proves the documented security/exposure contract for
 * {@code /actuator/prometheus} -- see docs/operations/observability-runbook.md's "Scraper
 * expectations" section, which this test is the automated backing for.
 *
 * <p>Criteria #15/#16/#17 from the issue: the Prometheus endpoint is reachable without
 * authentication and emits the custom metrics this slice adds; every other unapproved Actuator
 * endpoint remains unavailable regardless of authentication.
 *
 * <p>Also covers private-beta exact-release-SHA traceability (#104): {@code /actuator/info} exposes
 * the full build commit hash, backed by {@code git.properties} actually landing on a normal build's
 * classpath -- see docs/private-beta/pre-beta-smoke-test.md's "confirm the deployed artifacts can be
 * mapped back to the recorded SHA" precondition.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PrometheusEndpointIntegrationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    private static RequestPostProcessor token(String subject) {
        return jwt().jwt(j -> j.subject(subject)
                .issuer("http://localhost:8081/realms/mrr-origin")
                .audience(List.of("mrr-origin-api")));
    }

    @Test
    void prometheusEndpointIsReachableWithoutAuthenticationAndExposesCustomMetrics() throws Exception {
        String body = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Prometheus naming: dots -> underscores, counters get a _total suffix.
        assertThat(body).contains("mrrorigin_ingestion_events_total");
        assertThat(body).contains("mrrorigin_stripe_webhook_pending");
        assertThat(body).contains("mrrorigin_attribution_recalculation_running");
        assertThat(body).contains("mrrorigin_notification_weekly_summary_deliveries");
        assertThat(body).contains("mrrorigin_revenue_calculation_invocations_total");
        assertThat(body).contains("mrrorigin_revenue_calculation_supported_snapshots");
    }

    @Test
    void unapprovedActuatorEndpointsRemainUnavailableRegardlessOfAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/beans")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/heapdump")).andExpect(status().isUnauthorized());

        // Authenticated, but still not exposed at all (management.endpoints.web.exposure.include
        // never lists them) -- proves this isn't merely a security-layer coincidence.
        mockMvc.perform(get("/actuator/env").with(token("user-1"))).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/beans").with(token("user-1"))).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/heapdump").with(token("user-1"))).andExpect(status().isNotFound());
    }

    @Test
    void healthAndInfoRemainPublicAlongsidePrometheus() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/info")).andExpect(status().isOk());
    }

    /**
     * Private-beta readiness: exact-release-SHA traceability. {@code git.commit.id.full} must be a
     * full 40-character hex commit hash, not the plugin's abbreviated form and not merely present
     * as an arbitrary string -- this is the field the pre-beta smoke test's "confirm the deployed
     * artifacts can be mapped back to the recorded SHA" precondition is verified against.
     */
    @Test
    void infoEndpointExposesTheFullBuildCommitId() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.git.commit.id.full").value(matchesPattern("^[0-9a-f]{40}$")))
                .andExpect(jsonPath("$.git.branch").exists());
    }

    /**
     * {@code git.properties} is generated at build time by the git-commit-id-maven-plugin (bound to
     * the "initialize" phase) and copied to {@code target/classes} by the standard resources phase
     * before compilation/packaging -- proving it is genuinely present on a normal build's classpath,
     * not just producible by manually invoking the plugin goal in isolation. This is what
     * {@link #infoEndpointExposesTheFullBuildCommitId()} above depends on at runtime.
     */
    @Test
    void gitPropertiesIsOnTheClasspathWithTheRequiredKeysAfterANormalBuild() throws IOException {
        Properties git = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("git.properties")) {
            assertThat(in).as("git.properties must be generated onto the classpath by a normal build").isNotNull();
            git.load(in);
        }

        assertThat(git.getProperty("git.commit.id.full"))
                .as("full commit hash")
                .matches("^[0-9a-f]{40}$");
        assertThat(git.getProperty("git.commit.id.abbrev")).as("abbreviated commit hash").isNotBlank();
        assertThat(git.getProperty("git.branch")).as("branch").isNotBlank();
    }

    @Test
    void noCustomMeterCarriesAForbiddenHighCardinalityTagKey() {
        List<String> allowedTagKeys =
                List.of("result", "outcome", "reason", "mode", "status", "failure_kind");
        List<String> forbiddenSubstrings =
                List.of("workspace", "project", "customer", "connection", "event_id", "subscription", "email");

        meterRegistry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("mrrorigin."))
                .forEach(meter -> meter.getId().getTags().forEach(tag -> {
                    String key = tag.getKey().toLowerCase();
                    assertThat(allowedTagKeys).as("tag key on " + meter.getId().getName()).contains(key);
                    forbiddenSubstrings.forEach(forbidden -> assertThat(key)
                            .as("tag key on " + meter.getId().getName() + " must not reference " + forbidden)
                            .doesNotContain(forbidden));
                }));
    }
}
