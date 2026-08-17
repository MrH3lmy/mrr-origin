package com.mrrorigin.notification;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.mrrorigin.attribution.AttributionApplicationService;

/**
 * #26's weekly action summary: material-change/newly-appeared/disappeared/insufficient-sample/stable
 * classification, the {@code NONE} vs {@code UNATTRIBUTED} bucket contract, per-currency separation,
 * project-timezone week boundaries, evidence-link reconciliation against #22's movement drilldown,
 * and tenant isolation/authorization. Uses a fixed {@link Clock} (the same fixed-clock pattern
 * {@code RetentionCohortMaturityBoundaryIntegrationTests} uses) so "the last completed week" is
 * deterministic.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(WeeklySummaryIntegrationTests.FixedClockConfiguration.class)
class WeeklySummaryIntegrationTests {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:18-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    private static final String OWNER = "user-owner";

    // A Tuesday. "This week" (in progress) started Monday 2026-03-09, so it can never be complete --
    // the last *completed* week is always exactly the week before it.
    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");
    private static final String CURRENT_FROM = "2026-03-02T00:00:00Z";
    private static final String CURRENT_TO = "2026-03-09T00:00:00Z";
    private static final String PRIOR_FROM = "2026-02-23T00:00:00Z";
    private static final String PRIOR_TO = "2026-03-02T00:00:00Z";

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
    void classifiesMaterialChangeNewlyAppearedDisappearedInsufficientSampleAndStable() throws Exception {
        // google: prior 8x1000 = 8000, current 8x1300 = 10400 -> +30% change, both weeks >= 5 -> MATERIAL_CHANGE.
        acquireEight("goog_prior", "google", "USD", 1000, PRIOR_FROM);
        acquireEight("goog_cur", "google", "USD", 1300, CURRENT_FROM);

        // bing: 0 in prior week, 6 customers this week -> NEWLY_APPEARED.
        acquireN(6, "bing_new", "bing", "USD", 500, CURRENT_FROM);

        // reddit: 6 customers prior week, 0 this week -> DISAPPEARED.
        acquireN(6, "reddit_gone", "reddit", "USD", 500, PRIOR_FROM);

        // twitter: material swing in amount but only 3 customers each week -> INSUFFICIENT_SAMPLE.
        acquireN(3, "twitter_prior", "twitter", "USD", 1000, PRIOR_FROM);
        acquireN(3, "twitter_cur", "twitter", "USD", 2000, CURRENT_FROM);

        // linkedin: essentially flat (well under 25%), both weeks >= 5 -> STABLE.
        acquireN(5, "linkedin_prior", "linkedin", "USD", 1000, PRIOR_FROM);
        acquireN(5, "linkedin_cur", "linkedin", "USD", 1010, CURRENT_FROM);

        MvcResult result = mockMvc.perform(weeklySummary(OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("UTC"))
                .andExpect(jsonPath("$.weekStart").value("2026-03-02T00:00:00Z"))
                .andExpect(jsonPath("$.weekEnd").value("2026-03-09T00:00:00Z"))
                .andExpect(jsonPath("$.priorWeekStart").value("2026-02-23T00:00:00Z"))
                .andExpect(jsonPath("$.priorWeekEnd").value("2026-03-02T00:00:00Z"))
                .andExpect(insight("google", "MATERIAL_CHANGE"))
                .andExpect(jsonPath(insightPath("google") + ".percentageChange").value(0.3))
                .andExpect(jsonPath(insightPath("google") + ".applicableCustomerCount").value(8))
                .andExpect(insight("bing", "NEWLY_APPEARED"))
                .andExpect(jsonPath(insightPath("bing") + ".percentageChange")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath(insightPath("bing") + ".priorAmountMinor").value(0))
                .andExpect(insight("reddit", "DISAPPEARED"))
                .andExpect(jsonPath(insightPath("reddit") + ".percentageChange").value(-1.0))
                .andExpect(jsonPath(insightPath("reddit") + ".currentAmountMinor").value(0))
                .andExpect(insight("twitter", "INSUFFICIENT_SAMPLE"))
                .andExpect(jsonPath(insightPath("twitter") + ".applicableCustomerCount").value(3))
                .andExpect(insight("linkedin", "STABLE"))
                .andReturn();

        // Never described as a statistically detected anomaly, per #26 decision 2.
        String body = result.getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(
                body.toLowerCase(java.util.Locale.ROOT).contains("anomaly"),
                "response must never use anomaly language: " + body);
    }

    @Test
    void distinguishesNoSourceCapturedFromGenuinelyUnattributedWithoutMerging() throws Exception {
        // STRONG confidence, real customer link + touchpoint evidence, but no utm_source captured.
        acquireAndAttribute("cus_no_source", "USD", 1000, CURRENT_FROM, null, null, "/a");
        // No deterministic link at all.
        movement("cus_unattributed", "USD", 1000, CURRENT_FROM, "NEW");
        link("cus_unattributed");
        attribution.recalculate(workspace, project, "cus_unattributed");

        mockMvc.perform(weeklySummary(OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                                "$.currencySections[?(@.currency=='USD')].insights"
                                        + "[?(@.dimension=='SOURCE' && @.dimensionBucket=='NONE')].currentAmountMinor")
                        .value(1000))
                .andExpect(jsonPath(
                                "$.currencySections[?(@.currency=='USD')].insights"
                                        + "[?(@.dimension=='SOURCE' && @.dimensionBucket=='UNATTRIBUTED')].currentAmountMinor")
                        .value(1000))
                // Two genuinely distinct buckets, never coalesced into one "unknown" row.
                .andExpect(jsonPath(
                                "$.currencySections[?(@.currency=='USD')].insights"
                                        + "[?(@.dimension=='SOURCE' && @.dimensionValue==null)].dimensionBucket")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("NONE", "UNATTRIBUTED")));
    }

    @Test
    void keepsCurrenciesIndependentWithNoBlending() throws Exception {
        acquireN(6, "google_usd", "google", "USD", 1000, CURRENT_FROM);
        acquireN(6, "google_eur", "google", "EUR", 2000, CURRENT_FROM);

        mockMvc.perform(weeklySummary(OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currencySections.length()").value(2))
                .andExpect(jsonPath("$.currencySections[0].currency").value("EUR"))
                .andExpect(jsonPath("$.currencySections[1].currency").value("USD"))
                .andExpect(jsonPath(insightPath("google") + ".currentAmountMinor", org.hamcrest.Matchers.not(12000)));
    }

    @Test
    void evidenceLinksReconcileExactlyWithTheMovementsDrilldown() throws Exception {
        acquireEight("goog_prior", "google", "USD", 1000, PRIOR_FROM);
        acquireEight("goog_cur", "google", "USD", 1300, CURRENT_FROM);

        // The exact path EvidenceLink.path() builds for this insight's current-week filters -- proves
        // the JSON response carries this precise, decodable filter set rather than an opaque summary.
        String expectedLink = "/app/" + workspace + "/projects/" + project + "/sources?"
                + "from=" + enc(OffsetDateTime.parse(CURRENT_FROM).toString())
                + "&to=" + enc(OffsetDateTime.parse(CURRENT_TO).toString())
                + "&movementType=NEW&currency=USD&source=google";

        mockMvc.perform(weeklySummary(OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath(insightPath("google") + ".currentEvidenceLink").value(expectedLink));

        // Replaying that exact filter set against #22's own movement drilldown must reconcile to the
        // insight's currentAmountMinor/currentCustomerCount -- not a looser or different query.
        mockMvc.perform(get("/api/workspaces/{workspaceId}/projects/{projectId}/reporting/movements", workspace, project)
                        .queryParam("from", CURRENT_FROM)
                        .queryParam("to", CURRENT_TO)
                        .queryParam("movementType", "NEW")
                        .queryParam("currency", "USD")
                        .queryParam("source", "google")
                        .with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(8))
                .andExpect(jsonPath("$.entries[0].amountMinor").value(1300));
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void honorsAnExplicitNonUtcProjectTimezoneForWeekBoundaries() throws Exception {
        // Fixed +05:30 offset (no DST) so the boundary math is unambiguous.
        db.sql("UPDATE projects SET timezone = 'Asia/Kolkata' WHERE id = :p").param("p", project).update();

        mockMvc.perform(weeklySummary(OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("Asia/Kolkata"))
                // Monday 00:00 IST = the preceding Sunday 18:30 UTC.
                .andExpect(jsonPath("$.weekStart").value("2026-03-01T18:30:00Z"))
                .andExpect(jsonPath("$.weekEnd").value("2026-03-08T18:30:00Z"));
    }

    @Test
    void rejectsAWeekThatHasNotCompletedInTheProjectTimezone() throws Exception {
        mockMvc.perform(get(
                                "/api/workspaces/{workspaceId}/projects/{projectId}/reporting/weekly-summary",
                                workspace, project)
                        .queryParam("weekStart", "2026-03-09")
                        .with(token(OWNER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("weekStart must identify a completed week"));
    }

    @Test
    void isTenantIsolatedAndRequiresMembership() throws Exception {
        acquireEight("goog_cur", "google", "USD", 1300, CURRENT_FROM);

        UUID otherWorkspace = UUID.randomUUID();
        UUID otherProject = UUID.randomUUID();
        db.sql("INSERT INTO workspaces (id, name, slug) VALUES (:id, 'w2', :slug)")
                .param("id", otherWorkspace).param("slug", "w2-" + otherWorkspace).update();
        db.sql("INSERT INTO workspace_members (workspace_id, subject_id, role) VALUES (:w, :s, 'OWNER')")
                .param("w", otherWorkspace).param("s", "other-owner").update();
        db.sql("INSERT INTO projects (id, workspace_id, name, domain, public_key) VALUES (:p, :w, 'p2', 'two.example', :k)")
                .param("p", otherProject).param("w", otherWorkspace).param("k", "pk-" + otherProject).update();

        // A member of a different workspace gets 404, not this workspace's data.
        mockMvc.perform(get(
                                "/api/workspaces/{workspaceId}/projects/{projectId}/reporting/weekly-summary",
                                workspace, project)
                        .with(token("other-owner")))
                .andExpect(status().isNotFound());

        // The other workspace's own summary must never see this workspace's google insight.
        mockMvc.perform(get(
                                "/api/workspaces/{workspaceId}/projects/{projectId}/reporting/weekly-summary",
                                otherWorkspace, otherProject)
                        .with(token("other-owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currencySections.length()").value(0));
    }

    @Test
    void textAndHtmlRenderingOmitStableInsightsButKeepThemInJsonAndNeverUseAnomalyLanguage() throws Exception {
        acquireN(5, "linkedin_prior", "linkedin", "USD", 1000, PRIOR_FROM);
        acquireN(5, "linkedin_cur", "linkedin", "USD", 1010, CURRENT_FROM);
        acquireN(6, "bing_new", "bing", "USD", 500, CURRENT_FROM);

        String text = mockMvc.perform(get(
                                "/api/workspaces/{workspaceId}/projects/{projectId}/reporting/weekly-summary.txt",
                                workspace, project)
                        .with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andReturn()
                .getResponse()
                .getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("bing"));
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("[this week: /app/"));
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("[prior week: /app/"));
        org.junit.jupiter.api.Assertions.assertFalse(text.contains("linkedin"));
        org.junit.jupiter.api.Assertions.assertFalse(text.toLowerCase(java.util.Locale.ROOT).contains("anomaly"));
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("stable"));

        String html = mockMvc.perform(get(
                                "/api/workspaces/{workspaceId}/projects/{projectId}/reporting/weekly-summary.html",
                                workspace, project)
                        .with(token(OWNER)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("bing"));
        org.junit.jupiter.api.Assertions.assertFalse(html.contains(">linkedin<"));
    }

    // -- fixtures --

    private void acquireEight(String prefix, String source, String currency, long amountMinor, String effectiveAt) {
        acquireN(8, prefix, source, currency, amountMinor, effectiveAt);
    }

    private void acquireN(int n, String prefix, String source, String currency, long amountMinor, String effectiveAt) {
        for (int i = 0; i < n; i++) {
            acquireAndAttribute(prefix + "_" + i, currency, amountMinor, effectiveAt, source, "campaign", "/a");
        }
    }

    private void acquireAndAttribute(
            String stripeCustomerId, String currency, long amountMinor, String effectiveAt,
            String source, String campaign, String landingPath) {
        movement(stripeCustomerId, currency, amountMinor, effectiveAt, "NEW");
        link(stripeCustomerId);
        touchpoint(stripeCustomerId, OffsetDateTime.parse(effectiveAt).minusHours(1).toString(), source, campaign,
                "https://example.test" + landingPath);
        attribution.recalculate(workspace, project, stripeCustomerId);
    }

    private void movement(String stripeCustomerId, String currency, long amountMinor, String effectiveAt, String movementType) {
        db.sql(
                        """
                        INSERT INTO customer_mrr_movements
                            (id, workspace_id, stripe_customer_id, currency, amount_minor, movement_type,
                             effective_at, calculation_version, source_billing_references)
                        VALUES (:id, :w, :c, :cur, :amt, :type, :at, 'mrr-v1', ARRAY['billing:test'])
                        """)
                .param("id", UUID.randomUUID())
                .param("w", workspace)
                .param("c", stripeCustomerId)
                .param("cur", currency)
                .param("amt", amountMinor)
                .param("type", movementType)
                .param("at", OffsetDateTime.parse(effectiveAt))
                .update();
    }

    private void link(String stripeCustomerId) {
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
    }

    private void touchpoint(String stripeCustomerId, String at, String utmSource, String utmCampaign, String landingUrl) {
        UUID identity = db.sql(
                        "SELECT external_identity_id FROM stripe_customer_links WHERE workspace_id = :w AND stripe_customer_id = :c")
                .param("w", workspace).param("c", stripeCustomerId).query(UUID.class).single();
        UUID visitor = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        OffsetDateTime time = OffsetDateTime.parse(at);
        db.sql("INSERT INTO visitors (id, workspace_id, project_id, external_visitor_id, first_seen_at, last_seen_at) VALUES (:v, :w, :p, :e, :at, :at)")
                .param("v", visitor).param("w", workspace).param("p", project).param("e", visitor.toString()).param("at", time)
                .update();
        db.sql("INSERT INTO visitor_aliases (id, workspace_id, project_id, visitor_id, external_identity_id, identified_at) VALUES (:id, :w, :p, :v, :i, now())")
                .param("id", UUID.randomUUID()).param("w", workspace).param("p", project).param("v", visitor).param("i", identity)
                .update();
        db.sql("INSERT INTO tracking_sessions (id, workspace_id, project_id, visitor_id, external_session_id, started_at) VALUES (:s, :w, :p, :v, :e, :at)")
                .param("s", session).param("w", workspace).param("p", project).param("v", visitor).param("e", session.toString()).param("at", time)
                .update();
        db.sql(
                        "INSERT INTO touchpoints (id, workspace_id, project_id, visitor_id, session_id, occurred_at, landing_url, utm_source, utm_campaign, created_at) "
                                + "VALUES (:id, :w, :p, :v, :s, :at, :landing, :src, :campaign, :created)")
                .param("id", UUID.randomUUID()).param("w", workspace).param("p", project).param("v", visitor).param("s", session)
                .param("at", time).param("landing", landingUrl).param("src", utmSource).param("campaign", utmCampaign)
                .param("created", time.plusSeconds(1))
                .update();
    }

    private MockHttpServletRequestBuilder weeklySummary(String actor) {
        return get("/api/workspaces/{workspaceId}/projects/{projectId}/reporting/weekly-summary", workspace, project)
                .with(token(actor));
    }

    private static String insightPath(String source) {
        return "$.currencySections[?(@.currency=='USD')].insights"
                + "[?(@.dimension=='SOURCE' && @.dimensionValue=='" + source + "' && @.movementType=='NEW')]";
    }

    private static org.springframework.test.web.servlet.ResultMatcher insight(String source, String expectedStatus) {
        return jsonPath(insightPath(source) + ".status").value(expectedStatus);
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
