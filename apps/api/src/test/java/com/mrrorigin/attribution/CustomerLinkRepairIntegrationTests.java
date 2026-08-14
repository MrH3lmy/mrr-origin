package com.mrrorigin.attribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

import tools.jackson.databind.ObjectMapper;

/**
 * #20: create-or-correct Stripe customer link repair, its audit trail, and bounded recalculation.
 * Exercises {@code POST .../unattributed-revenue/repairs} end to end (identity module's supersession
 * write, the audit log, and {@link AttributionApplicationService#recalculate}), since {@link
 * com.mrrorigin.identity.StripeCustomerLinkingService#repair} requires an authenticated request scope.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CustomerLinkRepairIntegrationTests {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:18-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    private static final String OWNER = "user-owner";
    private static final String VIEWER = "user-viewer";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcClient db;

    private UUID workspace;
    private UUID project;

    @BeforeEach
    void setUp() {
        db.sql("TRUNCATE workspaces CASCADE").update();
        workspace = createWorkspace(OWNER);
        project = createProject(workspace);
    }

    @Test
    void createsALinkWhenNeitherSideWasPreviouslyLinked() throws Exception {
        identify("user_1");
        insertBillingCustomer("cus_1");
        touchpoint("user_1", "2026-03-15T00:00:00Z");
        movement("cus_1", "2026-04-01T00:00:00Z");

        mockMvc.perform(repair(OWNER, "user_1", "cus_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionType").value("CREATED"))
                .andExpect(jsonPath("$.link.externalUserId").value("user_1"))
                .andExpect(jsonPath("$.link.stripeCustomerId").value("cus_1"))
                .andExpect(jsonPath("$.displacedCustomerId").doesNotExist())
                .andExpect(jsonPath("$.targetCustomerAttribution[0].confidence").value("STRONG"));

        assertAuditActionCounts("cus_1", Map.of("CREATED", 1));
    }

    @Test
    void repeatedIdenticalRepairIsIdempotent() throws Exception {
        identify("user_1");
        insertBillingCustomer("cus_1");
        movement("cus_1", "2026-04-01T00:00:00Z");

        mockMvc.perform(repair(OWNER, "user_1", "cus_1")).andExpect(status().isOk());
        mockMvc.perform(repair(OWNER, "user_1", "cus_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionType").value("UNCHANGED"));

        assertActiveLinkCount(1);
        assertAuditActionCounts("cus_1", Map.of("CREATED", 1));
    }

    @Test
    void correctingTheIdentitySideSupersedesTheOldLinkAndRecalculatesBothCustomers() throws Exception {
        identify("user_1");
        insertBillingCustomer("cus_old");
        insertBillingCustomer("cus_new");
        movement("cus_old", "2026-04-01T00:00:00Z");
        movement("cus_new", "2026-04-05T00:00:00Z");
        touchpoint("user_1", "2026-03-15T00:00:00Z");

        mockMvc.perform(repair(OWNER, "user_1", "cus_old")).andExpect(status().isOk());
        UUID firstLinkId = activeLinkId("cus_old");

        // cus_old had STRONG attribution before the correction.
        assertThat(resultConfidence("cus_old")).isEqualTo("STRONG");

        mockMvc.perform(repair(OWNER, "user_1", "cus_new"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionType").value("CORRECTED"))
                .andExpect(jsonPath("$.link.stripeCustomerId").value("cus_new"))
                .andExpect(jsonPath("$.displacedCustomerId").value("cus_old"))
                .andExpect(jsonPath("$.targetCustomerAttribution[0].confidence").value("STRONG"))
                .andExpect(jsonPath("$.displacedCustomerAttribution[0].confidence").value("UNATTRIBUTED"))
                .andExpect(jsonPath("$.displacedCustomerAttribution[0].unattributedReason").value("NO_ACTIVE_LINK"));

        // Old link is superseded, not deleted.
        Boolean superseded = db.sql("SELECT superseded_at IS NOT NULL FROM stripe_customer_links WHERE id = :id")
                .param("id", firstLinkId)
                .query(Boolean.class)
                .single();
        assertThat(superseded).isTrue();
        assertActiveLinkCount(1);
        assertThat(resultConfidence("cus_old")).isEqualTo("UNATTRIBUTED");
        assertThat(resultConfidence("cus_new")).isEqualTo("STRONG");
        assertAuditActionCounts("cus_old", Map.of("CREATED", 1));
        assertAuditActionCounts("cus_new", Map.of("CORRECTED", 1));
    }

    @Test
    void correctingTheCustomerSideSupersedesTheOtherIdentitysLink() throws Exception {
        identify("user_a");
        identify("user_b");
        insertBillingCustomer("cus_1");
        movement("cus_1", "2026-04-01T00:00:00Z");

        mockMvc.perform(repair(OWNER, "user_a", "cus_1")).andExpect(status().isOk());
        UUID firstLinkId = activeLinkId("cus_1");

        mockMvc.perform(repair(OWNER, "user_b", "cus_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionType").value("CORRECTED"))
                .andExpect(jsonPath("$.link.externalUserId").value("user_b"))
                // Same target customer as before, so no separate customer needs recalculation.
                .andExpect(jsonPath("$.displacedCustomerId").doesNotExist());

        Boolean superseded = db.sql("SELECT superseded_at IS NOT NULL FROM stripe_customer_links WHERE id = :id")
                .param("id", firstLinkId)
                .query(Boolean.class)
                .single();
        assertThat(superseded).isTrue();
        assertActiveLinkCount(1);
        assertAuditActionCounts("cus_1", Map.of("CREATED", 1, "CORRECTED", 1));
    }

    @Test
    void unrelatedCustomerAttributionIsUntouchedByAnotherCustomersRepair() throws Exception {
        identify("user_untouched");
        insertBillingCustomer("cus_untouched");
        movement("cus_untouched", "2026-04-01T00:00:00Z");
        touchpoint("user_untouched", "2026-03-15T00:00:00Z");
        mockMvc.perform(repair(OWNER, "user_untouched", "cus_untouched")).andExpect(status().isOk());

        OffsetDateTime calculatedBefore = resultCalculatedAt("cus_untouched");
        UUID firstTouchBefore = resultFirstTouchpoint("cus_untouched");

        identify("user_other");
        insertBillingCustomer("cus_other");
        movement("cus_other", "2026-04-02T00:00:00Z");
        mockMvc.perform(repair(OWNER, "user_other", "cus_other")).andExpect(status().isOk());

        assertThat(resultCalculatedAt("cus_untouched")).isEqualTo(calculatedBefore);
        assertThat(resultFirstTouchpoint("cus_untouched")).isEqualTo(firstTouchBefore);
    }

    @Test
    void modelVersionHistoryIsPreservedAcrossARepair() throws Exception {
        identify("user_1");
        insertBillingCustomer("cus_1");
        UUID movementId = movement("cus_1", "2026-04-01T00:00:00Z");

        UUID oldVersionResultId = UUID.randomUUID();
        db.sql(
                        """
                        INSERT INTO customer_attribution_results
                            (id, workspace_id, project_id, movement_id, acquisition_movement_id, model_version,
                             confidence, unattributed_reason, source_references, calculated_at)
                        VALUES (:id, :w, :p, :m, :m, 'attribution-v0', 'UNATTRIBUTED', 'NO_ACTIVE_LINK', ARRAY[]::text[], now())
                        """)
                .param("id", oldVersionResultId)
                .param("w", workspace)
                .param("p", project)
                .param("m", movementId)
                .update();

        mockMvc.perform(repair(OWNER, "user_1", "cus_1")).andExpect(status().isOk());

        String stillUnattributed = db.sql(
                        "SELECT unattributed_reason FROM customer_attribution_results WHERE id = :id")
                .param("id", oldVersionResultId)
                .query(String.class)
                .single();
        assertThat(stillUnattributed).isEqualTo("NO_ACTIVE_LINK");
    }

    @Test
    void auditHistoryAccumulatesAndIsNeverRewritten() throws Exception {
        identify("user_1");
        identify("user_2");
        insertBillingCustomer("cus_1");
        movement("cus_1", "2026-04-01T00:00:00Z");

        mockMvc.perform(repair(OWNER, "user_1", "cus_1")).andExpect(status().isOk());
        mockMvc.perform(repair(OWNER, "user_2", "cus_1")).andExpect(status().isOk());

        mockMvc.perform(auditHistory(OWNER, "cus_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].actionType").value("CORRECTED"))
                .andExpect(jsonPath("$[0].externalUserId").value("user_2"))
                .andExpect(jsonPath("$[1].actionType").value("CREATED"))
                .andExpect(jsonPath("$[1].externalUserId").value("user_1"));
    }

    @Test
    void repairingACustomerLinkedFromADifferentProjectIsRejected() throws Exception {
        UUID otherProject = createProject(workspace);
        identify(otherProject, "user_other_project");
        identify("user_1");
        insertBillingCustomer("cus_1");
        movement("cus_1", "2026-04-01T00:00:00Z");
        db.sql(
                        """
                        INSERT INTO stripe_customer_links
                            (id, workspace_id, project_id, external_identity_id, stripe_customer_id,
                             evidence_source, evidence_reference, linked_by_subject_id)
                        VALUES (:id, :w, :p, (SELECT id FROM external_identities WHERE project_id = :p AND external_user_id = 'user_other_project'),
                                'cus_1', 'EXPLICIT_API', 'seed', 'seed')
                        """)
                .param("id", UUID.randomUUID())
                .param("w", workspace)
                .param("p", otherProject)
                .update();

        mockMvc.perform(repair(OWNER, "user_1", "cus_1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("stripe_customer_linked_in_different_project"));
    }

    @Test
    void nonMemberCannotRepairOrReadAudit() throws Exception {
        identify("user_1");
        insertBillingCustomer("cus_1");

        mockMvc.perform(repair("user-not-a-member", "user_1", "cus_1")).andExpect(status().isNotFound());
        mockMvc.perform(auditHistory("user-not-a-member", "cus_1")).andExpect(status().isNotFound());
    }

    @Test
    void viewerCannotRepairButCanReadAudit() throws Exception {
        addMember(VIEWER, "VIEWER");
        identify("user_1");
        insertBillingCustomer("cus_1");
        movement("cus_1", "2026-04-01T00:00:00Z");
        mockMvc.perform(repair(OWNER, "user_1", "cus_1")).andExpect(status().isOk());

        mockMvc.perform(repair(VIEWER, "user_1", "cus_1")).andExpect(status().isForbidden());
        mockMvc.perform(auditHistory(VIEWER, "cus_1")).andExpect(status().isOk());
    }

    @Test
    void concurrentIdenticalRepairRequestsConvergeToExactlyOneActiveLink() throws Exception {
        identify("user_1");
        insertBillingCustomer("cus_1");
        movement("cus_1", "2026-04-01T00:00:00Z");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return mockMvc.perform(repair(OWNER, "user_1", "cus_1"))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                }));
            }
            ready.await();
            go.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            pool.shutdown();
        }

        assertActiveLinkCount(1);
    }

    private void assertActiveLinkCount(int expected) {
        int count = db.sql("SELECT COUNT(*) FROM stripe_customer_links WHERE workspace_id = :w AND superseded_at IS NULL")
                .param("w", workspace)
                .query(Integer.class)
                .single();
        assertThat(count).isEqualTo(expected);
    }

    private void assertAuditActionCounts(String stripeCustomerId, Map<String, Integer> expected) {
        List<String> actions = db.sql(
                        "SELECT action_type FROM stripe_customer_link_repair_audit_log WHERE workspace_id = :w AND stripe_customer_id = :c")
                .param("w", workspace)
                .param("c", stripeCustomerId)
                .query(String.class)
                .list();
        Map<String, Long> actual = actions.stream()
                .collect(java.util.stream.Collectors.groupingBy(a -> a, java.util.stream.Collectors.counting()));
        expected.forEach((action, count) -> assertThat(actual.getOrDefault(action, 0L)).isEqualTo(count.longValue()));
    }

    private UUID activeLinkId(String stripeCustomerId) {
        return db.sql(
                        "SELECT id FROM stripe_customer_links WHERE workspace_id = :w AND stripe_customer_id = :c AND superseded_at IS NULL")
                .param("w", workspace)
                .param("c", stripeCustomerId)
                .query(UUID.class)
                .single();
    }

    private String resultConfidence(String stripeCustomerId) {
        return db.sql(
                        """
                        SELECT r.confidence FROM customer_attribution_results r
                        JOIN customer_mrr_movements m ON m.workspace_id = r.workspace_id AND m.id = r.movement_id
                        WHERE r.workspace_id = :w AND m.stripe_customer_id = :c AND r.model_version = :v
                        """)
                .param("w", workspace)
                .param("c", stripeCustomerId)
                .param("v", AttributionV1Engine.MODEL_VERSION)
                .query(String.class)
                .single();
    }

    private OffsetDateTime resultCalculatedAt(String stripeCustomerId) {
        return db.sql(
                        """
                        SELECT r.calculated_at FROM customer_attribution_results r
                        JOIN customer_mrr_movements m ON m.workspace_id = r.workspace_id AND m.id = r.movement_id
                        WHERE r.workspace_id = :w AND m.stripe_customer_id = :c AND r.model_version = :v
                        """)
                .param("w", workspace)
                .param("c", stripeCustomerId)
                .param("v", AttributionV1Engine.MODEL_VERSION)
                .query(OffsetDateTime.class)
                .single();
    }

    private UUID resultFirstTouchpoint(String stripeCustomerId) {
        return db.sql(
                        """
                        SELECT r.first_touchpoint_id FROM customer_attribution_results r
                        JOIN customer_mrr_movements m ON m.workspace_id = r.workspace_id AND m.id = r.movement_id
                        WHERE r.workspace_id = :w AND m.stripe_customer_id = :c AND r.model_version = :v
                        """)
                .param("w", workspace)
                .param("c", stripeCustomerId)
                .param("v", AttributionV1Engine.MODEL_VERSION)
                .query(UUID.class)
                .single();
    }

    private UUID movement(String stripeCustomerId, String effectiveAt) {
        UUID id = UUID.randomUUID();
        db.sql(
                        """
                        INSERT INTO customer_mrr_movements
                            (id, workspace_id, stripe_customer_id, currency, amount_minor, movement_type,
                             effective_at, calculation_version, source_billing_references)
                        VALUES (:id, :w, :c, 'USD', 1500, 'NEW', :at, 'mrr-v1', ARRAY['billing:test'])
                        """)
                .param("id", id)
                .param("w", workspace)
                .param("c", stripeCustomerId)
                .param("at", OffsetDateTime.parse(effectiveAt))
                .update();
        return id;
    }

    private void identify(String externalUserId) {
        identify(project, externalUserId);
    }

    private void identify(UUID projectId, String externalUserId) {
        db.sql("INSERT INTO external_identities (id, workspace_id, project_id, external_user_id) VALUES (:id, :w, :p, :u)")
                .param("id", UUID.randomUUID())
                .param("w", workspace)
                .param("p", projectId)
                .param("u", externalUserId)
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

    private void touchpoint(String externalUserId, String at) {
        UUID identity = db.sql("SELECT id FROM external_identities WHERE workspace_id = :w AND project_id = :p AND external_user_id = :u")
                .param("w", workspace)
                .param("p", project)
                .param("u", externalUserId)
                .query(UUID.class)
                .single();
        UUID visitor = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        UUID touchpoint = UUID.randomUUID();
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
                .param("id", touchpoint).param("w", workspace).param("p", project).param("v", visitor).param("s", session)
                .param("at", time).param("created", time.plusSeconds(1))
                .update();
    }

    private UUID createWorkspace(String ownerSubject) {
        UUID workspaceId = UUID.randomUUID();
        db.sql("INSERT INTO workspaces (id, name, slug) VALUES (:id, :name, :slug)")
                .param("id", workspaceId)
                .param("name", "Workspace " + workspaceId)
                .param("slug", "workspace-" + workspaceId)
                .update();
        addMember(workspaceId, ownerSubject, "OWNER");
        return workspaceId;
    }

    private void addMember(String subject, String role) {
        addMember(workspace, subject, role);
    }

    private void addMember(UUID workspaceId, String subject, String role) {
        db.sql("INSERT INTO workspace_members (workspace_id, subject_id, role) VALUES (:w, :subject, :role)")
                .param("w", workspaceId)
                .param("subject", subject)
                .param("role", role)
                .update();
    }

    private UUID createProject(UUID workspaceId) {
        UUID projectId = UUID.randomUUID();
        db.sql(
                        """
                        INSERT INTO projects (id, workspace_id, name, domain, public_key, timezone)
                        VALUES (:id, :w, :name, :domain, :key, 'UTC')
                        """)
                .param("id", projectId)
                .param("w", workspaceId)
                .param("name", "Project " + projectId)
                .param("domain", "project-" + projectId + ".example.com")
                .param("key", "pk_" + projectId)
                .update();
        return projectId;
    }

    private MockHttpServletRequestBuilder repair(String actor, String externalUserId, String stripeCustomerId)
            throws Exception {
        return post("/api/workspaces/{workspaceId}/projects/{projectId}/unattributed-revenue/repairs", workspace, project)
                .with(token(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("externalUserId", externalUserId, "stripeCustomerId", stripeCustomerId)));
    }

    private MockHttpServletRequestBuilder auditHistory(String actor, String stripeCustomerId) {
        return get("/api/workspaces/{workspaceId}/projects/{projectId}/unattributed-revenue/repairs", workspace, project)
                .queryParam("stripeCustomerId", stripeCustomerId)
                .with(token(actor));
    }

    private RequestPostProcessor token(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject)
                .issuer("http://localhost:8081/realms/mrr-origin")
                .audience(List.of("mrr-origin-api")));
    }
}
