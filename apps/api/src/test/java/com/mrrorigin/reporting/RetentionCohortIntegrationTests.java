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
 * #25's retained-MRR cohort read model: grouping, the ADR-0004 retained-MRR/NRR arithmetic
 * (including the documented reactivation-vs-NRR divergence), multi-currency separation, late-arriving
 * and recalculated attribution, literal sentinel-like source/campaign values, tenant isolation,
 * deterministic ordering, and cross-endpoint reconciliation with #22's movement drill-down and #23's
 * comparison. Every fixture in this file uses 2026-01/02-dated acquisitions, which are long past every
 * 30/60/90-day maturity boundary by the time this test runs against the real system clock -- exact
 * boundary behavior (immature vs. just-matured) is covered separately in {@link
 * RetentionCohortMaturityBoundaryIntegrationTests}, which fixes the clock instead.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RetentionCohortIntegrationTests {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:18-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    private static final String OWNER = "user-owner";

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
    void reconcilesRetainedMrrExpansionContractionChurnAndReactivationAtEachAgeIncludingTheDocumentedNrrDivergence() throws Exception {
        // A single customer's full lifecycle, timed so each event lands in a different age's window:
        // +10d expansion (inside every window), +40d contraction (age60/90 only), +70d churn (age90
        // only), +85d reactivation (age90 only). No event lands exactly on a cutoff, so retainedMrr
        // and the starting+expansion-contraction-churn+reactivation identity agree exactly at every age.
        acquireAndAttribute("cus_lifecycle", "USD", 1000, "2026-01-10T00:00:00Z", "google", "spring_sale", "/a");
        movementAt("cus_lifecycle", "USD", 200, "2026-01-20T00:00:00Z", "EXPANSION");
        movementAt("cus_lifecycle", "USD", 300, "2026-02-19T00:00:00Z", "CONTRACTION");
        movementAt("cus_lifecycle", "USD", 900, "2026-03-21T00:00:00Z", "CHURN");
        movementAt("cus_lifecycle", "USD", 400, "2026-04-05T00:00:00Z", "REACTIVATION");
        attribution.recalculate(workspace, project, "cus_lifecycle");

        mockMvc.perform(cohorts(OWNER, "SOURCE", null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cohorts.length()").value(1))
                .andExpect(jsonPath("$.cohorts[0].dimensionValue").value("google"))
                .andExpect(jsonPath("$.cohorts[0].startingMrrMinor").value(1000))
                .andExpect(jsonPath("$.cohorts[0].sampleSize").value(1))
                // age30: only the expansion has happened yet.
                .andExpect(jsonPath("$.cohorts[0].age30.available").value(true))
                .andExpect(jsonPath("$.cohorts[0].age30.retainedMrrMinor").value(1200))
                .andExpect(jsonPath("$.cohorts[0].age30.expansionMrrMinor").value(200))
                .andExpect(jsonPath("$.cohorts[0].age30.contractionMrrMinor").value(0))
                .andExpect(jsonPath("$.cohorts[0].age30.churnMrrMinor").value(0))
                .andExpect(jsonPath("$.cohorts[0].age30.reactivationMrrMinor").value(0))
                .andExpect(jsonPath("$.cohorts[0].age30.retentionPercentage").value(1.2))
                .andExpect(jsonPath("$.cohorts[0].age30.nrr").value(1.2))
                // age60: expansion and contraction have happened.
                .andExpect(jsonPath("$.cohorts[0].age60.retainedMrrMinor").value(900))
                .andExpect(jsonPath("$.cohorts[0].age60.expansionMrrMinor").value(200))
                .andExpect(jsonPath("$.cohorts[0].age60.contractionMrrMinor").value(300))
                .andExpect(jsonPath("$.cohorts[0].age60.retentionPercentage").value(0.9))
                .andExpect(jsonPath("$.cohorts[0].age60.nrr").value(0.9))
                // age90: everything has happened, including the reactivation. retainedMrr (400) reflects
                // it; NRR (0.0) deliberately excludes reactivation per ADR-0004/ADR-0006 -- the two
                // numbers now visibly diverge even though both are "correct" by their own definition.
                .andExpect(jsonPath("$.cohorts[0].age90.retainedMrrMinor").value(400))
                .andExpect(jsonPath("$.cohorts[0].age90.expansionMrrMinor").value(200))
                .andExpect(jsonPath("$.cohorts[0].age90.contractionMrrMinor").value(300))
                .andExpect(jsonPath("$.cohorts[0].age90.churnMrrMinor").value(900))
                .andExpect(jsonPath("$.cohorts[0].age90.reactivationMrrMinor").value(400))
                .andExpect(jsonPath("$.cohorts[0].age90.retentionPercentage").value(0.4))
                .andExpect(jsonPath("$.cohorts[0].age90.nrr").value(0.0));
    }

    @Test
    void keepsCurrenciesCompletelySeparateRatherThanSummingThem() throws Exception {
        acquireAndAttribute("cus_usd", "USD", 1000, "2026-01-05T00:00:00Z", "google", "spring_sale", "/a");
        acquireAndAttribute("cus_eur", "EUR", 800, "2026-01-06T00:00:00Z", "google", "spring_sale", "/a");

        mockMvc.perform(cohorts(OWNER, "SOURCE", null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cohorts.length()").value(2))
                .andExpect(jsonPath("$.cohorts[?(@.currency=='USD')].startingMrrMinor").value(1000))
                .andExpect(jsonPath("$.cohorts[?(@.currency=='USD')].age30.retainedMrrMinor").value(1000))
                .andExpect(jsonPath("$.cohorts[?(@.currency=='EUR')].startingMrrMinor").value(800))
                .andExpect(jsonPath("$.cohorts[?(@.currency=='EUR')].age30.retainedMrrMinor").value(800));
    }

    @Test
    void distinguishesUnattributedFromStronglyAttributedWithNoSourceCaptured() throws Exception {
        // Genuinely no evidence at all (dimensionValue null, attributed false).
        movementAt("cus_unattributed", "USD", 300, "2026-01-07T00:00:00Z", "NEW");
        link("cus_unattributed");
        attribution.recalculate(workspace, project, "cus_unattributed");

        // Real customer-link + touchpoint evidence, but no utm_source captured (dimensionValue null,
        // attributed true) -- a different bucket that must never merge with the one above.
        acquireAndAttribute("cus_direct", "USD", 600, "2026-01-08T00:00:00Z", null, null, "/direct");

        mockMvc.perform(cohorts(OWNER, "SOURCE", null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cohorts.length()").value(2))
                .andExpect(jsonPath("$.cohorts[?(@.attributed==false)].dimensionValue").value((Object) null))
                .andExpect(jsonPath("$.cohorts[?(@.attributed==false)].startingMrrMinor").value(300))
                .andExpect(jsonPath("$.cohorts[?(@.attributed==true)].dimensionValue").value((Object) null))
                .andExpect(jsonPath("$.cohorts[?(@.attributed==true)].startingMrrMinor").value(600));
    }

    @Test
    void aRealSourceLiterallyNamedUnattributedAndACampaignLiterallyNamedNoneAreDistinctFromTheirBuckets() throws Exception {
        // Regression guard mirroring #23's "NONE" regression: a founder-controlled UTM value that
        // happens to collide textually with this API's bucket vocabulary must never be silently
        // reinterpreted as that bucket.
        acquireAndAttribute("cus_literal", "USD", 900, "2026-01-05T00:00:00Z", "UNATTRIBUTED", "NONE", "/real");
        movementAt("cus_missing", "USD", 400, "2026-01-06T00:00:00Z", "NEW");
        link("cus_missing");
        attribution.recalculate(workspace, project, "cus_missing");

        mockMvc.perform(cohorts(OWNER, "SOURCE", null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cohorts[?(@.dimensionValue=='UNATTRIBUTED')].attributed").value(true))
                .andExpect(jsonPath("$.cohorts[?(@.dimensionValue=='UNATTRIBUTED')].startingMrrMinor").value(900))
                .andExpect(jsonPath("$.cohorts[?(@.dimensionValue==null && @.attributed==false)].startingMrrMinor").value(400));

        mockMvc.perform(cohorts(OWNER, "CAMPAIGN", "UNATTRIBUTED", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cohorts[?(@.dimensionValue=='NONE')].startingMrrMinor").value(900))
                .andExpect(jsonPath("$.cohorts.length()").value(1));
    }

    @Test
    void groupsCampaignsAndLandingPagesWithinASource() throws Exception {
        acquireAndAttribute("cus_spring_a", "USD", 1000, "2026-01-05T00:00:00Z", "google", "spring_sale", "/landing-a");
        acquireAndAttribute("cus_spring_b", "USD", 600, "2026-01-06T00:00:00Z", "google", "spring_sale", "/landing-b");
        acquireAndAttribute("cus_summer", "USD", 700, "2026-01-07T00:00:00Z", "google", "summer_sale", "/landing-a");
        acquireAndAttribute("cus_other_source", "USD", 900, "2026-01-08T00:00:00Z", "bing", "spring_sale", "/landing-a");

        mockMvc.perform(cohorts(OWNER, "CAMPAIGN", "google", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cohorts.length()").value(2))
                .andExpect(jsonPath("$.cohorts[?(@.dimensionValue=='spring_sale')].startingMrrMinor").value(1600))
                .andExpect(jsonPath("$.cohorts[?(@.dimensionValue=='spring_sale')].sampleSize").value(2))
                .andExpect(jsonPath("$.cohorts[?(@.dimensionValue=='summer_sale')].startingMrrMinor").value(700));

        mockMvc.perform(cohorts(OWNER, "LANDING_PAGE", "google", "spring_sale"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cohorts.length()").value(2))
                .andExpect(jsonPath("$.cohorts[?(@.dimensionValue=='https://example.test/landing-a')].startingMrrMinor").value(1000))
                .andExpect(jsonPath("$.cohorts[?(@.dimensionValue=='https://example.test/landing-b')].startingMrrMinor").value(600));

        mockMvc.perform(cohortsRequest(OWNER, "CAMPAIGN", null, null, false)).andExpect(status().isBadRequest());
        mockMvc.perform(cohortsRequest(OWNER, "LANDING_PAGE", "google", null, false)).andExpect(status().isBadRequest());
    }

    @Test
    void reflectsLateArrivingAttributionAndRecalculationOnTheNextReadWithNoInvalidationStep() throws Exception {
        // The customer is billed before any acquisition evidence exists -- Unattributed at first.
        movementAt("cus_late", "USD", 500, "2026-01-05T00:00:00Z", "NEW");
        link("cus_late");
        attribution.recalculate(workspace, project, "cus_late");

        mockMvc.perform(cohorts(OWNER, "SOURCE", null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cohorts.length()").value(1))
                .andExpect(jsonPath("$.cohorts[0].dimensionValue").value((Object) null))
                .andExpect(jsonPath("$.cohorts[0].attributed").value(false));

        // A touchpoint arrives late (e.g. a delayed identify() call) and attribution recalculates.
        touchpoint("cus_late", "2026-01-04T00:00:00Z", "google", "spring_sale", "https://example.test/a");
        attribution.recalculate(workspace, project, "cus_late");

        mockMvc.perform(cohorts(OWNER, "SOURCE", null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cohorts.length()").value(1))
                .andExpect(jsonPath("$.cohorts[0].dimensionValue").value("google"))
                .andExpect(jsonPath("$.cohorts[0].attributed").value(true))
                .andExpect(jsonPath("$.cohorts[0].startingMrrMinor").value(500));
    }

    @Test
    void aCustomerRelinkedToADifferentProjectMovesTheirEntireCohortMembership() throws Exception {
        UUID projectB = UUID.randomUUID();
        db.sql("INSERT INTO projects (id, workspace_id, name, domain, public_key) VALUES (:p, :w, 'pB', 'b.example', :k)")
                .param("p", projectB).param("w", workspace).param("k", "pk-" + projectB).update();

        insertBillingCustomer("cus_mover");
        insertBillingCustomer("cus_displacer");
        insertIdentity("identity-a", project, "user_a");
        insertIdentity("identity-b", projectB, "user_b");

        mockMvc.perform(repair(OWNER, project, "user_a", "cus_mover")).andExpect(status().isOk());
        movementAt("cus_mover", "USD", 1000, "2026-01-02T00:00:00Z", "NEW");
        attribution.recalculate(workspace, project, "cus_mover");

        mockMvc.perform(cohorts(OWNER, "SOURCE", null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cohorts[0].startingMrrMinor").value(1000));

        mockMvc.perform(repair(OWNER, project, "user_a", "cus_displacer")).andExpect(status().isOk());
        mockMvc.perform(repair(OWNER, projectB, "user_b", "cus_mover")).andExpect(status().isOk());

        mockMvc.perform(cohorts(OWNER, "SOURCE", null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cohorts.length()").value(0));

        mockMvc.perform(get(
                                "/api/workspaces/{workspaceId}/projects/{projectId}/reporting/retention/cohorts",
                                workspace,
                                projectB)
                        .queryParam("dimension", "SOURCE")
                        .with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cohorts[0].startingMrrMinor").value(1000));
    }

    @Test
    void isolatesCohortsByWorkspaceAndProjectMembership() throws Exception {
        acquireAndAttribute("cus_in_scope", "USD", 1000, "2026-01-05T00:00:00Z", "google", "spring_sale", "/a");

        UUID otherProject = UUID.randomUUID();
        db.sql("INSERT INTO projects (id, workspace_id, name, domain, public_key) VALUES (:p, :w, 'p2', 'two.example', :k)")
                .param("p", otherProject).param("w", workspace).param("k", "pk-" + otherProject).update();

        mockMvc.perform(get(
                                "/api/workspaces/{workspaceId}/projects/{projectId}/reporting/retention/cohorts",
                                workspace,
                                otherProject)
                        .queryParam("dimension", "SOURCE")
                        .with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cohorts.length()").value(0));

        mockMvc.perform(cohorts("user-not-a-member", "SOURCE", null, null)).andExpect(status().isNotFound());
    }

    @Test
    void producesIdenticalOutputOnRepeatedReadsWithStableOrdering() throws Exception {
        acquireAndAttribute("cus_bravo", "USD", 500, "2026-01-05T00:00:00Z", "bravo_source", "c", "/a");
        acquireAndAttribute("cus_alpha", "USD", 500, "2026-01-06T00:00:00Z", "alpha_source", "c", "/a");
        acquireAndAttribute("cus_alpha_feb", "USD", 500, "2026-02-05T00:00:00Z", "alpha_source", "c", "/a");

        String first = mockMvc.perform(cohorts(OWNER, "SOURCE", null, null))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(cohorts(OWNER, "SOURCE", null, null))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(first).isEqualTo(second);
        mockMvc.perform(cohorts(OWNER, "SOURCE", null, null))
                // January period before February; alpha before bravo within January.
                .andExpect(jsonPath("$.cohorts[0].dimensionValue").value("alpha_source"))
                .andExpect(jsonPath("$.cohorts[0].periodStart").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.cohorts[1].dimensionValue").value("bravo_source"))
                .andExpect(jsonPath("$.cohorts[2].dimensionValue").value("alpha_source"))
                .andExpect(jsonPath("$.cohorts[2].periodStart").value("2026-02-01T00:00:00Z"));
    }

    @Test
    void summaryAggregatesEveryPeriodInRangeAndReconcilesWithComparisonAndMovementDrilldown() throws Exception {
        // Two acquisition months within the requested [from, to) summary range.
        acquireAndAttribute("cus_jan", "USD", 1000, "2026-01-05T00:00:00Z", "google", "spring_sale", "/a");
        movementAt("cus_jan", "USD", 200, "2026-01-20T00:00:00Z", "EXPANSION");
        attribution.recalculate(workspace, project, "cus_jan");
        acquireAndAttribute("cus_feb", "USD", 500, "2026-02-05T00:00:00Z", "google", "spring_sale", "/a");

        mockMvc.perform(summary(OWNER, "2026-01-01T00:00:00Z", "2026-03-01T00:00:00Z", "SOURCE", null, null, 30))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].dimensionValue").value("google"))
                .andExpect(jsonPath("$.rows[0].startingMrrMinor").value(1500))
                .andExpect(jsonPath("$.rows[0].sampleSize").value(2))
                .andExpect(jsonPath("$.rows[0].cell.available").value(true))
                .andExpect(jsonPath("$.rows[0].cell.retainedMrrMinor").value(1700))
                .andExpect(jsonPath("$.rows[0].cell.expansionMrrMinor").value(200));

        // Reconciles with #23's comparison endpoint, which joins in this same retention data.
        mockMvc.perform(get("/api/workspaces/{workspaceId}/projects/{projectId}/reporting/comparison", workspace, project)
                        .queryParam("from", "2026-01-01T00:00:00Z").queryParam("to", "2026-03-01T00:00:00Z")
                        .queryParam("dimension", "SOURCE").queryParam("retentionAgeDays", "30")
                        .with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retention[?(@.dimensionValue=='google')].startingMrrMinor").value(1500))
                .andExpect(jsonPath("$.retention[?(@.dimensionValue=='google')].cell.retainedMrrMinor").value(1700));

        // Reconciles with #22's movement drill-down: the expansion total behind the cell.
        mockMvc.perform(get("/api/workspaces/{workspaceId}/projects/{projectId}/reporting/movements", workspace, project)
                        .queryParam("from", "2026-01-01T00:00:00Z").queryParam("to", "2026-03-01T00:00:00Z")
                        .queryParam("movementType", "EXPANSION").queryParam("source", "google")
                        .with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].amountMinor").value(200));
    }

    // -- fixtures (mirrors SourceComparisonIntegrationTests) --

    private void acquireAndAttribute(
            String stripeCustomerId, String currency, long amountMinor, String effectiveAt,
            String source, String campaign, String landingPath) {
        movementAt(stripeCustomerId, currency, amountMinor, effectiveAt, "NEW");
        link(stripeCustomerId);
        touchpoint(stripeCustomerId, OffsetDateTime.parse(effectiveAt).minusDays(1).toString(), source, campaign,
                landingPath == null ? null : "https://example.test" + landingPath);
        attribution.recalculate(workspace, project, stripeCustomerId);
    }

    private void movementAt(String stripeCustomerId, String currency, long amountMinor, String effectiveAt, String movementType) {
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

    private MockHttpServletRequestBuilder cohorts(String actor, String dimension, String source, String campaign) {
        return cohortsRequest(actor, dimension, source, campaign, false);
    }

    private MockHttpServletRequestBuilder cohortsRequest(
            String actor, String dimension, String source, String campaign, boolean campaignMissing) {
        var request = get("/api/workspaces/{workspaceId}/projects/{projectId}/reporting/retention/cohorts", workspace, project)
                .queryParam("dimension", dimension)
                .with(token(actor));
        if (source != null) request = request.queryParam("source", source);
        if (campaign != null) request = request.queryParam("campaign", campaign);
        if (campaignMissing) request = request.queryParam("campaignMissing", "true");
        return request;
    }

    private MockHttpServletRequestBuilder summary(
            String actor, String from, String to, String dimension, String source, String campaign, int ageDays) {
        var request = get("/api/workspaces/{workspaceId}/projects/{projectId}/reporting/retention/summary", workspace, project)
                .queryParam("from", from).queryParam("to", to)
                .queryParam("dimension", dimension)
                .queryParam("ageDays", String.valueOf(ageDays))
                .with(token(actor));
        if (source != null) request = request.queryParam("source", source);
        if (campaign != null) request = request.queryParam("campaign", campaign);
        return request;
    }

    private RequestPostProcessor token(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject)
                .issuer("http://localhost:8081/realms/mrr-origin")
                .audience(List.of("mrr-origin-api")));
    }
}
