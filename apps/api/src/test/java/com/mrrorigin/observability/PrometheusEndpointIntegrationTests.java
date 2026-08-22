package com.mrrorigin.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

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
