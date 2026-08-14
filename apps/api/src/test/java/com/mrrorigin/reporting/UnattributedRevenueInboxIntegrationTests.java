package com.mrrorigin.reporting;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.mrrorigin.attribution.AttributionApplicationService;

import tools.jackson.databind.ObjectMapper;

/**
 * #20's read-only unattributed-revenue inbox: reason-code correctness, stable keyset pagination,
 * and workspace/project isolation, per ARCHITECTURE.md's "Unattributed revenue inbox" outcome.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class UnattributedRevenueInboxIntegrationTests {
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
    void listsCustomersWithCorrectDeterministicReasons() throws Exception {
        movement("cus_no_active_link", "2026-04-01T00:00:00Z");
        attribution.recalculate(workspace, project, "cus_no_active_link");

        movement("cus_no_touchpoint", "2026-04-02T00:00:00Z");
        link("cus_no_touchpoint");
        attribution.recalculate(workspace, project, "cus_no_touchpoint");

        movement("cus_not_recalculated", "2026-04-03T00:00:00Z");
        link("cus_not_recalculated");

        movement("cus_strong", "2026-04-04T00:00:00Z");
        link("cus_strong");
        touchpoint("cus_strong", "2026-03-15T00:00:00Z");
        attribution.recalculate(workspace, project, "cus_strong");

        mockMvc.perform(list(OWNER, null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(3))
                .andExpect(jsonPath("$.entries[0].stripeCustomerId").value("cus_no_active_link"))
                .andExpect(jsonPath("$.entries[0].reason").value("NO_ACTIVE_LINK"))
                .andExpect(jsonPath("$.entries[1].stripeCustomerId").value("cus_no_touchpoint"))
                .andExpect(jsonPath("$.entries[1].reason").value("NO_ELIGIBLE_TOUCHPOINT"))
                .andExpect(jsonPath("$.entries[2].stripeCustomerId").value("cus_not_recalculated"))
                .andExpect(jsonPath("$.entries[2].reason").value("NOT_RECALCULATED"));
    }

    @Test
    void paginationIsStableDeterministicAndGapFree() throws Exception {
        for (int i = 1; i <= 3; i++) {
            String customerId = "cus_page_" + i;
            movement(customerId, "2026-04-0" + i + "T00:00:00Z");
            attribution.recalculate(workspace, project, customerId);
        }

        String firstPage = mockMvc.perform(list(OWNER, null, 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].stripeCustomerId").value("cus_page_1"))
                .andExpect(jsonPath("$.entries[1].stripeCustomerId").value("cus_page_2"))
                .andExpect(jsonPath("$.nextCursor").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String cursor = new ObjectMapper().readTree(firstPage).get("nextCursor").asText();

        mockMvc.perform(list(OWNER, cursor, 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].stripeCustomerId").value("cus_page_3"))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void isolatesListingByWorkspaceAndProject() throws Exception {
        // customer_mrr_movements has no project_id (billing customers are workspace-scoped), so a
        // customer's *project* association only exists once something actually links it -- an
        // actively-linked customer (not merely one with a bare attribution result) is required here
        // to prove isolation, since an unlinked customer is deliberately visible workspace-wide
        // (see aNeverLinkedCustomerIsVisibleFromEveryProjectInTheWorkspaceUntilClaimed).
        movement("cus_in_scope", "2026-04-01T00:00:00Z");
        link("cus_in_scope");
        attribution.recalculate(workspace, project, "cus_in_scope");

        UUID otherProject = UUID.randomUUID();
        db.sql("INSERT INTO projects (id, workspace_id, name, domain, public_key) VALUES (:p, :w, 'p2', 'two.example', :k)")
                .param("p", otherProject).param("w", workspace).param("k", "pk-" + otherProject).update();
        movement("cus_in_other_project", "2026-04-01T00:00:00Z");
        insertBillingCustomer("cus_in_other_project");
        UUID otherIdentity = UUID.randomUUID();
        db.sql("INSERT INTO external_identities (id, workspace_id, project_id, external_user_id) VALUES (:i, :w, :p, 'user-in-other-project')")
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
        attribution.recalculate(workspace, otherProject, "cus_in_other_project");

        mockMvc.perform(get(
                                "/api/workspaces/{workspaceId}/projects/{projectId}/unattributed-revenue",
                                workspace,
                                otherProject)
                        .with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].stripeCustomerId").value("cus_in_other_project"));

        mockMvc.perform(list(OWNER, null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].stripeCustomerId").value("cus_in_scope"));
    }

    @Test
    void nonMemberCannotListAnotherWorkspacesInbox() throws Exception {
        movement("cus_1", "2026-04-01T00:00:00Z");
        attribution.recalculate(workspace, project, "cus_1");

        mockMvc.perform(list("user-not-a-member", null, null)).andExpect(status().isNotFound());
    }

    @Test
    void aSupersededCorrectedAwayHistoricalLinkIsNeverSurfacedAsASuggestion() throws Exception {
        // cus_with_history was once explicitly linked to identity "user_freed"; a later customer-side
        // correction reassigned it to "user_taken". "user_freed" is now unclaimed and is the sole
        // historical name for cus_with_history -- but that history was explicitly corrected away, so
        // it must never come back as a suggestion (the fix for the bug flagged in review: superseded
        // evidence is a correction record, not still-valid evidence).
        UUID identity = UUID.randomUUID();
        db.sql("INSERT INTO external_identities (id, workspace_id, project_id, external_user_id) VALUES (:i, :w, :p, 'user_freed')")
                .param("i", identity).param("w", workspace).param("p", project).update();
        UUID otherActiveIdentity = UUID.randomUUID();
        db.sql("INSERT INTO external_identities (id, workspace_id, project_id, external_user_id) VALUES (:i, :w, :p, 'user_taken')")
                .param("i", otherActiveIdentity).param("w", workspace).param("p", project).update();

        insertBillingCustomer("cus_with_history");
        insertBillingCustomer("cus_taken_elsewhere");
        UUID activeElsewhere = UUID.randomUUID();
        db.sql(
                        """
                        INSERT INTO stripe_customer_links
                            (id, workspace_id, project_id, external_identity_id, stripe_customer_id,
                             evidence_source, evidence_reference, linked_by_subject_id)
                        VALUES (:id, :w, :p, :i, 'cus_taken_elsewhere', 'EXPLICIT_API', 'seed', 'seed')
                        """)
                .param("id", activeElsewhere).param("w", workspace).param("p", project).param("i", otherActiveIdentity)
                .update();
        db.sql(
                        """
                        INSERT INTO stripe_customer_links
                            (id, workspace_id, project_id, external_identity_id, stripe_customer_id,
                             evidence_source, evidence_reference, linked_by_subject_id, superseded_at, superseded_by_id)
                        VALUES (:id, :w, :p, :i, 'cus_with_history', 'EXPLICIT_API', 'seed', 'seed', now(), :replacement)
                        """)
                .param("id", UUID.randomUUID()).param("w", workspace).param("p", project).param("i", identity)
                .param("replacement", activeElsewhere)
                .update();

        movement("cus_with_history", "2026-04-01T00:00:00Z");
        attribution.recalculate(workspace, project, "cus_with_history");

        mockMvc.perform(list(OWNER, null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].reason").value("NO_ACTIVE_LINK"))
                .andExpect(jsonPath("$.entries[0].suggestion").doesNotExist())
                .andExpect(jsonPath("$.entries[0].suggestionUnavailableReason").value("NO_DETERMINISTIC_REPAIR_AVAILABLE"));
    }

    @Test
    void noSuggestionYieldsTheDeterministicUnavailableReason() throws Exception {
        movement("cus_no_history", "2026-04-01T00:00:00Z");
        attribution.recalculate(workspace, project, "cus_no_history");

        mockMvc.perform(list(OWNER, null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].suggestion").doesNotExist())
                .andExpect(jsonPath("$.entries[0].suggestionUnavailableReason").value("NO_DETERMINISTIC_REPAIR_AVAILABLE"));
    }

    @Test
    void aNeverLinkedCustomerIsVisibleAsNoActiveLinkWithoutAnyPriorRecalculation() throws Exception {
        // No link, no attribution result -- ever. This is the core "why is this unattributed" case
        // (nobody has linked it yet), and must be discoverable without a manual recalculate() first.
        movement("cus_never_linked", "2026-04-01T00:00:00Z");

        mockMvc.perform(list(OWNER, null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].stripeCustomerId").value("cus_never_linked"))
                .andExpect(jsonPath("$.entries[0].reason").value("NO_ACTIVE_LINK"));
    }

    @Test
    void aNeverLinkedCustomerIsVisibleFromEveryProjectInTheWorkspaceUntilClaimed() throws Exception {
        UUID otherProject = UUID.randomUUID();
        db.sql("INSERT INTO projects (id, workspace_id, name, domain, public_key) VALUES (:p, :w, 'p2', 'two.example', :k)")
                .param("p", otherProject).param("w", workspace).param("k", "pk-" + otherProject).update();
        movement("cus_unclaimed", "2026-04-01T00:00:00Z");

        mockMvc.perform(list(OWNER, null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].stripeCustomerId").value("cus_unclaimed"));
        mockMvc.perform(get("/api/workspaces/{workspaceId}/projects/{projectId}/unattributed-revenue", workspace, otherProject)
                        .with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].stripeCustomerId").value("cus_unclaimed"));

        // Once claimed from one project, it stops showing up as "unclaimed" everywhere else: its
        // repaired project sees the STRONG-or-NO_ELIGIBLE_TOUCHPOINT truth, and every other project's
        // NOT EXISTS(active link) branch no longer matches it.
        link("cus_unclaimed");

        mockMvc.perform(get("/api/workspaces/{workspaceId}/projects/{projectId}/unattributed-revenue", workspace, otherProject)
                        .with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(0));
    }

    private void movement(String stripeCustomerId, String effectiveAt) {
        db.sql(
                        """
                        INSERT INTO customer_mrr_movements
                            (id, workspace_id, stripe_customer_id, currency, amount_minor, movement_type,
                             effective_at, calculation_version, source_billing_references)
                        VALUES (:id, :w, :c, 'USD', 1200, 'NEW', :at, 'mrr-v1', ARRAY['billing:test'])
                        """)
                .param("id", UUID.randomUUID())
                .param("w", workspace)
                .param("c", stripeCustomerId)
                .param("at", OffsetDateTime.parse(effectiveAt))
                .update();
    }

    private void link(String stripeCustomerId) {
        UUID identity = UUID.randomUUID();
        db.sql("INSERT INTO external_identities (id, workspace_id, project_id, external_user_id) VALUES (:i, :w, :p, :u)")
                .param("i", identity).param("w", workspace).param("p", project).param("u", "user-" + stripeCustomerId)
                .update();
        insertBillingCustomer(stripeCustomerId);
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

    private void insertBillingCustomer(String stripeCustomerId) {
        db.sql(
                        """
                        INSERT INTO billing_customers
                            (id, workspace_id, stripe_customer_id, provider_created_at, source, source_version, source_sequence)
                        VALUES (:id, :w, :c, now(), 'BACKFILL', 1, :c)
                        """)
                .param("id", UUID.randomUUID())
                .param("w", workspace)
                .param("c", stripeCustomerId)
                .update();
    }

    private void touchpoint(String stripeCustomerId, String at) {
        UUID identity = db.sql("SELECT external_identity_id FROM stripe_customer_links WHERE workspace_id = :w AND stripe_customer_id = :c")
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
                        "INSERT INTO touchpoints (id, workspace_id, project_id, visitor_id, session_id, occurred_at, landing_url, utm_source, created_at) "
                                + "VALUES (:id, :w, :p, :v, :s, :at, 'https://example.test/', 'google', :created)")
                .param("id", UUID.randomUUID()).param("w", workspace).param("p", project).param("v", visitor).param("s", session)
                .param("at", time).param("created", time.plusSeconds(1))
                .update();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder list(
            String actor, String cursor, Integer limit) {
        var request = get("/api/workspaces/{workspaceId}/projects/{projectId}/unattributed-revenue", workspace, project)
                .with(token(actor));
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
