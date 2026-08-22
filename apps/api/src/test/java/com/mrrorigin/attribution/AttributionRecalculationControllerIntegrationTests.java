package com.mrrorigin.attribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * #84's operator-facing HTTP surface over {@link AttributionRecalculationService}. Bounded-batch
 * resumability, idempotency, restart semantics, and cross-tenant isolation at the service level are
 * already fully proven by {@link AttributionRecalculationServiceIntegrationTests} (including its own
 * {@code concurrentBatchRunsForTheSameScopeSerializeInsteadOfDuplicatingWork}); this class instead
 * proves the HTTP layer wires those exact guarantees through correctly: authorization (manager-only,
 * non-member concealment), tenant-scoped project validation, request-shape (bounded {@code
 * maxCustomers}), and response-shape.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AttributionRecalculationControllerIntegrationTests {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:18-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    private static final String OWNER = "user-owner";
    private static final String VIEWER = "user-viewer";
    private static final String STRANGER = "user-not-a-member";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcClient db;
    @Autowired private AttributionRecalculationService recalculation;

    private UUID workspace;
    private UUID project;

    @BeforeEach
    void setUp() {
        db.sql("TRUNCATE workspaces CASCADE").update();
        workspace = createWorkspace(OWNER);
        project = createProject(workspace);
    }

    @Test
    void managerCanInspectNotStartedStatus() throws Exception {
        mockMvc.perform(get(statusPath(workspace, project)).with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.cursorCustomerId").doesNotExist())
                .andExpect(jsonPath("$.customersProcessed").value(0))
                .andExpect(jsonPath("$.complete").value(false));
    }

    @Test
    void nonManagerMemberCannotInspectOrTriggerRecalculation() throws Exception {
        addMember(VIEWER, "VIEWER");

        mockMvc.perform(get(statusPath(workspace, project)).with(token(VIEWER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(resumePath(workspace, project)).with(token(VIEWER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(restartPath(workspace, project)).with(token(VIEWER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void nonMemberWorkspaceAccessIsConcealedAsNotFound() throws Exception {
        mockMvc.perform(get(statusPath(workspace, project)).with(token(STRANGER)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(resumePath(workspace, project)).with(token(STRANGER)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(restartPath(workspace, project)).with(token(STRANGER)))
                .andExpect(status().isNotFound());
    }

    @Test
    void projectBelongingToAnotherWorkspaceCannotBeAccessedThroughThisWorkspace() throws Exception {
        UUID otherWorkspace = createWorkspace(OWNER);
        UUID otherProject = createProject(otherWorkspace);

        mockMvc.perform(get(statusPath(workspace, otherProject)).with(token(OWNER)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(resumePath(workspace, otherProject)).with(token(OWNER)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(restartPath(workspace, otherProject)).with(token(OWNER)))
                .andExpect(status().isNotFound());

        // Nothing about the foreign project changed as a side effect of the denied requests.
        assertThat(recalculation.status(otherWorkspace, otherProject)).isEmpty();
    }

    @Test
    void managerResumeCreatesRunAndProcessesAtMostTheBoundedCount() throws Exception {
        for (int i = 0; i < 5; i++) customer("cus-" + i);

        mockMvc.perform(post(resumePath(workspace, project)).param("maxCustomers", "2").with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customersProcessedThisBatch").value(2))
                .andExpect(jsonPath("$.totalCustomersProcessed").value(2))
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.cursorCustomerId").value("cus-1"));
    }

    @Test
    void repeatedResumeCallsAdvanceFromTheDurableCheckpoint() throws Exception {
        for (int i = 0; i < 5; i++) customer("cus-" + i);

        mockMvc.perform(post(resumePath(workspace, project)).param("maxCustomers", "2").with(token(OWNER)))
                .andExpect(jsonPath("$.cursorCustomerId").value("cus-1"))
                .andExpect(jsonPath("$.totalCustomersProcessed").value(2));
        mockMvc.perform(post(resumePath(workspace, project)).param("maxCustomers", "2").with(token(OWNER)))
                .andExpect(jsonPath("$.customersProcessedThisBatch").value(2))
                .andExpect(jsonPath("$.cursorCustomerId").value("cus-3"))
                .andExpect(jsonPath("$.totalCustomersProcessed").value(4));

        mockMvc.perform(get(statusPath(workspace, project)).with(token(OWNER)))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.customersProcessed").value(4));
    }

    @Test
    void interruptedMultipleBoundedCallsEventuallyReachComplete() throws Exception {
        for (int i = 0; i < 5; i++) customer("cus-" + i);

        int calls = 0;
        boolean complete = false;
        while (!complete) {
            calls++;
            if (calls > 20) throw new AssertionError("Did not converge within a bounded number of batches");
            String body = mockMvc.perform(post(resumePath(workspace, project)).param("maxCustomers", "2").with(token(OWNER)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            complete = body.contains("\"complete\":true");
        }
        assertThat(calls).isGreaterThan(1);

        mockMvc.perform(get(statusPath(workspace, project)).with(token(OWNER)))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.customersProcessed").value(5));
        assertThat(countResults()).isEqualTo(5);
    }

    @Test
    void resumeAfterCompletedIsADeterministicNoOp() throws Exception {
        customer("cus-a");
        customer("cus-b");
        runToCompletion(10);

        mockMvc.perform(post(resumePath(workspace, project)).with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customersProcessedThisBatch").value(0))
                .andExpect(jsonPath("$.totalCustomersProcessed").value(2))
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(countResults()).isEqualTo(2);
    }

    @Test
    void restartOfCompletedResetsAndAllowsAFreshSweep() throws Exception {
        customer("cus-a");
        customer("cus-b");
        runToCompletion(10);

        mockMvc.perform(post(restartPath(workspace, project)).with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.cursorCustomerId").doesNotExist())
                .andExpect(jsonPath("$.customersProcessed").value(0))
                .andExpect(jsonPath("$.complete").value(false));

        mockMvc.perform(post(resumePath(workspace, project)).param("maxCustomers", "10").with(token(OWNER)))
                .andExpect(jsonPath("$.customersProcessedThisBatch").value(2))
                .andExpect(jsonPath("$.complete").value(true));
        assertThat(countResults()).isEqualTo(2); // upserts overwrite in place, never duplicate
    }

    @Test
    void restartWhileRunningIsRejectedAndLeavesCheckpointAndOutputUntouched() throws Exception {
        for (int i = 0; i < 5; i++) customer("cus-" + i);
        mockMvc.perform(post(resumePath(workspace, project)).param("maxCustomers", "2").with(token(OWNER)))
                .andExpect(jsonPath("$.complete").value(false));
        var before = recalculation.status(workspace, project).orElseThrow();
        long resultsBefore = countResults();

        mockMvc.perform(post(restartPath(workspace, project)).with(token(OWNER)))
                .andExpect(status().isConflict());

        var after = recalculation.status(workspace, project).orElseThrow();
        assertThat(after.status()).isEqualTo(before.status());
        assertThat(after.cursor()).isEqualTo(before.cursor());
        assertThat(after.processed()).isEqualTo(before.processed());
        assertThat(countResults()).isEqualTo(resultsBefore);
    }

    @Test
    void restartWithNoExistingRunIsRejectedClearly() throws Exception {
        mockMvc.perform(post(restartPath(workspace, project)).with(token(OWNER)))
                .andExpect(status().isConflict());
    }

    @Test
    void repeatedRetriedResumeDoesNotDuplicateAttributionResults() throws Exception {
        customer("cus-a");
        customer("cus-b");

        mockMvc.perform(post(resumePath(workspace, project)).param("maxCustomers", "10").with(token(OWNER)))
                .andExpect(jsonPath("$.complete").value(true));
        List<String> firstRows = resultSnapshot();
        assertThat(countResults()).isEqualTo(2);

        // A retried call against the same, already-advanced checkpoint must not reprocess or duplicate.
        mockMvc.perform(post(resumePath(workspace, project)).param("maxCustomers", "10").with(token(OWNER)))
                .andExpect(jsonPath("$.customersProcessedThisBatch").value(0));
        assertThat(countResults()).isEqualTo(2);
        assertThat(resultSnapshot()).isEqualTo(firstRows);
    }

    @Test
    void concurrentResumeRequestsForTheSameWorkspaceProjectRemainSafe() throws Exception {
        int total = 8;
        for (int i = 0; i < total; i++) customer("cus-" + i);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Void>> futures = List.of(
                    pool.submit(() -> { ready.countDown(); go.await(); driveToCompletion(); return null; }),
                    pool.submit(() -> { ready.countDown(); go.await(); driveToCompletion(); return null; }));
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
            for (Future<Void> future : futures) future.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
        }

        assertThat(countResults()).isEqualTo(total);
        assertThat(recalculation.status(workspace, project)).get()
                .satisfies(run -> assertThat(run.processed()).isEqualTo(total));
    }

    private void driveToCompletion() throws Exception {
        boolean complete = false;
        int guard = 0;
        while (!complete) {
            guard++;
            if (guard > 50) throw new AssertionError("Did not converge");
            String body = mockMvc.perform(post(resumePath(workspace, project)).param("maxCustomers", "3").with(token(OWNER)))
                    .andReturn().getResponse().getContentAsString();
            complete = body.contains("\"complete\":true");
        }
    }

    @Test
    void sameCustomerIdsReusedAcrossTwoWorkspacesStayIsolated() throws Exception {
        UUID otherWorkspace = createWorkspace(OWNER);
        UUID otherProject = createProject(otherWorkspace);
        customer("cus-shared", workspace, project);
        customer("cus-shared", otherWorkspace, otherProject);

        mockMvc.perform(post(resumePath(workspace, project)).param("maxCustomers", "10").with(token(OWNER)))
                .andExpect(jsonPath("$.customersProcessedThisBatch").value(1));

        assertThat(recalculation.status(otherWorkspace, otherProject)).isEmpty();
        assertThat(db.sql("SELECT count(*) FROM customer_attribution_results WHERE workspace_id=:w")
                .param("w", otherWorkspace).query(Long.class).single()).isZero();
        assertThat(db.sql("SELECT count(*) FROM customer_attribution_results WHERE workspace_id=:w")
                .param("w", workspace).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void mutatingOperationsAreRejectedWhileWorkspaceIsDeleting() throws Exception {
        customer("cus-a");
        db.sql("UPDATE workspaces SET status = 'DELETING' WHERE id = :w").param("w", workspace).update();

        mockMvc.perform(post(resumePath(workspace, project)).with(token(OWNER)))
                .andExpect(status().isConflict());
        mockMvc.perform(post(restartPath(workspace, project)).with(token(OWNER)))
                .andExpect(status().isConflict());
    }

    @Test
    void maxCustomersOutOfBoundsIsRejected() throws Exception {
        mockMvc.perform(post(resumePath(workspace, project)).param("maxCustomers", "0").with(token(OWNER)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(resumePath(workspace, project)).param("maxCustomers", "501").with(token(OWNER)))
                .andExpect(status().isBadRequest());
    }

    private void runToCompletion(int maxCustomers) {
        AttributionRecalculationService.BatchOutcome outcome;
        int guard = 0;
        do {
            outcome = recalculation.runBatch(workspace, project, maxCustomers);
            guard++;
            if (guard > 200) throw new AssertionError("Did not converge");
        } while (!outcome.complete());
    }

    private long countResults() {
        return db.sql("SELECT count(*) FROM customer_attribution_results").query(Long.class).single();
    }

    private List<String> resultSnapshot() {
        return db.sql("SELECT movement_id,model_version,confidence,unattributed_reason FROM customer_attribution_results ORDER BY movement_id")
                .query((r, n) -> r.getObject(1) + "|" + r.getString(2) + "|" + r.getString(3) + "|" + r.getString(4)).list();
    }

    private void customer(String customerId) {
        customer(customerId, workspace, project);
    }

    private void customer(String customerId, UUID workspaceId, UUID projectId) {
        UUID movement = UUID.randomUUID();
        db.sql("INSERT INTO customer_mrr_movements(id,workspace_id,stripe_customer_id,currency,amount_minor,movement_type,effective_at,calculation_version,source_billing_references) VALUES(:id,:w,:c,'USD',100,'NEW',:at,'mrr-v1',ARRAY['billing:test'])")
                .param("id", movement).param("w", workspaceId).param("c", customerId)
                .param("at", OffsetDateTime.parse("2026-04-01T00:00:00Z")).update();
        UUID identity = UUID.randomUUID();
        db.sql("INSERT INTO external_identities(id,workspace_id,project_id,external_user_id) VALUES(:i,:w,:p,:u)")
                .param("i", identity).param("w", workspaceId).param("p", projectId).param("u", "user-" + workspaceId + "-" + customerId).update();
        db.sql("INSERT INTO billing_customers(id,workspace_id,stripe_customer_id,provider_created_at,source,source_version,source_sequence) VALUES(:id,:w,:c,now(),'BACKFILL',1,:c) ON CONFLICT DO NOTHING")
                .param("id", UUID.randomUUID()).param("w", workspaceId).param("c", customerId).update();
        db.sql("INSERT INTO stripe_customer_links(id,workspace_id,project_id,external_identity_id,stripe_customer_id,evidence_source,evidence_reference,linked_by_subject_id) VALUES(:id,:w,:p,:i,:c,'EXPLICIT_API','evidence','owner')")
                .param("id", UUID.randomUUID()).param("w", workspaceId).param("p", projectId).param("i", identity).param("c", customerId).update();
        UUID visitor = UUID.randomUUID(), session = UUID.randomUUID(), touchpoint = UUID.randomUUID();
        OffsetDateTime at = OffsetDateTime.parse("2026-03-01T00:00:00Z");
        db.sql("INSERT INTO visitors(id,workspace_id,project_id,external_visitor_id,first_seen_at,last_seen_at) VALUES(:v,:w,:p,:e,:at,:at)")
                .param("v", visitor).param("w", workspaceId).param("p", projectId).param("e", visitor.toString()).param("at", at).update();
        db.sql("INSERT INTO visitor_aliases(id,workspace_id,project_id,visitor_id,external_identity_id,identified_at) VALUES(:id,:w,:p,:v,:i,now())")
                .param("id", UUID.randomUUID()).param("w", workspaceId).param("p", projectId).param("v", visitor).param("i", identity).update();
        db.sql("INSERT INTO tracking_sessions(id,workspace_id,project_id,visitor_id,external_session_id,started_at) VALUES(:s,:w,:p,:v,:e,:at)")
                .param("s", session).param("w", workspaceId).param("p", projectId).param("v", visitor).param("e", session.toString()).param("at", at).update();
        db.sql("INSERT INTO touchpoints(id,workspace_id,project_id,visitor_id,session_id,occurred_at,landing_url,utm_source,created_at) VALUES(:id,:w,:p,:v,:s,:at,'https://example.test/','google',:created)")
                .param("id", touchpoint).param("w", workspaceId).param("p", projectId).param("v", visitor).param("s", session).param("at", at).param("created", at.plusSeconds(1)).update();
    }

    private UUID createWorkspace(String ownerSubject) {
        UUID workspaceId = UUID.randomUUID();
        db.sql("INSERT INTO workspaces (id, name, slug) VALUES (:id, :name, :slug)")
                .param("id", workspaceId).param("name", "Workspace " + workspaceId).param("slug", "workspace-" + workspaceId).update();
        addMember(workspaceId, ownerSubject, "OWNER");
        return workspaceId;
    }

    private UUID createProject(UUID workspaceId) {
        UUID projectId = UUID.randomUUID();
        db.sql("INSERT INTO projects (id, workspace_id, name, domain, public_key) VALUES (:p, :w, 'p', :d, :k)")
                .param("p", projectId).param("w", workspaceId).param("d", projectId + ".example").param("k", "pk-" + projectId).update();
        return projectId;
    }

    private void addMember(String subject, String role) {
        addMember(workspace, subject, role);
    }

    private void addMember(UUID workspaceId, String subject, String role) {
        db.sql("INSERT INTO workspace_members (workspace_id, subject_id, role) VALUES (:w, :subject, :role)")
                .param("w", workspaceId).param("subject", subject).param("role", role).update();
    }

    private static String statusPath(UUID workspaceId, UUID projectId) {
        return "/api/workspaces/%s/projects/%s/attribution-recalculation".formatted(workspaceId, projectId);
    }

    private static String resumePath(UUID workspaceId, UUID projectId) {
        return statusPath(workspaceId, projectId) + "/resume";
    }

    private static String restartPath(UUID workspaceId, UUID projectId) {
        return statusPath(workspaceId, projectId) + "/restart";
    }

    private RequestPostProcessor token(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject)
                .issuer("http://localhost:8081/realms/mrr-origin")
                .audience(List.of("mrr-origin-api")));
    }
}
