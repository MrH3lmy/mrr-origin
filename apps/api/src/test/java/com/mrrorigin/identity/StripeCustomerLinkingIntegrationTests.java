package com.mrrorigin.identity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * #16: explicit server-side Stripe-customer-to-tracked-external-user linking. Covers idempotent
 * relinking, both conflict directions, guessed/nonexistent IDs, structural cross-tenant isolation,
 * and a concurrent-identical-request race.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class StripeCustomerLinkingIntegrationTests {

    private static final String OWNER = "user-owner";
    private static final String OTHER_OWNER = "user-other-owner";
    private static final String VIEWER = "user-viewer";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    private JdbcClient jdbc;

    @Autowired
    void setJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @BeforeEach
    void clearTenantData() {
        new JdbcTemplate(dataSource)
                .execute(
                        "TRUNCATE TABLE stripe_customer_links, external_identities, visitor_aliases, visitors, "
                                + "billing_customers, projects, workspace_members, workspaces CASCADE");
    }

    @Test
    void linkingIsIdempotentForRepeatedIdenticalRequests() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);
        identify(workspaceId, projectId, "user_1");
        insertBillingCustomer(workspaceId, "cus_1");

        mockMvc.perform(link(workspaceId, projectId, OWNER, "user_1", "cus_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidenceSource").value("EXPLICIT_API"));

        mockMvc.perform(link(workspaceId, projectId, OWNER, "user_1", "cus_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalUserId").value("user_1"))
                .andExpect(jsonPath("$.stripeCustomerId").value("cus_1"));

        assertActiveLinkCount(workspaceId, 1);
    }

    @Test
    void reassigningAnAlreadyLinkedExternalUserToADifferentCustomerConflicts() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);
        identify(workspaceId, projectId, "user_1");
        insertBillingCustomer(workspaceId, "cus_1");
        insertBillingCustomer(workspaceId, "cus_2");

        mockMvc.perform(link(workspaceId, projectId, OWNER, "user_1", "cus_1")).andExpect(status().isOk());

        mockMvc.perform(link(workspaceId, projectId, OWNER, "user_1", "cus_2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("external_user_already_linked"));

        assertActiveLinkCount(workspaceId, 1);
    }

    @Test
    void claimingAnAlreadyLinkedCustomerForADifferentExternalUserConflicts() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);
        identify(workspaceId, projectId, "user_1");
        identify(workspaceId, projectId, "user_2");
        insertBillingCustomer(workspaceId, "cus_1");

        mockMvc.perform(link(workspaceId, projectId, OWNER, "user_1", "cus_1")).andExpect(status().isOk());

        mockMvc.perform(link(workspaceId, projectId, OWNER, "user_2", "cus_1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("stripe_customer_already_linked"));

        assertActiveLinkCount(workspaceId, 1);
    }

    @Test
    void guessedExternalUserIdIsRejectedWithoutLeakingWhetherItExistsElsewhere() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);
        insertBillingCustomer(workspaceId, "cus_1");

        mockMvc.perform(link(workspaceId, projectId, OWNER, "user_never_identified", "cus_1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("external_user_not_tracked"));
    }

    @Test
    void guessedStripeCustomerIdIsRejectedWhenNeverObservedInTheLedger() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);
        identify(workspaceId, projectId, "user_1");

        mockMvc.perform(link(workspaceId, projectId, OWNER, "user_1", "cus_never_seen"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("stripe_customer_not_found"));
    }

    @Test
    void anotherWorkspacesTrackedExternalUserCannotBeGuessedIntoThisWorkspace() throws Exception {
        UUID workspaceA = createWorkspace(OWNER);
        UUID projectA = createProject(workspaceA);
        UUID workspaceB = createWorkspace(OWNER);
        UUID projectB = createProject(workspaceB);
        identify(workspaceB, projectB, "user_in_b");
        insertBillingCustomer(workspaceA, "cus_a");

        // Same actor owns both workspaces, but user_in_b was identified under workspace B/project B.
        mockMvc.perform(link(workspaceA, projectA, OWNER, "user_in_b", "cus_a"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("external_user_not_tracked"));
    }

    @Test
    void anotherWorkspacesStripeCustomerCannotBeGuessedIntoThisWorkspace() throws Exception {
        UUID workspaceA = createWorkspace(OWNER);
        UUID projectA = createProject(workspaceA);
        UUID workspaceB = createWorkspace(OWNER);
        identify(workspaceA, projectA, "user_1");
        insertBillingCustomer(workspaceB, "cus_in_b");

        mockMvc.perform(link(workspaceA, projectA, OWNER, "user_1", "cus_in_b"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("stripe_customer_not_found"));
    }

    @Test
    void aProjectFromAnotherWorkspaceIsNotFoundEvenWithAValidStripeCustomer() throws Exception {
        UUID workspaceA = createWorkspace(OWNER);
        UUID workspaceB = createWorkspace(OWNER);
        UUID projectB = createProject(workspaceB);
        identify(workspaceB, projectB, "user_1");
        insertBillingCustomer(workspaceA, "cus_a");

        mockMvc.perform(link(workspaceA, projectB, OWNER, "user_1", "cus_a"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("project_not_found"));
    }

    @Test
    void nonMemberCannotLinkOrReadAnotherWorkspacesData() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);
        identify(workspaceId, projectId, "user_1");
        insertBillingCustomer(workspaceId, "cus_1");

        mockMvc.perform(link(workspaceId, projectId, OTHER_OWNER, "user_1", "cus_1"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get(
                                "/api/workspaces/{workspaceId}/projects/{projectId}/stripe-customer-links",
                                workspaceId, projectId)
                        .queryParam("externalUserId", "user_1")
                        .with(token(OTHER_OWNER)))
                .andExpect(status().isNotFound());
    }

    @Test
    void memberWithoutManagePermissionCannotLink() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        addMember(workspaceId, VIEWER, "VIEWER");
        UUID projectId = createProject(workspaceId);
        identify(workspaceId, projectId, "user_1");
        insertBillingCustomer(workspaceId, "cus_1");

        mockMvc.perform(link(workspaceId, projectId, VIEWER, "user_1", "cus_1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void activeLinkCanBeReadAfterCreation() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);
        identify(workspaceId, projectId, "user_1");
        insertBillingCustomer(workspaceId, "cus_1");
        mockMvc.perform(link(workspaceId, projectId, OWNER, "user_1", "cus_1")).andExpect(status().isOk());

        mockMvc.perform(get(
                                "/api/workspaces/{workspaceId}/projects/{projectId}/stripe-customer-links",
                                workspaceId, projectId)
                        .queryParam("externalUserId", "user_1")
                        .with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stripeCustomerId").value("cus_1"))
                .andExpect(jsonPath("$.linkedBySubjectId").value(OWNER));
    }

    @Test
    void queryParameterSupportsPathLikeExternalUserIds() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);
        String externalUserId = "auth0/user|123";
        identify(workspaceId, projectId, externalUserId);
        insertBillingCustomer(workspaceId, "cus_path");
        mockMvc.perform(link(workspaceId, projectId, OWNER, externalUserId, "cus_path"))
                .andExpect(status().isOk());

        mockMvc.perform(get(
                                "/api/workspaces/{workspaceId}/projects/{projectId}/stripe-customer-links",
                                workspaceId, projectId)
                        .queryParam("externalUserId", externalUserId)
                        .with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalUserId").value(externalUserId));
    }

    @Test
    void supersessionReferenceCannotCrossWorkspaceOrOmitItsTimestamp() throws Exception {
        UUID workspaceA = createWorkspace(OWNER);
        UUID projectA = createProject(workspaceA);
        identify(workspaceA, projectA, "user_a");
        insertBillingCustomer(workspaceA, "cus_a");
        mockMvc.perform(link(workspaceA, projectA, OWNER, "user_a", "cus_a")).andExpect(status().isOk());

        UUID workspaceB = createWorkspace(OWNER);
        UUID projectB = createProject(workspaceB);
        identify(workspaceB, projectB, "user_b");
        insertBillingCustomer(workspaceB, "cus_b");
        mockMvc.perform(link(workspaceB, projectB, OWNER, "user_b", "cus_b")).andExpect(status().isOk());

        UUID linkA = activeLinkId(workspaceA);
        UUID linkB = activeLinkId(workspaceB);

        assertThatThrownBy(() -> jdbc.sql(
                                "UPDATE stripe_customer_links SET superseded_at = CURRENT_TIMESTAMP, "
                                        + "superseded_by_id = :replacement WHERE id = :id")
                        .param("replacement", linkB)
                        .param("id", linkA)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.sql(
                                "UPDATE stripe_customer_links SET superseded_at = CURRENT_TIMESTAMP WHERE id = :id")
                        .param("id", linkA)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentIdenticalLinkRequestsConvergeToExactlyOneRow() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);
        identify(workspaceId, projectId, "user_1");
        insertBillingCustomer(workspaceId, "cus_1");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return mockMvc.perform(link(workspaceId, projectId, OWNER, "user_1", "cus_1"))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                }));
            }
            ready.await();
            go.countDown();
            for (Future<Integer> result : results) {
                int status = result.get(10, TimeUnit.SECONDS);
                if (status != 200) {
                    throw new AssertionError("Expected both concurrent identical requests to succeed, got " + status);
                }
            }
        } finally {
            pool.shutdown();
        }

        assertActiveLinkCount(workspaceId, 1);
    }

    private UUID activeLinkId(UUID workspaceId) {
        return jdbc.sql(
                        "SELECT id FROM stripe_customer_links WHERE workspace_id = :workspaceId AND superseded_at IS NULL")
                .param("workspaceId", workspaceId)
                .query(UUID.class)
                .single();
    }

    private void assertActiveLinkCount(UUID workspaceId, int expected) {
        int count = jdbc.sql(
                        "SELECT COUNT(*) FROM stripe_customer_links WHERE workspace_id = :workspaceId AND superseded_at IS NULL")
                .param("workspaceId", workspaceId)
                .query(Integer.class)
                .single();
        if (count != expected) {
            throw new AssertionError("Expected " + expected + " active link(s), found " + count);
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder link(
            UUID workspaceId, UUID projectId, String actor, String externalUserId, String stripeCustomerId)
            throws Exception {
        return post(
                        "/api/workspaces/{workspaceId}/projects/{projectId}/stripe-customer-links",
                        workspaceId,
                        projectId)
                .with(token(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content(new tools.jackson.databind.ObjectMapper()
                        .writeValueAsString(Map.of("externalUserId", externalUserId, "stripeCustomerId", stripeCustomerId)));
    }

    private UUID createWorkspace(String ownerSubject) {
        UUID workspaceId = UUID.randomUUID();
        jdbc.sql("INSERT INTO workspaces (id, name, slug) VALUES (:id, :name, :slug)")
                .param("id", workspaceId)
                .param("name", "Workspace " + workspaceId)
                .param("slug", "workspace-" + workspaceId)
                .update();
        jdbc.sql("INSERT INTO workspace_members (workspace_id, subject_id, role) VALUES (:workspaceId, :subject, 'OWNER')")
                .param("workspaceId", workspaceId)
                .param("subject", ownerSubject)
                .update();
        return workspaceId;
    }

    private void addMember(UUID workspaceId, String subject, String role) {
        jdbc.sql("INSERT INTO workspace_members (workspace_id, subject_id, role) VALUES (:workspaceId, :subject, :role)")
                .param("workspaceId", workspaceId)
                .param("subject", subject)
                .param("role", role)
                .update();
    }

    private UUID createProject(UUID workspaceId) {
        UUID projectId = UUID.randomUUID();
        jdbc.sql(
                        """
                        INSERT INTO projects (id, workspace_id, name, domain, public_key, timezone)
                        VALUES (:id, :workspaceId, :name, :domain, :publicKey, 'UTC')
                        """)
                .param("id", projectId)
                .param("workspaceId", workspaceId)
                .param("name", "Project " + projectId)
                .param("domain", "project-" + projectId + ".example.com")
                .param("publicKey", "pk_" + projectId)
                .update();
        return projectId;
    }

    private void identify(UUID workspaceId, UUID projectId, String externalUserId) {
        jdbc.sql(
                        """
                        INSERT INTO external_identities (id, workspace_id, project_id, external_user_id)
                        VALUES (:id, :workspaceId, :projectId, :externalUserId)
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("externalUserId", externalUserId)
                .update();
    }

    private void insertBillingCustomer(UUID workspaceId, String stripeCustomerId) {
        jdbc.sql(
                        """
                        INSERT INTO billing_customers
                            (id, workspace_id, stripe_customer_id, provider_created_at, source, source_version, source_sequence)
                        VALUES (:id, :workspaceId, :stripeCustomerId, :now, 'BACKFILL', 1, 'test')
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("stripeCustomerId", stripeCustomerId)
                .param("now", OffsetDateTime.now())
                .update();
    }

    private RequestPostProcessor token(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject)
                .issuer("http://localhost:8081/realms/mrr-origin")
                .audience(List.of("mrr-origin-api")));
    }
}
