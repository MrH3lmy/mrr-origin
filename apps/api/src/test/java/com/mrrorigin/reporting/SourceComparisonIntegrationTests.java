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
 * #23's source/campaign/landing-page comparison: grouping correctness, tenant isolation, project
 * relinking, multi-currency separation, stable sort order, and drill-down reconciliation against
 * the {@code /movements} endpoint #22 already ships. Reuses the same fixture-building helpers and
 * {@link RevenueMovementsService#OWNER_CTE} project-ownership mechanism {@code
 * RevenueOverviewIntegrationTests} already proves for the sibling read models in this controller.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SourceComparisonIntegrationTests {
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
    void groupsStrongNewAndChurnedMrrBySourceAndBucketsTheRestAsUnattributed() throws Exception {
        acquireAndAttribute("cus_google", "USD", 1000, "2026-04-05T00:00:00Z", "google", "spring_sale", "/a");
        churn("cus_google", "USD", 1000, "2026-04-20T00:00:00Z");

        acquireAndAttribute("cus_bing", "USD", 500, "2026-04-06T00:00:00Z", "bing", "spring_sale", "/a");

        movement("cus_unattributed", "USD", 300, "2026-04-07T00:00:00Z", "NEW");
        link("cus_unattributed");
        attribution.recalculate(workspace, project, "cus_unattributed");

        mockMvc.perform(comparison(OWNER, "SOURCE", null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.dimensionValue=='google' && @.movementType=='NEW')].totalMinor").value(1000))
                .andExpect(jsonPath("$.rows[?(@.dimensionValue=='google' && @.movementType=='NEW')].attributed").value(true))
                .andExpect(jsonPath("$.rows[?(@.dimensionValue=='google' && @.movementType=='CHURN')].totalMinor").value(1000))
                .andExpect(jsonPath("$.rows[?(@.dimensionValue=='bing' && @.movementType=='NEW')].totalMinor").value(500))
                .andExpect(jsonPath("$.rows[?(@.dimensionValue==null && @.movementType=='NEW')].totalMinor").value(300))
                .andExpect(jsonPath("$.rows[?(@.dimensionValue==null && @.movementType=='NEW')].attributed").value(false))
                .andExpect(jsonPath("$.unavailableMetrics.length()").value(2))
                .andExpect(jsonPath("$.unavailableMetrics[?(@.metric=='RETAINED_MRR')]").exists())
                .andExpect(jsonPath("$.unavailableMetrics[?(@.metric=='NRR')]").exists());
    }

    @Test
    void distinguishesAttributedMovementsWithNoSourceCapturedFromGenuinelyUnattributedMovements() throws Exception {
        // Regression: a movement can be STRONG (real customer-link + touchpoint evidence) yet have no
        // utm_source at all (e.g. a direct visit). That row must never merge with, or be indistinguishable
        // from, a movement with no acquisition evidence whatsoever -- both previously grouped into the
        // same dimensionValue==null bucket. attributed now tells them apart.
        acquireAndAttribute("cus_no_source_captured", "USD", 600, "2026-04-05T00:00:00Z", null, null, "/direct");

        movement("cus_unattributed_2", "USD", 300, "2026-04-06T00:00:00Z", "NEW");
        link("cus_unattributed_2");
        attribution.recalculate(workspace, project, "cus_unattributed_2");

        mockMvc.perform(comparison(OWNER, "SOURCE", null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.attributed==true && @.movementType=='NEW')].totalMinor").value(600))
                .andExpect(jsonPath("$.rows[?(@.attributed==true && @.movementType=='NEW')].customerCount").value(1))
                .andExpect(jsonPath("$.rows[?(@.attributed==false && @.movementType=='NEW')].totalMinor").value(300))
                .andExpect(jsonPath("$.rows[?(@.attributed==false && @.movementType=='NEW')].customerCount").value(1))
                // Both rows have dimensionValue==null, but there are two of them, not one merged row.
                .andExpect(jsonPath("$.rows.length()").value(2));
    }

    @Test
    void notRecalculatedAndExplicitlyUnattributedMovementsShareOneReconciledBucket() throws Exception {
        // A linked movement can be visible to reporting before attribution recalculation creates its
        // result row. SQL NULL and explicit non-STRONG confidence are the same Unattributed product
        // bucket, so they must aggregate into one comparison row and one matching evidence result.
        movement("cus_not_recalculated", "USD", 500, "2026-04-05T00:00:00Z", "NEW");
        link("cus_not_recalculated");

        movement("cus_explicitly_unattributed", "USD", 300, "2026-04-06T00:00:00Z", "NEW");
        link("cus_explicitly_unattributed");
        attribution.recalculate(workspace, project, "cus_explicitly_unattributed");

        mockMvc.perform(comparison(OWNER, "SOURCE", null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                                "$.rows[?(@.dimensionValue==null && @.attributed==false && @.movementType=='NEW')].length()")
                        .value(1))
                .andExpect(jsonPath(
                                "$.rows[?(@.dimensionValue==null && @.attributed==false && @.movementType=='NEW')].totalMinor")
                        .value(800))
                .andExpect(jsonPath(
                                "$.rows[?(@.dimensionValue==null && @.attributed==false && @.movementType=='NEW')].customerCount")
                        .value(2));

        mockMvc.perform(get(
                                "/api/workspaces/{workspaceId}/projects/{projectId}/reporting/movements",
                                workspace,
                                project)
                        .queryParam("from", FROM)
                        .queryParam("to", TO)
                        .queryParam("movementType", "NEW")
                        .queryParam("sourceUnattributed", "true")
                        .with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].stripeCustomerId").value("cus_not_recalculated"))
                .andExpect(jsonPath("$.entries[0].amountMinor").value(500))
                .andExpect(jsonPath("$.entries[1].stripeCustomerId").value("cus_explicitly_unattributed"))
                .andExpect(jsonPath("$.entries[1].amountMinor").value(300));
    }

    @Test
    void keepsCurrenciesSeparateRatherThanSummingThem() throws Exception {
        acquireAndAttribute("cus_usd", "USD", 1000, "2026-04-05T00:00:00Z", "google", "spring_sale", "/a");
        acquireAndAttribute("cus_eur", "EUR", 800, "2026-04-06T00:00:00Z", "google", "spring_sale", "/a");

        mockMvc.perform(comparison(OWNER, "SOURCE", null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.currency=='USD' && @.dimensionValue=='google' && @.movementType=='NEW')].totalMinor").value(1000))
                .andExpect(jsonPath("$.rows[?(@.currency=='EUR' && @.dimensionValue=='google' && @.movementType=='NEW')].totalMinor").value(800));
    }

    @Test
    void groupsCampaignsWithinASourceAndRequiresASourceToDoSo() throws Exception {
        acquireAndAttribute("cus_spring", "USD", 1000, "2026-04-05T00:00:00Z", "google", "spring_sale", "/a");
        acquireAndAttribute("cus_summer", "USD", 700, "2026-04-06T00:00:00Z", "google", "summer_sale", "/b");
        acquireAndAttribute("cus_no_campaign", "USD", 200, "2026-04-07T00:00:00Z", "google", null, "/c");
        // A different source's campaign must never leak into the "google" comparison.
        acquireAndAttribute("cus_bing_campaign", "USD", 900, "2026-04-08T00:00:00Z", "bing", "spring_sale", "/a");

        mockMvc.perform(comparison(OWNER, "CAMPAIGN", "google", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.dimensionValue=='spring_sale')].totalMinor").value(1000))
                .andExpect(jsonPath("$.rows[?(@.dimensionValue=='summer_sale')].totalMinor").value(700))
                .andExpect(jsonPath("$.rows[?(@.dimensionValue==null)].totalMinor").value(200))
                .andExpect(jsonPath("$.rows.length()").value(3));

        mockMvc.perform(comparison(OWNER, "CAMPAIGN", null, null)).andExpect(status().isBadRequest());
    }

    @Test
    void groupsLandingPagesWithinASourceAndCampaignIncludingTheNoCampaignBucket() throws Exception {
        acquireAndAttribute("cus_a", "USD", 1000, "2026-04-05T00:00:00Z", "google", "spring_sale", "/landing-a");
        acquireAndAttribute("cus_b", "USD", 600, "2026-04-06T00:00:00Z", "google", "spring_sale", "/landing-b");
        acquireAndAttribute("cus_no_campaign", "USD", 400, "2026-04-07T00:00:00Z", "google", null, "/landing-c");

        mockMvc.perform(comparison(OWNER, "LANDING_PAGE", "google", "spring_sale"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.dimensionValue=='https://example.test/landing-a')].totalMinor").value(1000))
                .andExpect(jsonPath("$.rows[?(@.dimensionValue=='https://example.test/landing-b')].totalMinor").value(600))
                .andExpect(jsonPath("$.rows.length()").value(2));

        mockMvc.perform(comparisonWithMissingCampaign(OWNER, "google"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.dimensionValue=='https://example.test/landing-c')].totalMinor").value(400))
                .andExpect(jsonPath("$.rows.length()").value(1));

        mockMvc.perform(comparison(OWNER, "LANDING_PAGE", "google", null)).andExpect(status().isBadRequest());
        mockMvc.perform(comparison(OWNER, "LANDING_PAGE", null, "spring_sale")).andExpect(status().isBadRequest());
    }

    @Test
    void aRealCampaignLiterallyNamedNoneIsDistinctFromTheNoCampaignBucket() throws Exception {
        // Regression: the LANDING_PAGE comparison used to encode "no campaign captured" as the magic
        // string "NONE" inside the campaign parameter itself. utm_campaign is a founder-controlled
        // value, so a real campaign named "NONE" would have collided with that sentinel and silently
        // shown the wrong landing pages. campaignMissing is now a separate boolean.
        acquireAndAttribute("cus_real_none", "USD", 900, "2026-04-05T00:00:00Z", "google", "NONE", "/landing-real");
        acquireAndAttribute("cus_missing_campaign", "USD", 500, "2026-04-06T00:00:00Z", "google", null, "/landing-missing");

        mockMvc.perform(comparison(OWNER, "LANDING_PAGE", "google", "NONE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.dimensionValue=='https://example.test/landing-real')].totalMinor").value(900))
                .andExpect(jsonPath("$.rows.length()").value(1));

        mockMvc.perform(comparisonWithMissingCampaign(OWNER, "google"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.dimensionValue=='https://example.test/landing-missing')].totalMinor").value(500))
                .andExpect(jsonPath("$.rows.length()").value(1));

        // campaign and campaignMissing together is a contradiction, not silently resolved one way.
        mockMvc.perform(get("/api/workspaces/{workspaceId}/projects/{projectId}/reporting/comparison", workspace, project)
                        .queryParam("from", FROM).queryParam("to", TO).queryParam("dimension", "LANDING_PAGE")
                        .queryParam("source", "google").queryParam("campaign", "spring_sale")
                        .queryParam("campaignMissing", "true").with(token(OWNER)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void excludesMovementsOutsideTheRequestedPeriod() throws Exception {
        acquireAndAttribute("cus_in_period", "USD", 1000, "2026-04-05T00:00:00Z", "google", "spring_sale", "/a");
        acquireAndAttribute("cus_outside_period", "USD", 500, "2026-03-01T00:00:00Z", "google", "spring_sale", "/a");

        mockMvc.perform(comparison(OWNER, "SOURCE", null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.dimensionValue=='google' && @.movementType=='NEW')].totalMinor").value(1000));
    }

    @Test
    void sortsDeterministicallyByTotalDescendingWithAStableTiebreaker() throws Exception {
        // Two sources with equal New MRR: without a tiebreaker, GROUP BY/ORDER BY total_minor DESC has
        // no guaranteed order for ties, so a founder re-sorting or re-loading the page could see rows
        // silently swap position. The dimension-value tiebreaker makes the order reproducible.
        acquireAndAttribute("cus_bravo", "USD", 500, "2026-04-05T00:00:00Z", "bravo_source", "c", "/a");
        acquireAndAttribute("cus_alpha", "USD", 500, "2026-04-06T00:00:00Z", "alpha_source", "c", "/a");

        String first = mockMvc.perform(comparison(OWNER, "SOURCE", null, null))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(comparison(OWNER, "SOURCE", null, null))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(first).isEqualTo(second);
        mockMvc.perform(comparison(OWNER, "SOURCE", null, null))
                .andExpect(jsonPath("$.rows[0].dimensionValue").value("alpha_source"))
                .andExpect(jsonPath("$.rows[1].dimensionValue").value("bravo_source"));
    }

    @Test
    void aSourceComparisonRowReconcilesExactlyWithItsMovementDrilldown() throws Exception {
        acquireAndAttribute("cus_1", "USD", 1000, "2026-04-05T00:00:00Z", "google", "spring_sale", "/landing-a");
        acquireAndAttribute("cus_2", "USD", 600, "2026-04-06T00:00:00Z", "google", "spring_sale", "/landing-a");
        acquireAndAttribute("cus_3", "USD", 400, "2026-04-07T00:00:00Z", "google", "summer_sale", "/landing-b");

        long sourceTotal = 1000 + 600 + 400;
        mockMvc.perform(comparison(OWNER, "SOURCE", null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.dimensionValue=='google' && @.movementType=='NEW')].totalMinor").value((int) sourceTotal))
                .andExpect(jsonPath("$.rows[?(@.dimensionValue=='google' && @.movementType=='NEW')].customerCount").value(3));

        mockMvc.perform(movements(OWNER, "google", null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(3));

        long campaignTotal = 1000 + 600;
        mockMvc.perform(comparison(OWNER, "CAMPAIGN", "google", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.dimensionValue=='spring_sale')].totalMinor").value((int) campaignTotal))
                .andExpect(jsonPath("$.rows[?(@.dimensionValue=='spring_sale')].customerCount").value(2));

        mockMvc.perform(movements(OWNER, "google", "spring_sale", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].amountMinor").value(1000))
                .andExpect(jsonPath("$.entries[1].amountMinor").value(600));

        mockMvc.perform(comparison(OWNER, "LANDING_PAGE", "google", "spring_sale"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.dimensionValue=='https://example.test/landing-a')].totalMinor").value(1600))
                .andExpect(jsonPath("$.rows[?(@.dimensionValue=='https://example.test/landing-a')].customerCount").value(2));

        mockMvc.perform(movements(OWNER, "google", "spring_sale", "https://example.test/landing-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(2));
    }

    @Test
    void isolatesComparisonByWorkspaceAndProject() throws Exception {
        acquireAndAttribute("cus_in_scope", "USD", 1000, "2026-04-05T00:00:00Z", "google", "spring_sale", "/a");

        UUID otherProject = UUID.randomUUID();
        db.sql("INSERT INTO projects (id, workspace_id, name, domain, public_key) VALUES (:p, :w, 'p2', 'two.example', :k)")
                .param("p", otherProject).param("w", workspace).param("k", "pk-" + otherProject).update();

        mockMvc.perform(get(
                                "/api/workspaces/{workspaceId}/projects/{projectId}/reporting/comparison",
                                workspace,
                                otherProject)
                        .queryParam("from", FROM)
                        .queryParam("to", TO)
                        .queryParam("dimension", "SOURCE")
                        .with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(0));

        mockMvc.perform(comparison("user-not-a-member", "SOURCE", null, null)).andExpect(status().isNotFound());
    }

    @Test
    void aCustomerRelinkedToADifferentProjectStopsCountingTowardTheFormerProjectsComparison() throws Exception {
        // Regression guard for the PR #54 cross-project relinking bug this comparison read model must
        // not reintroduce: AttributionApplicationService#recalculate re-stamps a customer's entire
        // movement history under whichever project last recalculated it.
        UUID projectB = UUID.randomUUID();
        db.sql("INSERT INTO projects (id, workspace_id, name, domain, public_key) VALUES (:p, :w, 'pB', 'b.example', :k)")
                .param("p", projectB).param("w", workspace).param("k", "pk-" + projectB).update();

        insertBillingCustomer("cus_mover");
        insertBillingCustomer("cus_displacer");
        insertIdentity("identity-a", project, "user_a");
        insertIdentity("identity-b", projectB, "user_b");

        // No touchpoint is attached here on purpose: a relink to a *different* identity (as
        // CustomerLinkRepairService performs below) legitimately loses the old identity's touchpoint
        // history, since acquisition evidence belongs to the visitor/identity, not the customer. That
        // is a correct attribution-continuity outcome, not what this test is about. This test is about
        // the OWNER_CTE project-ownership resolution: does the movement stop counting for project A's
        // comparison and start counting for project B's once recalculation re-stamps it -- exactly the
        // PR #54 regression. The Unattributed bucket (dimensionValue=null) is sufficient to prove that.
        mockMvc.perform(repair(OWNER, project, "user_a", "cus_mover")).andExpect(status().isOk());
        movement("cus_mover", "USD", 1000, "2026-04-02T00:00:00Z", "NEW");
        attribution.recalculate(workspace, project, "cus_mover");

        mockMvc.perform(comparison(OWNER, "SOURCE", null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.dimensionValue==null && @.movementType=='NEW')].totalMinor").value(1000));

        mockMvc.perform(repair(OWNER, project, "user_a", "cus_displacer")).andExpect(status().isOk());
        mockMvc.perform(repair(OWNER, projectB, "user_b", "cus_mover")).andExpect(status().isOk());

        mockMvc.perform(comparison(OWNER, "SOURCE", null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(0));

        mockMvc.perform(get(
                                "/api/workspaces/{workspaceId}/projects/{projectId}/reporting/comparison",
                                workspace,
                                projectB)
                        .queryParam("from", FROM)
                        .queryParam("to", TO)
                        .queryParam("dimension", "SOURCE")
                        .with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.dimensionValue==null && @.movementType=='NEW')].totalMinor").value(1000));
    }

    // -- fixtures --

    private void acquireAndAttribute(
            String stripeCustomerId, String currency, long amountMinor, String effectiveAt,
            String source, String campaign, String landingPath) {
        movement(stripeCustomerId, currency, amountMinor, effectiveAt, "NEW");
        link(stripeCustomerId);
        touchpoint(stripeCustomerId, OffsetDateTime.parse(effectiveAt).minusDays(1).toString(), source, campaign,
                "https://example.test" + landingPath);
        attribution.recalculate(workspace, project, stripeCustomerId);
    }

    private void churn(String stripeCustomerId, String currency, long amountMinor, String effectiveAt) {
        movement(stripeCustomerId, currency, amountMinor, effectiveAt, "CHURN");
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

    private MockHttpServletRequestBuilder comparison(String actor, String dimension, String source, String campaign) {
        var request = get("/api/workspaces/{workspaceId}/projects/{projectId}/reporting/comparison", workspace, project)
                .queryParam("from", FROM)
                .queryParam("to", TO)
                .queryParam("dimension", dimension)
                .with(token(actor));
        if (source != null) request = request.queryParam("source", source);
        if (campaign != null) request = request.queryParam("campaign", campaign);
        return request;
    }

    private MockHttpServletRequestBuilder comparisonWithMissingCampaign(String actor, String source) {
        return get("/api/workspaces/{workspaceId}/projects/{projectId}/reporting/comparison", workspace, project)
                .queryParam("from", FROM)
                .queryParam("to", TO)
                .queryParam("dimension", "LANDING_PAGE")
                .queryParam("source", source)
                .queryParam("campaignMissing", "true")
                .with(token(actor));
    }

    private MockHttpServletRequestBuilder movements(String actor, String source, String campaign, String landingPage) {
        var request = get("/api/workspaces/{workspaceId}/projects/{projectId}/reporting/movements", workspace, project)
                .queryParam("from", FROM)
                .queryParam("to", TO)
                .queryParam("movementType", "NEW")
                .with(token(actor));
        if (source != null) request = request.queryParam("source", source);
        if (campaign != null) request = request.queryParam("campaign", campaign);
        if (landingPage != null) request = request.queryParam("landingPage", landingPage);
        return request;
    }

    private RequestPostProcessor token(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject)
                .issuer("http://localhost:8081/realms/mrr-origin")
                .audience(List.of("mrr-origin-api")));
    }
}
