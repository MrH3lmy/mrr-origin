package com.mrrorigin.reporting;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.mrrorigin.attribution.AttributionApplicationService;

/**
 * Exact 30/60/90-day maturity boundary behavior for #25's retention cohorts, with a fixed clock so
 * "now" can sit precisely at a boundary instant. See {@code docs/adr/0006-retained-mrr-cohort-read-model.md}:
 * an age is available only once {@code now >= periodEnd + age}, and an immature age must render as
 * explicitly unavailable -- never a numeric zero.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(RetentionCohortMaturityBoundaryIntegrationTests.FixedClockConfiguration.class)
class RetentionCohortMaturityBoundaryIntegrationTests {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:18-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    private static final String OWNER = "user-owner";

    // January 2026's period ends 2026-02-01. Its age-30 boundary (periodEnd + 30 days) is exactly
    // 2026-03-03T00:00:00Z -- fixed "now" sits precisely there, so age30 must just barely be
    // available while age60 (periodEnd + 60 = 2026-04-02) and age90 (periodEnd + 90 = 2026-05-02)
    // must not be. February 2026's period ends 2026-03-01: its age-30 boundary (2026-03-31) is still
    // ahead of "now", so a February cohort's age30 must stay immature even though January's has just
    // matured -- proving the boundary is evaluated per period, not as a single global cutoff.
    private static final Instant NOW = Instant.parse("2026-03-03T00:00:00Z");

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcClient db;
    @Autowired private AttributionApplicationService attribution;

    private UUID workspace;
    private UUID project;

    @BeforeEach
    void setUp() {
        db.sql("TRUNCATE workspaces CASCADE").update();
        workspace = UUID.randomUUID();
        project = UUID.randomUUID();
        db.sql("INSERT INTO workspaces (id, name, slug) VALUES (:id, 'w', :slug)")
                .param("id", workspace).param("slug", "w-" + workspace).update();
        db.sql("INSERT INTO workspace_members (workspace_id, subject_id, role) VALUES (:w, :s, 'OWNER')")
                .param("w", workspace).param("s", OWNER).update();
        db.sql("INSERT INTO projects (id, workspace_id, name, domain, public_key) VALUES (:p, :w, 'p', 'one.example', :k)")
                .param("p", project).param("w", workspace).param("k", "pk-" + project).update();
    }

    @Test
    void age30IsAvailableExactlyAtItsBoundaryWhileAge60And90AreStillMaturityPending() throws Exception {
        acquireAndAttribute("cus_january", "USD", 1000, "2026-01-15T00:00:00Z", "google", "spring_sale", "/a");

        mockMvc.perform(cohorts(OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cohorts.length()").value(1))
                // startingMrr/sampleSize are acquisition facts, always populated regardless of age maturity.
                .andExpect(jsonPath("$.cohorts[0].startingMrrMinor").value(1000))
                .andExpect(jsonPath("$.cohorts[0].sampleSize").value(1))
                .andExpect(jsonPath("$.cohorts[0].age30.available").value(true))
                .andExpect(jsonPath("$.cohorts[0].age30.retainedMrrMinor").value(1000))
                .andExpect(jsonPath("$.cohorts[0].age30.retentionPercentage").value(1.0))
                .andExpect(jsonPath("$.cohorts[0].age60.available").value(false))
                .andExpect(jsonPath("$.cohorts[0].age60.unavailableReason").value("MATURITY_PENDING"))
                // Immature means every numeric field is null -- never a fabricated zero.
                .andExpect(jsonPath("$.cohorts[0].age60.retainedMrrMinor").value((Object) null))
                .andExpect(jsonPath("$.cohorts[0].age60.retentionPercentage").value((Object) null))
                .andExpect(jsonPath("$.cohorts[0].age60.nrr").value((Object) null))
                .andExpect(jsonPath("$.cohorts[0].age90.available").value(false))
                .andExpect(jsonPath("$.cohorts[0].age90.unavailableReason").value("MATURITY_PENDING"));
    }

    @Test
    void aLaterAcquisitionPeriodStaysImmatureAtAnAgeAnEarlierPeriodHasAlreadyReached() throws Exception {
        acquireAndAttribute("cus_february", "USD", 700, "2026-02-10T00:00:00Z", "google", "spring_sale", "/a");

        mockMvc.perform(cohorts(OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cohorts.length()").value(1))
                .andExpect(jsonPath("$.cohorts[0].startingMrrMinor").value(700))
                .andExpect(jsonPath("$.cohorts[0].age30.available").value(false))
                .andExpect(jsonPath("$.cohorts[0].age30.unavailableReason").value("MATURITY_PENDING"))
                .andExpect(jsonPath("$.cohorts[0].age30.retainedMrrMinor").value((Object) null));
    }

    @Test
    void summaryRequiresEveryContributingPeriodToBeMatureNotJustSomeOfThem() throws Exception {
        // January is mature at age30 (boundary already reached); February is not. The combined
        // [from, to) summary row must be unavailable as a whole -- an aggregate that silently reflected
        // only the mature period would understate the cohort and quietly change value on a later read
        // as February matures, which is exactly the instability ADR-0006's all-or-nothing rule avoids.
        acquireAndAttribute("cus_january", "USD", 1000, "2026-01-15T00:00:00Z", "google", "spring_sale", "/a");
        acquireAndAttribute("cus_february", "USD", 700, "2026-02-10T00:00:00Z", "google", "spring_sale", "/a");

        mockMvc.perform(summary(OWNER, "2026-01-01T00:00:00Z", "2026-03-01T00:00:00Z", 30))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].startingMrrMinor").value(1700))
                .andExpect(jsonPath("$.rows[0].sampleSize").value(2))
                .andExpect(jsonPath("$.rows[0].cell.available").value(false))
                .andExpect(jsonPath("$.rows[0].cell.unavailableReason").value("MATURITY_PENDING"))
                .andExpect(jsonPath("$.rows[0].cell.retainedMrrMinor").value((Object) null));

        // January alone, requested on its own, is mature.
        mockMvc.perform(summary(OWNER, "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z", 30))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].cell.available").value(true))
                .andExpect(jsonPath("$.rows[0].cell.retainedMrrMinor").value(1000));
    }

    // -- fixtures --

    private void acquireAndAttribute(
            String stripeCustomerId, String currency, long amountMinor, String effectiveAt,
            String source, String campaign, String landingPath) {
        db.sql(
                        """
                        INSERT INTO customer_mrr_movements
                            (id, workspace_id, stripe_customer_id, currency, amount_minor, movement_type,
                             effective_at, calculation_version, source_billing_references)
                        VALUES (:id, :w, :c, :cur, :amt, 'NEW', :at, 'mrr-v1', ARRAY['billing:test'])
                        """)
                .param("id", UUID.randomUUID()).param("w", workspace).param("c", stripeCustomerId)
                .param("cur", currency).param("amt", amountMinor).param("at", OffsetDateTime.parse(effectiveAt))
                .update();

        UUID identity = UUID.randomUUID();
        db.sql("INSERT INTO external_identities (id, workspace_id, project_id, external_user_id) VALUES (:i, :w, :p, :u)")
                .param("i", identity).param("w", workspace).param("p", project).param("u", "user-" + stripeCustomerId)
                .update();
        db.sql(
                        """
                        INSERT INTO billing_customers
                            (id, workspace_id, stripe_customer_id, provider_created_at, source, source_version, source_sequence)
                        VALUES (:id, :w, :c, now(), 'BACKFILL', 1, :c)
                        """)
                .param("id", UUID.randomUUID()).param("w", workspace).param("c", stripeCustomerId).update();
        db.sql(
                        """
                        INSERT INTO stripe_customer_links
                            (id, workspace_id, project_id, external_identity_id, stripe_customer_id,
                             evidence_source, evidence_reference, linked_by_subject_id)
                        VALUES (:id, :w, :p, :i, :c, 'EXPLICIT_API', 'evidence', 'owner')
                        """)
                .param("id", UUID.randomUUID()).param("w", workspace).param("p", project).param("i", identity)
                .param("c", stripeCustomerId)
                .update();

        UUID visitor = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        OffsetDateTime touchAt = OffsetDateTime.parse(effectiveAt).minusDays(1);
        db.sql("INSERT INTO visitors (id, workspace_id, project_id, external_visitor_id, first_seen_at, last_seen_at) VALUES (:v, :w, :p, :e, :at, :at)")
                .param("v", visitor).param("w", workspace).param("p", project).param("e", visitor.toString()).param("at", touchAt)
                .update();
        db.sql("INSERT INTO visitor_aliases (id, workspace_id, project_id, visitor_id, external_identity_id, identified_at) VALUES (:id, :w, :p, :v, :i, now())")
                .param("id", UUID.randomUUID()).param("w", workspace).param("p", project).param("v", visitor).param("i", identity)
                .update();
        db.sql("INSERT INTO tracking_sessions (id, workspace_id, project_id, visitor_id, external_session_id, started_at) VALUES (:s, :w, :p, :v, :e, :at)")
                .param("s", session).param("w", workspace).param("p", project).param("v", visitor).param("e", session.toString()).param("at", touchAt)
                .update();
        db.sql(
                        "INSERT INTO touchpoints (id, workspace_id, project_id, visitor_id, session_id, occurred_at, landing_url, utm_source, utm_campaign, created_at) "
                                + "VALUES (:id, :w, :p, :v, :s, :at, :landing, :src, :campaign, :created)")
                .param("id", UUID.randomUUID()).param("w", workspace).param("p", project).param("v", visitor).param("s", session)
                .param("at", touchAt).param("landing", "https://example.test" + landingPath).param("src", source)
                .param("campaign", campaign).param("created", touchAt.plusSeconds(1))
                .update();

        attribution.recalculate(workspace, project, stripeCustomerId);
    }

    private MockHttpServletRequestBuilder cohorts(String actor) {
        return get("/api/workspaces/{workspaceId}/projects/{projectId}/reporting/retention/cohorts", workspace, project)
                .queryParam("dimension", "SOURCE")
                .with(token(actor));
    }

    private MockHttpServletRequestBuilder summary(String actor, String from, String to, int ageDays) {
        return get("/api/workspaces/{workspaceId}/projects/{projectId}/reporting/retention/summary", workspace, project)
                .queryParam("from", from).queryParam("to", to)
                .queryParam("dimension", "SOURCE")
                .queryParam("ageDays", String.valueOf(ageDays))
                .with(token(actor));
    }

    private RequestPostProcessor token(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject)
                .issuer("http://localhost:8081/realms/mrr-origin")
                .audience(List.of("mrr-origin-api")));
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
