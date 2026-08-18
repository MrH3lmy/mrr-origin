package com.mrrorigin.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * #62's owner-only, resumable, cross-module workspace hard deletion: authorization (owner-only,
 * even while a deletion is already in progress), the explicit-confirmation requirement (slug match,
 * idempotent re-confirm), the write-rejection gate for every other mutation once {@code DELETING},
 * full end-to-end row removal across every module (including the FK-RESTRICT chains {@code
 * customer_attribution_results} and {@code stripe_customer_links} force through dependency-ordered
 * phases), ingestion-key revocation ahead of the table sweep, cross-tenant isolation, the deletion
 * tombstone's minimal non-PII contents, and the tombstone purge job's 30-day cutoff.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class WorkspaceDataDeletionIntegrationTests {

    private static final String OWNER = "user-owner";
    private static final String ADMIN = "user-admin";
    private static final String MEMBER = "user-member";
    private static final String OTHER = "user-other";

    private static final List<String> SEEDED_WORKSPACE_OWNED_TABLES = List.of(
            "visitors", "tracking_sessions", "touchpoints", "external_identities", "billing_customers",
            "stripe_customer_links", "customer_mrr_movements", "customer_attribution_results", "billing_invoices",
            "export_audit_log", "weekly_summary_deliveries", "project_ingestion_keys");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient db;

    @Autowired
    private WorkspaceDeletionTombstonePurgeJob purgeJob;

    @BeforeEach
    void clearTenantData() {
        db.sql("TRUNCATE TABLE workspaces, workspace_deletion_tombstones CASCADE").update();
    }

    @Test
    void onlyTheOwnerCanConfirmDeletion() throws Exception {
        UUID workspaceId = seedWorkspace("owner-only-workspace");
        addMember(workspaceId, ADMIN, "ADMIN");
        addMember(workspaceId, MEMBER, "MEMBER");

        confirm(workspaceId, "owner-only-workspace", ADMIN).andExpect(status().isForbidden());
        confirm(workspaceId, "owner-only-workspace", MEMBER).andExpect(status().isForbidden());
        confirm(workspaceId, "owner-only-workspace", OTHER).andExpect(status().isNotFound());
    }

    @Test
    void confirmationRequiresTheWorkspaceSlugAndIsIdempotent() throws Exception {
        UUID workspaceId = seedWorkspace("confirm-workspace");

        confirm(workspaceId, "wrong-slug", OWNER).andExpect(status().isBadRequest());

        MvcResult first = confirm(workspaceId, "confirm-workspace", OWNER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("REVOKE_INGESTION_KEYS"))
                .andExpect(jsonPath("$.complete").value(false))
                .andReturn();
        String requestId = json(first).get("requestId").asText();
        assertThat(workspaceStatus(workspaceId)).isEqualTo("DELETING");

        MvcResult second = confirm(workspaceId, "confirm-workspace", OWNER).andExpect(status().isOk()).andReturn();
        assertThat(json(second).get("requestId").asText()).isEqualTo(requestId);
    }

    @Test
    void runRequiresPriorConfirmationAndIsOwnerOnlyEvenWhileDeleting() throws Exception {
        UUID workspaceId = seedWorkspace("run-workspace");
        addMember(workspaceId, ADMIN, "ADMIN");

        run(workspaceId, OWNER).andExpect(status().isConflict());

        confirm(workspaceId, "run-workspace", OWNER).andExpect(status().isOk());

        // The workspace is now DELETING; an admin who could otherwise manage it is still not the owner.
        run(workspaceId, ADMIN).andExpect(status().isForbidden());
        run(workspaceId, OWNER).andExpect(status().isOk());
    }

    @Test
    void writesAreRejectedButReadsStillWorkOnceDeletingIsConfirmed() throws Exception {
        UUID workspaceId = seedWorkspace("write-block-workspace");
        confirm(workspaceId, "write-block-workspace", OWNER).andExpect(status().isOk());

        mockMvc.perform(get("/api/workspaces/{workspaceId}", workspaceId).with(token(OWNER)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/workspaces/{workspaceId}/projects", workspaceId)
                        .with(token(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("name", "Too Late", "domain", "too-late.example.com"))))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/workspaces/{workspaceId}/members", workspaceId)
                        .with(token(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("subjectId", OTHER, "role", "MEMBER"))))
                .andExpect(status().isConflict());
    }

    @Test
    void runningToCompletionHardDeletesEveryWorkspaceOwnedTableRevokesKeysWritesATombstoneAndNeverTouchesAnotherWorkspace()
            throws Exception {
        UUID workspaceId = seedWorkspace("full-deletion-workspace");
        UUID projectId = seedProject(workspaceId, "one.example.com");
        UUID otherWorkspaceId = seedWorkspace("untouched-workspace");
        UUID otherProjectId = seedProject(otherWorkspaceId, "untouched.example.com");

        seedFullFixture(workspaceId, projectId);
        seedFullFixture(otherWorkspaceId, otherProjectId);
        UUID keyId = issueIngestionKey(workspaceId, projectId);
        issueIngestionKey(otherWorkspaceId, otherProjectId);

        confirm(workspaceId, "full-deletion-workspace", OWNER).andExpect(status().isOk());

        // One batch in: ingestion keys are revoked immediately, before the table sweep ever deletes them.
        MvcResult afterFirstBatch = run(workspaceId, OWNER, 500).andExpect(status().isOk()).andReturn();
        assertThat(json(afterFirstBatch).get("phase").asText()).isEqualTo("DISABLE_STRIPE_SYNC");
        assertThat(revokedAt(keyId)).isNotNull();
        assertThat(rowCount("project_ingestion_keys", workspaceId)).isEqualTo(1);

        boolean complete = false;
        for (int i = 0; i < 60 && !complete; i++) {
            MvcResult result = run(workspaceId, OWNER, 500).andExpect(status().isOk()).andReturn();
            complete = json(result).get("complete").asBoolean();
        }
        assertThat(complete).as("deletion completed within a bounded number of batches").isTrue();

        for (String table : SEEDED_WORKSPACE_OWNED_TABLES) {
            assertThat(rowCount(table, workspaceId)).as("table %s for the deleted workspace", table).isZero();
        }
        assertThat(workspaceExists(workspaceId)).isFalse();
        assertThat(memberCount(workspaceId)).isZero();

        for (String table : SEEDED_WORKSPACE_OWNED_TABLES) {
            assertThat(rowCount(table, otherWorkspaceId)).as("table %s for the untouched workspace", table).isPositive();
        }
        assertThat(workspaceExists(otherWorkspaceId)).isTrue();

        Map<String, Object> tombstone = db.sql("SELECT * FROM workspace_deletion_tombstones WHERE workspace_id = :w")
                .param("w", workspaceId)
                .query()
                .singleRow();
        assertThat(tombstone.keySet())
                .containsExactlyInAnyOrder("request_id", "workspace_id", "status", "created_at", "completed_at");
        assertThat(tombstone.get("status")).isEqualTo("COMPLETED");
        assertThat(rowCount("workspace_data_deletion_runs", workspaceId)).isZero();
    }

    @Test
    void purgeJobRemovesOnlyTombstonesOlderThanThirtyDays() {
        UUID recentWorkspace = UUID.randomUUID();
        UUID oldWorkspace = UUID.randomUUID();
        insertTombstone(recentWorkspace, OffsetDateTime.now());
        insertTombstone(oldWorkspace, OffsetDateTime.now().minusDays(31));

        purgeJob.tick();

        assertThat(tombstoneExists(recentWorkspace)).isTrue();
        assertThat(tombstoneExists(oldWorkspace)).isFalse();
    }

    private UUID seedWorkspace(String slug) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO workspaces (id, name, slug) VALUES (:id, :slug, :slug)")
                .param("id", id).param("slug", slug).update();
        db.sql("INSERT INTO workspace_members (workspace_id, subject_id, role) VALUES (:w, :s, 'OWNER')")
                .param("w", id).param("s", OWNER).update();
        return id;
    }

    private void addMember(UUID workspaceId, String subject, String role) {
        db.sql("INSERT INTO workspace_members (workspace_id, subject_id, role) VALUES (:w, :s, :r)")
                .param("w", workspaceId).param("s", subject).param("r", role).update();
    }

    private UUID seedProject(UUID workspaceId, String domain) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO projects (id, workspace_id, name, domain, public_key) VALUES (:id, :w, 'p', :d, :k)")
                .param("id", id).param("w", workspaceId).param("d", domain).param("k", "pk-" + id).update();
        return id;
    }

    /** One row in each of {@link #SEEDED_WORKSPACE_OWNED_TABLES}, including the two FK-RESTRICT chains. */
    private void seedFullFixture(UUID workspaceId, UUID projectId) {
        OffsetDateTime now = OffsetDateTime.now();
        UUID visitorId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID touchpointId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        UUID linkId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        String stripeCustomerId = "cus_" + UUID.randomUUID();

        db.sql("""
                        INSERT INTO visitors (id, workspace_id, project_id, external_visitor_id, first_seen_at, last_seen_at)
                        VALUES (:id, :w, :p, :ext, :now, :now)
                        """)
                .param("id", visitorId).param("w", workspaceId).param("p", projectId)
                .param("ext", "visitor-" + visitorId).param("now", now).update();

        db.sql("""
                        INSERT INTO tracking_sessions (id, workspace_id, project_id, visitor_id, external_session_id, started_at)
                        VALUES (:id, :w, :p, :v, :ext, :now)
                        """)
                .param("id", sessionId).param("w", workspaceId).param("p", projectId).param("v", visitorId)
                .param("ext", "session-" + sessionId).param("now", now).update();

        db.sql("""
                        INSERT INTO touchpoints (id, workspace_id, project_id, visitor_id, session_id, occurred_at, landing_url)
                        VALUES (:id, :w, :p, :v, :s, :now, 'https://example.com/')
                        """)
                .param("id", touchpointId).param("w", workspaceId).param("p", projectId)
                .param("v", visitorId).param("s", sessionId).param("now", now).update();

        db.sql("""
                        INSERT INTO external_identities (id, workspace_id, project_id, external_user_id)
                        VALUES (:id, :w, :p, :ext)
                        """)
                .param("id", identityId).param("w", workspaceId).param("p", projectId)
                .param("ext", "user-" + identityId).update();

        db.sql("""
                        INSERT INTO billing_customers
                            (id, workspace_id, stripe_customer_id, provider_created_at, source, source_version, source_sequence)
                        VALUES (:id, :w, :c, :now, 'BACKFILL', 1, 's1')
                        """)
                .param("id", UUID.randomUUID()).param("w", workspaceId).param("c", stripeCustomerId).param("now", now)
                .update();

        db.sql("""
                        INSERT INTO stripe_customer_links
                            (id, workspace_id, project_id, external_identity_id, stripe_customer_id, evidence_source,
                             evidence_reference, linked_by_subject_id)
                        VALUES (:id, :w, :p, :identity, :c, 'EXPLICIT_API', 'ref', :subject)
                        """)
                .param("id", linkId).param("w", workspaceId).param("p", projectId).param("identity", identityId)
                .param("c", stripeCustomerId).param("subject", OWNER).update();

        db.sql("""
                        INSERT INTO customer_mrr_movements
                            (id, workspace_id, stripe_customer_id, currency, amount_minor, movement_type, effective_at,
                             calculation_version, source_billing_references)
                        VALUES (:id, :w, :c, 'USD', 1000, 'NEW', :now, 'v1', ARRAY['billing:test'])
                        """)
                .param("id", movementId).param("w", workspaceId).param("c", stripeCustomerId).param("now", now).update();

        db.sql("""
                        INSERT INTO customer_attribution_results
                            (id, workspace_id, project_id, movement_id, acquisition_movement_id, model_version,
                             first_touchpoint_id, last_touchpoint_id, customer_link_evidence_id, confidence,
                             source_references, calculated_at)
                        VALUES
                            (:id, :w, :p, :m, :m, 'v1', :tp, :tp, :link, 'STRONG', ARRAY['tp:test'], :now)
                        """)
                .param("id", UUID.randomUUID()).param("w", workspaceId).param("p", projectId).param("m", movementId)
                .param("tp", touchpointId).param("link", linkId).param("now", now).update();

        db.sql("""
                        INSERT INTO billing_invoices
                            (id, workspace_id, stripe_invoice_id, stripe_customer_id, status, currency, amount_due,
                             amount_paid, amount_remaining, provider_created_at, source, source_version, source_sequence)
                        VALUES (:id, :w, :inv, :c, 'paid', 'USD', 1000, 1000, 0, :now, 'BACKFILL', 1, 's1')
                        """)
                .param("id", UUID.randomUUID()).param("w", workspaceId).param("inv", "in_" + UUID.randomUUID())
                .param("c", stripeCustomerId).param("now", now).update();

        db.sql("""
                        INSERT INTO export_audit_log
                            (id, workspace_id, project_id, export_type, schema_version, actor_subject_id, filters, row_count)
                        VALUES (:id, :w, :p, 'CUSTOMERS', 'customers-v1', :actor, '{}'::jsonb, 0)
                        """)
                .param("id", UUID.randomUUID()).param("w", workspaceId).param("p", projectId).param("actor", OWNER)
                .update();

        db.sql("""
                        INSERT INTO weekly_summary_deliveries
                            (id, workspace_id, project_id, recipient_subject_id, recipient_email, week_start, next_attempt_at)
                        VALUES (:id, :w, :p, :subject, :email, :week, :now)
                        """)
                .param("id", UUID.randomUUID()).param("w", workspaceId).param("p", projectId).param("subject", OWNER)
                .param("email", "owner@example.com").param("week", LocalDate.now()).param("now", now).update();
    }

    private UUID issueIngestionKey(UUID workspaceId, UUID projectId) throws Exception {
        MvcResult result = mockMvc.perform(post(
                                "/api/workspaces/{workspaceId}/projects/{projectId}/tracking/ingestion-key",
                                workspaceId, projectId)
                        .with(token(OWNER)))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(json(result).get("id").asText());
    }

    private void insertTombstone(UUID workspaceId, OffsetDateTime createdAt) {
        db.sql("""
                        INSERT INTO workspace_deletion_tombstones (request_id, workspace_id, status, created_at, completed_at)
                        VALUES (:id, :w, 'COMPLETED', :createdAt, :createdAt)
                        """)
                .param("id", UUID.randomUUID()).param("w", workspaceId).param("createdAt", createdAt).update();
    }

    private boolean tombstoneExists(UUID workspaceId) {
        return rowCount("workspace_deletion_tombstones", workspaceId) > 0;
    }

    private boolean workspaceExists(UUID workspaceId) {
        return db.sql("SELECT COUNT(*) FROM workspaces WHERE id = :id").param("id", workspaceId).query(Integer.class).single()
                > 0;
    }

    private String workspaceStatus(UUID workspaceId) {
        return db.sql("SELECT status FROM workspaces WHERE id = :id").param("id", workspaceId).query(String.class).single();
    }

    private OffsetDateTime revokedAt(UUID keyId) {
        return db.sql("SELECT revoked_at FROM project_ingestion_keys WHERE id = :id")
                .param("id", keyId)
                .query(OffsetDateTime.class)
                .single();
    }

    private int rowCount(String table, UUID workspaceId) {
        return db.sql("SELECT COUNT(*) FROM " + table + " WHERE workspace_id = :w")
                .param("w", workspaceId)
                .query(Integer.class)
                .single();
    }

    private int memberCount(UUID workspaceId) {
        return db.sql("SELECT COUNT(*) FROM workspace_members WHERE workspace_id = :w")
                .param("w", workspaceId)
                .query(Integer.class)
                .single();
    }

    private ResultActions confirm(UUID workspaceId, String confirmationSlug, String subject) throws Exception {
        return mockMvc.perform(post("/api/workspaces/{workspaceId}/deletion/confirm", workspaceId)
                .with(token(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("confirmationSlug", confirmationSlug))));
    }

    private ResultActions run(UUID workspaceId, String subject) throws Exception {
        return mockMvc.perform(post("/api/workspaces/{workspaceId}/deletion/run", workspaceId).with(token(subject)));
    }

    private ResultActions run(UUID workspaceId, String subject, int maxRows) throws Exception {
        return mockMvc.perform(post("/api/workspaces/{workspaceId}/deletion/run", workspaceId)
                .with(token(subject))
                .param("maxRows", String.valueOf(maxRows)));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private RequestPostProcessor token(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject)
                .issuer("http://localhost:8081/realms/mrr-origin")
                .audience(List.of("mrr-origin-api")));
    }
}
