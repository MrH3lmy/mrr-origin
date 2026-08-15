package com.mrrorigin.reporting;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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

import tools.jackson.databind.ObjectMapper;

/**
 * #22's founder-overview read models: period-scoped MRR movement totals, current MRR, source
 * highlights, and the movement drill-down, all derived from already-persisted mrr-v1/attribution-v1
 * data. Project isolation reuses the same "linked, or has attribution history in this project"
 * candidate set already proven by {@code UnattributedRevenueInboxIntegrationTests} and {@code
 * AttributionCoverageIntegrationTests}, so isolation here is checked once rather than duplicating
 * every case.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RevenueOverviewIntegrationTests {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:18-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    private static final String OWNER = "user-owner";
    private static final String FROM = "2026-04-01T00:00:00Z";
    private static final String TO = "2026-05-01T00:00:00Z";

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
    void aggregatesMovementTotalsByTypeAndCurrencyWithinThePeriod() throws Exception {
        movement("cus_new", "USD", 2000, "2026-04-05T00:00:00Z");
        link("cus_new");
        attribution.recalculate(workspace, project, "cus_new");

        // Outside the requested period -- must not be counted.
        movement("cus_outside_period", "USD", 500, "2026-03-01T00:00:00Z");
        link("cus_outside_period");
        attribution.recalculate(workspace, project, "cus_outside_period");

        mockMvc.perform(overview(OWNER, FROM, TO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movementTotals.length()").value(1))
                .andExpect(jsonPath("$.movementTotals[0].currency").value("USD"))
                .andExpect(jsonPath("$.movementTotals[0].movementType").value("NEW"))
                .andExpect(jsonPath("$.movementTotals[0].totalMinor").value(2000))
                .andExpect(jsonPath("$.movementTotals[0].movementCount").value(1));
    }

    @Test
    void currentMrrSumsTheLatestSupportedSnapshotPerCustomerAsOfThePeriodEnd() throws Exception {
        movement("cus_current", "USD", 3000, "2026-04-05T00:00:00Z");
        link("cus_current");
        attribution.recalculate(workspace, project, "cus_current");

        mockMvc.perform(overview(OWNER, FROM, TO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentMrr.length()").value(1))
                .andExpect(jsonPath("$.currentMrr[0].currency").value("USD"))
                .andExpect(jsonPath("$.currentMrr[0].totalMinor").value(3000))
                .andExpect(jsonPath("$.currentMrr[0].customerCount").value(1));
    }

    @Test
    void sourceHighlightsGroupStrongNewMrrBySourceAndBucketTheRestAsUnattributed() throws Exception {
        movement("cus_attributed", "USD", 1000, "2026-04-05T00:00:00Z");
        link("cus_attributed");
        touchpoint("cus_attributed", "2026-03-15T00:00:00Z", "google");
        attribution.recalculate(workspace, project, "cus_attributed");

        movement("cus_unattributed", "USD", 500, "2026-04-06T00:00:00Z");
        link("cus_unattributed");
        attribution.recalculate(workspace, project, "cus_unattributed");

        mockMvc.perform(overview(OWNER, FROM, TO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceHighlights.length()").value(2))
                .andExpect(jsonPath("$.sourceHighlights[0].source").value("google"))
                .andExpect(jsonPath("$.sourceHighlights[0].totalMinor").value(1000))
                .andExpect(jsonPath("$.sourceHighlights[1].source").doesNotExist())
                .andExpect(jsonPath("$.sourceHighlights[1].totalMinor").value(500));
    }

    @Test
    void movementsDrillDownCarriesEvidenceAndSupportsFiltering() throws Exception {
        movement("cus_attributed", "USD", 1000, "2026-04-05T00:00:00Z");
        link("cus_attributed");
        touchpoint("cus_attributed", "2026-03-15T00:00:00Z", "google");
        attribution.recalculate(workspace, project, "cus_attributed");

        mockMvc.perform(movements(OWNER, FROM, TO, null, null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].stripeCustomerId").value("cus_attributed"))
                .andExpect(jsonPath("$.entries[0].confidence").value("STRONG"))
                .andExpect(jsonPath("$.entries[0].firstTouch.source").value("google"));

        mockMvc.perform(movements(OWNER, FROM, TO, "CHURN", null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(0));

        mockMvc.perform(movements(OWNER, FROM, TO, null, "google", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1));

        mockMvc.perform(movements(OWNER, FROM, TO, null, "UNATTRIBUTED", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(0));
    }

    @Test
    void movementsDrillDownCanBeFilteredByCampaignAndLandingPageForTheSourcesComparisonDrilldown() throws Exception {
        // #23: Source -> campaign -> landing page -> customers. A founder drilling from a comparison
        // row must land on exactly the movements that produced it, so campaign/landingPage narrow the
        // same evidence columns the comparison table itself groups by -- no separate calculation.
        movement("cus_full_evidence", "USD", 1000, "2026-04-05T00:00:00Z");
        link("cus_full_evidence");
        touchpoint("cus_full_evidence", "2026-03-15T00:00:00Z", "google", "spring_sale", "https://example.test/landing-a");
        attribution.recalculate(workspace, project, "cus_full_evidence");

        movement("cus_no_campaign", "USD", 400, "2026-04-06T00:00:00Z");
        link("cus_no_campaign");
        touchpoint("cus_no_campaign", "2026-03-16T00:00:00Z", "google", null, "https://example.test/landing-b");
        attribution.recalculate(workspace, project, "cus_no_campaign");

        // Matches source + campaign + landing page exactly.
        mockMvc.perform(movements(OWNER, FROM, TO, null, "google", "spring_sale", "https://example.test/landing-a", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].stripeCustomerId").value("cus_full_evidence"));

        // The "NONE" sentinel selects the "no campaign captured" bucket, mirroring the UNATTRIBUTED
        // sentinel already used for source.
        mockMvc.perform(movements(OWNER, FROM, TO, null, "google", "NONE", null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].stripeCustomerId").value("cus_no_campaign"));

        // campaign without source, or landingPage without campaign, are rejected rather than silently
        // ignored -- an incomplete drill-down filter must never widen to more rows than the founder
        // clicked into.
        mockMvc.perform(movements(OWNER, FROM, TO, null, null, "spring_sale", null, null))
                .andExpect(status().isBadRequest());
        mockMvc.perform(movements(OWNER, FROM, TO, null, "google", null, "https://example.test/landing-a", null))
                .andExpect(status().isBadRequest());
    }

    @Test
    void movementsDrillDownCanBeFilteredByCurrencySoAMultiCurrencyClickReconciles() throws Exception {
        // Regression: clicking a currency-specific summary row (e.g. USD Churn) must not pull in
        // matching movements from a different currency bucket -- the drill-down would then no longer
        // reconcile to the summarized number the founder clicked.
        movement("cus_usd", "USD", 1000, "2026-04-05T00:00:00Z");
        link("cus_usd");
        attribution.recalculate(workspace, project, "cus_usd");

        movement("cus_eur", "EUR", 800, "2026-04-06T00:00:00Z");
        link("cus_eur");
        attribution.recalculate(workspace, project, "cus_eur");

        mockMvc.perform(movements(OWNER, FROM, TO, null, null, "USD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].stripeCustomerId").value("cus_usd"));

        mockMvc.perform(movements(OWNER, FROM, TO, null, null, "EUR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].stripeCustomerId").value("cus_eur"));

        mockMvc.perform(movements(OWNER, FROM, TO, null, null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(2));
    }

    @Test
    void aCustomerRelinkedToADifferentProjectStopsCountingTowardTheFormerProjectsRevenue() throws Exception {
        // Regression: AttributionApplicationService#recalculate re-stamps a customer's *entire*
        // movement history under whichever project currently recalculates it. Without resolving a
        // single "owning project" per customer, a customer who moves from project A to project B
        // would double-count into both projects' overview once B recalculates -- exactly what
        // project isolation is supposed to prevent.
        UUID projectB = UUID.randomUUID();
        db.sql("INSERT INTO projects (id, workspace_id, name, domain, public_key) VALUES (:p, :w, 'pB', 'b.example', :k)")
                .param("p", projectB).param("w", workspace).param("k", "pk-" + projectB).update();

        insertBillingCustomer("cus_mover");
        insertBillingCustomer("cus_displacer");
        insertIdentity("identity-a", project, "user_a");
        insertIdentity("identity-b", projectB, "user_b");

        // cus_mover starts out linked and recalculated in project A.
        mockMvc.perform(repair(OWNER, project, "user_a", "cus_mover")).andExpect(status().isOk());
        movement("cus_mover", "USD", 1000, "2026-04-02T00:00:00Z");
        movement("cus_mover", "USD", 500, "2026-04-10T00:00:00Z", "EXPANSION");
        attribution.recalculate(workspace, project, "cus_mover");

        mockMvc.perform(overview(OWNER, FROM, TO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movementTotals.length()").value(2));

        // cus_mover is freed from project A (its identity is reassigned to a different customer)...
        mockMvc.perform(repair(OWNER, project, "user_a", "cus_displacer")).andExpect(status().isOk());
        // ...then relinked fresh under project B. CustomerLinkRepairService#repair recalculates the
        // target customer as part of the repair, so this re-stamps cus_mover's full movement history
        // (both movements above) under project B.
        mockMvc.perform(repair(OWNER, projectB, "user_b", "cus_mover")).andExpect(status().isOk());

        mockMvc.perform(overview(OWNER, FROM, TO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movementTotals.length()").value(0));

        mockMvc.perform(get(
                                "/api/workspaces/{workspaceId}/projects/{projectId}/reporting/overview",
                                workspace,
                                projectB)
                        .queryParam("from", FROM)
                        .queryParam("to", TO)
                        .with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movementTotals.length()").value(2));
    }

    @Test
    void isolatesOverviewByWorkspaceAndProject() throws Exception {
        movement("cus_in_scope", "USD", 1000, "2026-04-05T00:00:00Z");
        link("cus_in_scope");
        attribution.recalculate(workspace, project, "cus_in_scope");

        UUID otherProject = UUID.randomUUID();
        db.sql("INSERT INTO projects (id, workspace_id, name, domain, public_key) VALUES (:p, :w, 'p2', 'two.example', :k)")
                .param("p", otherProject).param("w", workspace).param("k", "pk-" + otherProject).update();

        mockMvc.perform(get(
                                "/api/workspaces/{workspaceId}/projects/{projectId}/reporting/overview",
                                workspace,
                                otherProject)
                        .queryParam("from", FROM)
                        .queryParam("to", TO)
                        .with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movementTotals.length()").value(0));
    }

    @Test
    void rejectsAFromThatIsNotBeforeTo() throws Exception {
        mockMvc.perform(overview(OWNER, TO, FROM)).andExpect(status().isBadRequest());
    }

    @Test
    void nonMemberCannotReadAnotherWorkspacesOverview() throws Exception {
        mockMvc.perform(overview("user-not-a-member", FROM, TO)).andExpect(status().isNotFound());
    }

    private void movement(String stripeCustomerId, String currency, long amountMinor, String effectiveAt) {
        movement(stripeCustomerId, currency, amountMinor, effectiveAt, "NEW");
        db.sql(
                        """
                        INSERT INTO customer_mrr_snapshots
                            (id, workspace_id, stripe_customer_id, currency, amount_minor, effective_at,
                             calculation_version, supported, source_billing_references)
                        VALUES (:id, :w, :c, :cur, :amt, :at, 'mrr-v1', TRUE, ARRAY['billing:test'])
                        """)
                .param("id", UUID.randomUUID())
                .param("w", workspace)
                .param("c", stripeCustomerId)
                .param("cur", currency)
                .param("amt", amountMinor)
                .param("at", OffsetDateTime.parse(effectiveAt))
                .update();
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

    private void insertBillingCustomer(String stripeCustomerId) {
        db.sql(
                        """
                        INSERT INTO billing_customers
                            (id, workspace_id, stripe_customer_id, provider_created_at, source, source_version, source_sequence)
                        VALUES (:id, :w, :c, now(), 'BACKFILL', 1, :c)
                        """)
                .param("id", UUID.randomUUID()).param("w", workspace).param("c", stripeCustomerId).update();
    }

    private void insertIdentity(String key, UUID projectId, String externalUserId) {
        db.sql("INSERT INTO external_identities (id, workspace_id, project_id, external_user_id) VALUES (:i, :w, :p, :u)")
                .param("i", UUID.nameUUIDFromBytes(key.getBytes()))
                .param("w", workspace)
                .param("p", projectId)
                .param("u", externalUserId)
                .update();
    }

    private MockHttpServletRequestBuilder repair(
            String actor, UUID projectId, String externalUserId, String stripeCustomerId) throws Exception {
        return post("/api/workspaces/{workspaceId}/projects/{projectId}/unattributed-revenue/repairs", workspace, projectId)
                .with(token(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper()
                        .writeValueAsString(Map.of("externalUserId", externalUserId, "stripeCustomerId", stripeCustomerId)));
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

    private void touchpoint(String stripeCustomerId, String at, String utmSource) {
        touchpoint(stripeCustomerId, at, utmSource, null, "https://example.test/");
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

    private MockHttpServletRequestBuilder overview(String actor, String from, String to) {
        return get("/api/workspaces/{workspaceId}/projects/{projectId}/reporting/overview", workspace, project)
                .queryParam("from", from)
                .queryParam("to", to)
                .with(token(actor));
    }

    private MockHttpServletRequestBuilder movements(
            String actor, String from, String to, String movementType, String source, String currency) {
        return movements(actor, from, to, movementType, source, null, null, currency);
    }

    private MockHttpServletRequestBuilder movements(
            String actor,
            String from,
            String to,
            String movementType,
            String source,
            String campaign,
            String landingPage,
            String currency) {
        var request = get("/api/workspaces/{workspaceId}/projects/{projectId}/reporting/movements", workspace, project)
                .queryParam("from", from)
                .queryParam("to", to)
                .with(token(actor));
        if (movementType != null) request = request.queryParam("movementType", movementType);
        if (source != null) request = request.queryParam("source", source);
        if (campaign != null) request = request.queryParam("campaign", campaign);
        if (landingPage != null) request = request.queryParam("landingPage", landingPage);
        if (currency != null) request = request.queryParam("currency", currency);
        return request;
    }

    private RequestPostProcessor token(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject)
                .issuer("http://localhost:8081/realms/mrr-origin")
                .audience(List.of("mrr-origin-api")));
    }
}
