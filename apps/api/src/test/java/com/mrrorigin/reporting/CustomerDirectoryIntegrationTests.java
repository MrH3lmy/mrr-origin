package com.mrrorigin.reporting;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.mrrorigin.attribution.AttributionApplicationService;

import tools.jackson.databind.ObjectMapper;

/**
 * #24's workspace/project-scoped customer directory: search, stable keyset pagination, per-currency
 * current MRR, and workspace/project isolation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CustomerDirectoryIntegrationTests {
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
    void listsCustomersWithAttributionSummaryAndCurrentMrr() throws Exception {
        insertBillingCustomer("cus_a", "2026-01-01T00:00:00Z");
        movement("cus_a", "USD", 1_500, "2026-01-01T00:00:00Z");
        link("cus_a");
        touchpoint("cus_a", "2025-12-15T00:00:00Z", "google", "spring-sale");
        attribution.recalculate(workspace, project, "cus_a");
        snapshot("cus_a", "USD", 1_500, "2026-01-01T00:00:00Z");

        mockMvc.perform(list(OWNER, null, null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].stripeCustomerId").value("cus_a"))
                .andExpect(jsonPath("$.entries[0].confidence").value("STRONG"))
                .andExpect(jsonPath("$.entries[0].firstSource").value("google"))
                .andExpect(jsonPath("$.entries[0].currentMrr[0].currency").value("USD"))
                .andExpect(jsonPath("$.entries[0].currentMrr[0].amountMinor").value(1500));
    }

    @Test
    void searchFiltersByCustomerIdSubstring() throws Exception {
        insertBillingCustomer("cus_alpha", "2026-01-01T00:00:00Z");
        link("cus_alpha");
        insertBillingCustomer("cus_beta", "2026-01-02T00:00:00Z");
        link("cus_beta");

        mockMvc.perform(list(OWNER, "alpha", null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].stripeCustomerId").value("cus_alpha"));
    }

    @Test
    void multiCurrencySeparationInCurrentMrr() throws Exception {
        insertBillingCustomer("cus_multi", "2026-01-01T00:00:00Z");
        link("cus_multi");
        snapshot("cus_multi", "USD", 1_000, "2026-01-01T00:00:00Z");
        snapshot("cus_multi", "EUR", 900, "2026-01-01T00:00:00Z");

        mockMvc.perform(list(OWNER, null, null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].currentMrr.length()").value(2))
                .andExpect(jsonPath("$.entries[0].currentMrr[0].currency").value("EUR"))
                .andExpect(jsonPath("$.entries[0].currentMrr[0].amountMinor").value(900))
                .andExpect(jsonPath("$.entries[0].currentMrr[1].currency").value("USD"))
                .andExpect(jsonPath("$.entries[0].currentMrr[1].amountMinor").value(1000));
    }

    @Test
    void paginationIsStableDeterministicAndGapFree() throws Exception {
        insertBillingCustomer("cus_page_1", "2026-01-01T00:00:00Z");
        link("cus_page_1");
        insertBillingCustomer("cus_page_2", "2026-01-02T00:00:00Z");
        link("cus_page_2");
        insertBillingCustomer("cus_page_3", "2026-01-03T00:00:00Z");
        link("cus_page_3");

        String firstPage = mockMvc.perform(list(OWNER, null, null, 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].stripeCustomerId").value("cus_page_3"))
                .andExpect(jsonPath("$.entries[1].stripeCustomerId").value("cus_page_2"))
                .andExpect(jsonPath("$.nextCursor").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String cursor = new ObjectMapper().readTree(firstPage).get("nextCursor").asText();

        mockMvc.perform(list(OWNER, null, cursor, 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].stripeCustomerId").value("cus_page_1"))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void isolatesListingByWorkspaceAndProject() throws Exception {
        insertBillingCustomer("cus_in_scope", "2026-01-01T00:00:00Z");
        link("cus_in_scope");

        UUID otherProject = UUID.randomUUID();
        db.sql("INSERT INTO projects (id, workspace_id, name, domain, public_key) VALUES (:p, :w, 'p2', 'two.example', :k)")
                .param("p", otherProject).param("w", workspace).param("k", "pk-" + otherProject).update();
        insertBillingCustomer("cus_in_other_project", "2026-01-01T00:00:00Z");
        UUID otherIdentity = UUID.randomUUID();
        db.sql("INSERT INTO external_identities (id, workspace_id, project_id, external_user_id) VALUES (:i, :w, :p, 'user-other')")
                .param("i", otherIdentity).param("w", workspace).param("p", otherProject).update();
        db.sql(
                        """
                        INSERT INTO stripe_customer_links
                            (id, workspace_id, project_id, external_identity_id, stripe_customer_id,
                             evidence_source, evidence_reference, linked_by_subject_id)
                        VALUES (:id, :w, :p, :i, 'cus_in_other_project', 'EXPLICIT_API', 'evidence', 'owner')
                        """)
                .param("id", UUID.randomUUID()).param("w", workspace).param("p", otherProject).param("i", otherIdentity)
                .update();

        mockMvc.perform(list(OWNER, null, null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].stripeCustomerId").value("cus_in_scope"));

        mockMvc.perform(get("/api/workspaces/{workspaceId}/projects/{projectId}/customers", workspace, otherProject)
                        .with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].stripeCustomerId").value("cus_in_other_project"));
    }

    @Test
    void nonMemberCannotListAnotherWorkspacesCustomers() throws Exception {
        insertBillingCustomer("cus_1", "2026-01-01T00:00:00Z");
        link("cus_1");

        mockMvc.perform(list("user-not-a-member", null, null, null)).andExpect(status().isNotFound());
    }

    private void movement(String stripeCustomerId, String currency, long amountMinor, String effectiveAt) {
        db.sql(
                        """
                        INSERT INTO customer_mrr_movements
                            (id, workspace_id, stripe_customer_id, currency, amount_minor, movement_type,
                             effective_at, calculation_version, source_billing_references)
                        VALUES (:id, :w, :c, :cur, :amt, 'NEW', :at, 'mrr-v1', ARRAY['billing:test'])
                        """)
                .param("id", UUID.randomUUID())
                .param("w", workspace)
                .param("c", stripeCustomerId)
                .param("cur", currency)
                .param("amt", amountMinor)
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
                        INSERT INTO stripe_customer_links
                            (id, workspace_id, project_id, external_identity_id, stripe_customer_id,
                             evidence_source, evidence_reference, linked_by_subject_id)
                        VALUES (:id, :w, :p, :i, :c, 'EXPLICIT_API', 'evidence', 'owner')
                        """)
                .param("id", UUID.randomUUID()).param("w", workspace).param("p", project).param("i", identity)
                .param("c", stripeCustomerId)
                .update();
    }

    private void insertBillingCustomer(String stripeCustomerId, String providerCreatedAt) {
        db.sql(
                        """
                        INSERT INTO billing_customers
                            (id, workspace_id, stripe_customer_id, provider_created_at, source, source_version, source_sequence)
                        VALUES (:id, :w, :c, :at, 'BACKFILL', 1, :c)
                        """)
                .param("id", UUID.randomUUID())
                .param("w", workspace)
                .param("c", stripeCustomerId)
                .param("at", OffsetDateTime.parse(providerCreatedAt))
                .update();
    }

    private void snapshot(String stripeCustomerId, String currency, long amountMinor, String effectiveAt) {
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

    private void touchpoint(String stripeCustomerId, String at, String utmSource, String utmCampaign) {
        UUID identity = db.sql(
                        "SELECT external_identity_id FROM stripe_customer_links WHERE workspace_id = :w AND stripe_customer_id = :c")
                .param("w", workspace).param("c", stripeCustomerId).query(UUID.class).single();
        UUID visitor = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        OffsetDateTime time = OffsetDateTime.parse(at);
        db.sql(
                        "INSERT INTO visitors (id, workspace_id, project_id, external_visitor_id, first_seen_at, last_seen_at) "
                                + "VALUES (:v, :w, :p, :e, :at, :at)")
                .param("v", visitor).param("w", workspace).param("p", project).param("e", visitor.toString()).param("at", time)
                .update();
        db.sql(
                        "INSERT INTO visitor_aliases (id, workspace_id, project_id, visitor_id, external_identity_id, identified_at) "
                                + "VALUES (:id, :w, :p, :v, :i, now())")
                .param("id", UUID.randomUUID()).param("w", workspace).param("p", project).param("v", visitor).param("i", identity)
                .update();
        db.sql(
                        "INSERT INTO tracking_sessions (id, workspace_id, project_id, visitor_id, external_session_id, started_at) "
                                + "VALUES (:s, :w, :p, :v, :e, :at)")
                .param("s", session).param("w", workspace).param("p", project).param("v", visitor).param("e", session.toString())
                .param("at", time)
                .update();
        db.sql(
                        "INSERT INTO touchpoints (id, workspace_id, project_id, visitor_id, session_id, occurred_at, landing_url, "
                                + "utm_source, utm_campaign, created_at) VALUES (:id, :w, :p, :v, :s, :at, 'https://example.test/', :src, :camp, :created)")
                .param("id", UUID.randomUUID()).param("w", workspace).param("p", project).param("v", visitor).param("s", session)
                .param("at", time).param("src", utmSource).param("camp", utmCampaign).param("created", time.plusSeconds(1))
                .update();
    }

    private MockHttpServletRequestBuilder list(String actor, String search, String cursor, Integer limit) {
        var request = get("/api/workspaces/{workspaceId}/projects/{projectId}/customers", workspace, project).with(token(actor));
        if (search != null) {
            request = request.queryParam("search", search);
        }
        if (cursor != null) {
            request = request.queryParam("cursor", cursor);
        }
        if (limit != null) {
            request = request.queryParam("limit", String.valueOf(limit));
        }
        return request;
    }

    private RequestPostProcessor token(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject)
                .issuer("http://localhost:8081/realms/mrr-origin")
                .audience(List.of("mrr-origin-api")));
    }
}
