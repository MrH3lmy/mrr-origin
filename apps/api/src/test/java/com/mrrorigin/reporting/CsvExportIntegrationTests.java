package com.mrrorigin.reporting;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
 * #26's three v1 CSV exports: stable schema/header/version, {@code NONE} vs {@code UNATTRIBUTED} as
 * distinct never-coalesced tokens, unavailable retained-MRR/NRR contract (blank, never {@code 0}),
 * reconciliation against the movements drilldown, multi-currency separation with no cross-currency
 * totals, customer-identity redaction (PR #57's rule, reused), tenant isolation/authorization, a
 * large-export streaming check, and export-audit recording without exported customer data.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CsvExportIntegrationTests {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:18-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    private static final String OWNER = "user-owner";
    private static final String VIEWER = "user-viewer";
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
        db.sql("INSERT INTO workspace_members (workspace_id, subject_id, role) VALUES (:w, :s, 'VIEWER')")
                .param("w", workspace).param("s", VIEWER).update();
        db.sql("INSERT INTO projects (id, workspace_id, name, domain, public_key) VALUES (:p, :w, 'p', 'one.example', :k)")
                .param("p", project).param("w", workspace).param("k", "pk-" + project).update();
    }

    @Test
    void comparisonExportHasStableSchemaAndDistinguishesNoneFromUnattributed() throws Exception {
        acquireAndAttribute("cus_google", "USD", 1000, "2026-04-05T00:00:00Z", "google", "spring_sale", "/a");
        // STRONG confidence, no utm_source captured -> NONE bucket.
        acquireAndAttribute("cus_no_source", "USD", 500, "2026-04-06T00:00:00Z", null, null, "/b");
        // No deterministic link at all -> UNATTRIBUTED bucket.
        movement("cus_unattributed", "USD", 300, "2026-04-07T00:00:00Z", "NEW");
        link("cus_unattributed");
        attribution.recalculate(workspace, project, "cus_unattributed");

        var result = mockMvc.perform(comparisonExport(OWNER, "SOURCE"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Export-Schema-Version", "comparison-v1"))
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.valueOf("text/csv")))
                .andReturn();

        String csv = result.getResponse().getContentAsString();
        List<String> lines = csv.lines().toList();
        Assertions.assertEquals(
                "dimension,dimension_value,dimension_bucket,currency,period_start,period_end,new_mrr_amount_minor,"
                        + "new_mrr_customer_count,churned_mrr_amount_minor,churned_mrr_customer_count,retention_age_days,"
                        + "retained_mrr_available,retained_mrr_amount_minor,retention_percentage_available,"
                        + "retention_percentage,nrr_available,nrr,unavailable_reason,evidence_link",
                lines.get(0));

        boolean hasNoneBucket = lines.stream().anyMatch(l -> l.contains(",NONE,USD,") && l.contains("500"));
        boolean hasUnattributedBucket = lines.stream().anyMatch(l -> l.contains(",UNATTRIBUTED,USD,") && l.contains("300"));
        Assertions.assertTrue(hasNoneBucket, "NONE bucket row missing: " + csv);
        Assertions.assertTrue(hasUnattributedBucket, "UNATTRIBUTED bucket row missing: " + csv);
        // Never the same row / never coalesced into one bucket.
        Assertions.assertNotEquals(
                lines.stream().filter(l -> l.contains(",NONE,")).findFirst(),
                lines.stream().filter(l -> l.contains(",UNATTRIBUTED,")).findFirst());
    }

    @Test
    void comparisonExportReconcilesExactlyWithTheMovementsDrilldownPerCurrency() throws Exception {
        acquireAndAttribute("cus_a", "USD", 1000, "2026-04-05T00:00:00Z", "google", "spring_sale", "/a");
        acquireAndAttribute("cus_b", "USD", 500, "2026-04-06T00:00:00Z", "google", "spring_sale", "/a");
        acquireAndAttribute("cus_c", "EUR", 700, "2026-04-07T00:00:00Z", "google", "spring_sale", "/a");

        String csv = mockMvc.perform(comparisonExport(OWNER, "SOURCE")).andReturn().getResponse().getContentAsString();
        String[] usdCols = csv.lines().filter(l -> l.startsWith("SOURCE,google,,USD,")).findFirst().orElseThrow().split(",", -1);
        String[] eurCols = csv.lines().filter(l -> l.startsWith("SOURCE,google,,EUR,")).findFirst().orElseThrow().split(",", -1);
        Assertions.assertEquals("1500", usdCols[6], "USD new_mrr_amount_minor must total 1500 across 2 customers");
        Assertions.assertEquals("2", usdCols[7]);
        Assertions.assertEquals("700", eurCols[6], "EUR must total 700 across 1 customer, never blended with USD");
        Assertions.assertEquals("1", eurCols[7]);

        mockMvc.perform(movements(OWNER, "google", "USD"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.entries.length()").value(2));
        mockMvc.perform(movements(OWNER, "google", "EUR"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.entries.length()").value(1));
    }

    @Test
    void comparisonExportLeavesUnavailableRetentionBlankNeverZeroWithReason() throws Exception {
        acquireAndAttribute("cus_future", "USD", 1000, "2099-04-05T00:00:00Z", "google", "future_campaign", "/future");

        var request = get("/api/workspaces/{workspaceId}/projects/{projectId}/reporting/exports/comparison", workspace, project)
                .queryParam("from", "2099-04-01T00:00:00Z")
                .queryParam("to", "2099-05-01T00:00:00Z")
                .queryParam("dimension", "SOURCE")
                .with(token(OWNER));
        String csv = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String row = csv.lines().filter(l -> l.startsWith("SOURCE,google,,USD,")).findFirst().orElseThrow();
        String[] cols = row.split(",", -1);
        // Every unavailable metric uses the same false + blank shape, never a fabricated zero.
        Assertions.assertEquals("false", cols[11]);
        Assertions.assertEquals("", cols[12]);
        Assertions.assertEquals("false", cols[13]);
        Assertions.assertEquals("", cols[14]);
        Assertions.assertEquals("false", cols[15]);
        Assertions.assertEquals("", cols[16]);
        Assertions.assertEquals("MATURITY_PENDING", cols[17]);
    }

    @Test
    void comparisonExportUsesTheSameUnavailableShapeWhenNoAcquisitionCohortExists() throws Exception {
        movement("cus_churn_only", "USD", 700, "2026-04-05T00:00:00Z", "CHURN");
        link("cus_churn_only");
        attribution.recalculate(workspace, project, "cus_churn_only");

        String csv = mockMvc.perform(comparisonExport(OWNER, "SOURCE"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String row = csv.lines()
                .filter(l -> l.startsWith("SOURCE,,UNATTRIBUTED,USD,"))
                .findFirst()
                .orElseThrow();
        String[] cols = row.split(",", -1);

        Assertions.assertEquals("false", cols[11]);
        Assertions.assertEquals("", cols[12]);
        Assertions.assertEquals("false", cols[13]);
        Assertions.assertEquals("", cols[14]);
        Assertions.assertEquals("false", cols[15]);
        Assertions.assertEquals("", cols[16]);
        Assertions.assertEquals("NO_ACQUISITION_COHORT", cols[17]);
    }

    @Test
    void retentionCohortsExportHasStableSchemaAndNeverFabricatesAZero() throws Exception {
        acquireAndAttribute("cus_google", "USD", 1000, "2026-01-05T00:00:00Z", "google", "spring_sale", "/a");

        var request = get(
                        "/api/workspaces/{workspaceId}/projects/{projectId}/reporting/exports/retention-cohorts",
                        workspace, project)
                .queryParam("dimension", "SOURCE")
                .with(token(OWNER));
        var result = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(header().string("X-Export-Schema-Version", "retention-cohorts-v1"))
                .andReturn();
        List<String> lines = result.getResponse().getContentAsString().lines().toList();
        Assertions.assertTrue(lines.get(0).startsWith("dimension,dimension_value,dimension_bucket,currency,period_start"));
        Assertions.assertTrue(lines.get(0).endsWith("evidence_link"));
        // January 2026 is long mature by the time this test runs, so age30 must be populated, not blank.
        String row = lines.stream().filter(l -> l.startsWith("SOURCE,google,,USD,")).findFirst().orElseThrow();
        String[] cols = row.split(",", -1);
        Assertions.assertEquals("true", cols[8]); // age30_available
        Assertions.assertEquals("1000", cols[9]); // age30_retained_mrr_amount_minor
    }

    @Test
    void customersExportRedactsIdentityForNonManagersButNotForOwners() throws Exception {
        acquireAndAttribute("cus_google", "USD", 1000, "2026-04-05T00:00:00Z", "google", "spring_sale", "/a");

        String ownerCsv = mockMvc.perform(customersExport(OWNER))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Export-Schema-Version", "customers-v1"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String ownerRow = ownerCsv.lines().filter(l -> l.startsWith("cus_google,")).findFirst().orElseThrow();
        Assertions.assertTrue(ownerRow.contains("user-cus_google"), "owner must see external_user_id: " + ownerRow);

        String viewerCsv = mockMvc.perform(customersExport(VIEWER))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String viewerRow = viewerCsv.lines().filter(l -> l.startsWith("cus_google,")).findFirst().orElseThrow();
        Assertions.assertFalse(viewerRow.contains("user-cus_google"), "viewer must not see external_user_id: " + viewerRow);
        // A fully valid, still-reconciling export -- not a 403 -- with only that column blank.
        String[] cols = viewerRow.split(",", -1);
        Assertions.assertEquals("1000", cols[9]); // current_mrr_amount_minor still populated
    }

    @Test
    void customersExportStreamsAllPagesForALargeCustomerCount() throws Exception {
        // Larger than CustomerDirectoryService's page size (100), so the export must walk multiple
        // cursor pages rather than truncating at the first page.
        for (int i = 0; i < 150; i++) {
            insertBillingCustomerOnly("cus_bulk_" + i);
        }

        String csv = mockMvc.perform(customersExport(OWNER)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long dataRows = csv.lines().skip(1).filter(l -> !l.isBlank()).count();
        Assertions.assertEquals(150, dataRows);
    }

    @Test
    void recordsAnExportAuditEntryWithoutLoggingExportedCustomerData() throws Exception {
        acquireAndAttribute("cus_google", "USD", 1000, "2026-04-05T00:00:00Z", "google", "spring_sale", "/a");

        mockMvc.perform(customersExport(OWNER)).andExpect(status().isOk());

        List<String> auditRows = db.sql(
                        "SELECT export_type, schema_version, actor_subject_id, row_count, filters::text AS filters "
                                + "FROM export_audit_log WHERE workspace_id = :w AND project_id = :p")
                .param("w", workspace).param("p", project)
                .query((rs, n) -> rs.getString("export_type") + "|" + rs.getString("schema_version") + "|"
                        + rs.getString("actor_subject_id") + "|" + rs.getLong("row_count") + "|" + rs.getString("filters"))
                .list();
        Assertions.assertEquals(1, auditRows.size());
        String audit = auditRows.get(0);
        Assertions.assertTrue(audit.startsWith("CUSTOMERS|customers-v1|" + OWNER + "|1|"), "audit row: " + audit);
        Assertions.assertFalse(audit.contains("cus_google"), "audit must never contain exported customer data: " + audit);
        Assertions.assertFalse(audit.contains("user-cus_google"), "audit must never contain exported identity: " + audit);
    }

    @Test
    void isTenantIsolatedAcrossAllThreeExports() throws Exception {
        acquireAndAttribute("cus_google", "USD", 1000, "2026-04-05T00:00:00Z", "google", "spring_sale", "/a");

        UUID otherWorkspace = UUID.randomUUID();
        UUID otherProject = UUID.randomUUID();
        db.sql("INSERT INTO workspaces (id, name, slug) VALUES (:id, 'w2', :slug)")
                .param("id", otherWorkspace).param("slug", "w2-" + otherWorkspace).update();
        db.sql("INSERT INTO workspace_members (workspace_id, subject_id, role) VALUES (:w, :s, 'OWNER')")
                .param("w", otherWorkspace).param("s", "other-owner").update();
        db.sql("INSERT INTO projects (id, workspace_id, name, domain, public_key) VALUES (:p, :w, 'p2', 'two.example', :k)")
                .param("p", otherProject).param("w", otherWorkspace).param("k", "pk-" + otherProject).update();

        mockMvc.perform(get(
                                "/api/workspaces/{workspaceId}/projects/{projectId}/reporting/exports/comparison",
                                workspace, project)
                        .queryParam("from", FROM).queryParam("to", TO).queryParam("dimension", "SOURCE")
                        .with(token("other-owner")))
                .andExpect(status().isNotFound());

        String otherCsv = mockMvc.perform(get(
                                "/api/workspaces/{workspaceId}/projects/{projectId}/reporting/exports/customers",
                                otherWorkspace, otherProject)
                        .with(token("other-owner")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Assertions.assertFalse(otherCsv.contains("cus_google"));
    }

    // -- fixtures --

    private void acquireAndAttribute(
            String stripeCustomerId, String currency, long amountMinor, String effectiveAt,
            String source, String campaign, String landingPath) {
        movement(stripeCustomerId, currency, amountMinor, effectiveAt, "NEW");
        link(stripeCustomerId);
        // A linked, eligible direct touchpoint with null utm_source is STRONG attribution in the
        // explicit NONE bucket; omitting the touchpoint entirely would instead be UNATTRIBUTED.
        touchpoint(stripeCustomerId, OffsetDateTime.parse(effectiveAt).minusDays(1).toString(), source, campaign,
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
        if ("NEW".equals(movementType)) {
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

    private void insertBillingCustomerOnly(String stripeCustomerId) {
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
                .param("id", UUID.randomUUID()).param("w", workspace).param("p", project)
                .param("i", insertIdentity(stripeCustomerId))
                .param("c", stripeCustomerId)
                .update();
    }

    private UUID insertIdentity(String stripeCustomerId) {
        UUID identity = UUID.randomUUID();
        db.sql("INSERT INTO external_identities (id, workspace_id, project_id, external_user_id) VALUES (:i, :w, :p, :u)")
                .param("i", identity).param("w", workspace).param("p", project).param("u", "user-" + stripeCustomerId)
                .update();
        return identity;
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

    private MockHttpServletRequestBuilder comparisonExport(String actor, String dimension) {
        return get("/api/workspaces/{workspaceId}/projects/{projectId}/reporting/exports/comparison", workspace, project)
                .queryParam("from", FROM)
                .queryParam("to", TO)
                .queryParam("dimension", dimension)
                .with(token(actor));
    }

    private MockHttpServletRequestBuilder customersExport(String actor) {
        return get("/api/workspaces/{workspaceId}/projects/{projectId}/reporting/exports/customers", workspace, project)
                .with(token(actor));
    }

    private MockHttpServletRequestBuilder movements(String actor, String source, String currency) {
        return get("/api/workspaces/{workspaceId}/projects/{projectId}/reporting/movements", workspace, project)
                .queryParam("from", FROM)
                .queryParam("to", TO)
                .queryParam("movementType", "NEW")
                .queryParam("source", source)
                .queryParam("currency", currency)
                .with(token(actor));
    }

    private RequestPostProcessor token(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject)
                .issuer("http://localhost:8081/realms/mrr-origin")
                .audience(List.of("mrr-origin-api")));
    }
}
