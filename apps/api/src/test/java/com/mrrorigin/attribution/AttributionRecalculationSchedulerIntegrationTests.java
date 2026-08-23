package com.mrrorigin.attribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * #92's bounded background driver for {@link AttributionRecalculationService#runBatch}: one bounded
 * batch per outstanding scope per tick, safe under overlapping ticks/replicas, never auto-restarting a
 * {@code COMPLETED} sweep, bounded convergence across ticks, cross-tenant isolation, and
 * failure/recovery. Every scenario drives {@code tick()}/{@code driveOutstandingScopes()} directly and
 * deterministically -- never a real {@code @Scheduled} firing or a sleep.
 */
@SpringBootTest
@Testcontainers
class AttributionRecalculationSchedulerIntegrationTests {

    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:18-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired AttributionRecalculationService recalculation;
    @Autowired JdbcClient db;
    UUID workspace;
    UUID project;

    @BeforeEach
    void setUp() {
        db.sql("TRUNCATE workspaces CASCADE").update();
        workspace = UUID.randomUUID();
        project = UUID.randomUUID();
        db.sql("INSERT INTO workspaces(id,name,slug) VALUES(:w,'test',:slug)").param("w", workspace).param("slug", "w-" + workspace).update();
        project(project, workspace, "one.example");
    }

    // ---- Bounded per tick (scope count and per-scope customer count), convergent across ticks -----

    @Test
    void boundedTickAdvancesAtMostConfiguredScopesAndALaterTickContinuesDraining() {
        // 5 separate projects (separate scopes) in the same workspace, 3 customers each.
        UUID[] projects = new UUID[5];
        for (int p = 0; p < 5; p++) {
            projects[p] = UUID.randomUUID();
            project(projects[p], workspace, "p" + p + ".example");
            for (int c = 0; c < 3; c++) {
                customer(projects[p], "cus-p" + p + "-" + c, true);
            }
        }

        var scheduler = new AttributionRecalculationScheduler(
                recalculation, new AttributionRecalculationSchedulerProperties(true, 10, 2));

        AttributionRecalculationScheduler.DriveOutcome first = scheduler.driveOutstandingScopes();
        assertThat(first.scopesConsidered()).isEqualTo(2);
        assertThat(first.scopesAdvanced()).isEqualTo(2);
        long completedAfterFirst = projectsCompleted(projects);
        assertThat(completedAfterFirst).isEqualTo(2);

        // Repeat ticks until every scope is done; bounded by 2 scopes/tick, so this must take
        // multiple ticks for 5 scopes.
        int ticks = 1;
        while (projectsCompleted(projects) < 5) {
            scheduler.driveOutstandingScopes();
            ticks++;
            assertThat(ticks).isLessThanOrEqualTo(10); // guard against an infinite loop on a bug
        }
        assertThat(ticks).isGreaterThan(1);
        assertThat(countResults()).isEqualTo(15);
    }

    @Test
    void oneTickCallsRunBatchExactlyOnceEvenWhenAScopeHasMoreWorkThanOneBatchCanFinish() {
        for (int c = 0; c < 5; c++) {
            customer(project, "cus-many-" + c, true);
        }

        // maxCustomersPerScope smaller than the 5 pending customers: one tick must leave the scope
        // still RUNNING, not loop internally to completion.
        var scheduler = new AttributionRecalculationScheduler(
                recalculation, new AttributionRecalculationSchedulerProperties(true, 3, 10));
        scheduler.driveOutstandingScopes();

        assertThat(recalculation.status(workspace, project)).get()
                .satisfies(run -> {
                    assertThat(run.status()).isEqualTo("RUNNING");
                    assertThat(run.processed()).isEqualTo(3);
                });

        scheduler.driveOutstandingScopes();
        assertThat(recalculation.status(workspace, project)).get()
                .satisfies(run -> {
                    assertThat(run.status()).isEqualTo("COMPLETED");
                    assertThat(run.processed()).isEqualTo(5);
                });
        assertThat(countResults()).isEqualTo(5);
    }

    // ---- Multiple replicas / overlapping ticks for the same scope --------------------------------

    @Test
    void twoConcurrentTicksForTheSameScopeConvergeWithoutDuplicatingResults() throws InterruptedException {
        int total = 12;
        for (int i = 0; i < total; i++) {
            customer(project, "cus-concurrent-" + i, true);
        }

        var replicaA = new AttributionRecalculationScheduler(
                recalculation, new AttributionRecalculationSchedulerProperties(true, 3, 10));
        var replicaB = new AttributionRecalculationScheduler(
                recalculation, new AttributionRecalculationSchedulerProperties(true, 3, 10));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            var futureA = pool.submit(() -> driveToCompletion(replicaA, ready, go));
            var futureB = pool.submit(() -> driveToCompletion(replicaB, ready, go));
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
            futureA.get(30, TimeUnit.SECONDS);
            futureB.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            pool.shutdown();
        }

        assertThat(countResults()).isEqualTo(total);
        assertThat(recalculation.status(workspace, project)).get()
                .satisfies(run -> {
                    assertThat(run.status()).isEqualTo("COMPLETED");
                    assertThat(run.processed()).isEqualTo(total);
                });
    }

    private void driveToCompletion(AttributionRecalculationScheduler scheduler, CountDownLatch ready, CountDownLatch go) {
        ready.countDown();
        await(go);
        for (int guard = 0; guard < 50; guard++) {
            scheduler.driveOutstandingScopes();
            var status = recalculation.status(workspace, project);
            if (status.isPresent() && status.get().status().equals("COMPLETED")) {
                return;
            }
        }
        throw new IllegalStateException("did not converge to COMPLETED within the bounded guard");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    // ---- Repeated ticks converge to COMPLETED; never auto-restart -------------------------------

    @Test
    void repeatedTicksAdvanceTheCheckpointAndConvergeToCompletedWithoutDuplicateResults() {
        for (int i = 0; i < 7; i++) {
            customer(project, "cus-repeat-" + i, true);
        }
        var scheduler = new AttributionRecalculationScheduler(
                recalculation, new AttributionRecalculationSchedulerProperties(true, 2, 10));

        int ticks = 0;
        while (statusOf(project).map(r -> !r.status().equals("COMPLETED")).orElse(true)) {
            scheduler.driveOutstandingScopes();
            ticks++;
            assertThat(ticks).isLessThanOrEqualTo(10);
        }
        assertThat(ticks).isGreaterThan(1); // proves the checkpoint genuinely advanced across ticks
        assertThat(countResults()).isEqualTo(7);
        List<String> afterCompletion = resultSnapshot();

        // Never auto-restart: repeated ticks against an already-COMPLETED scope must be no-ops.
        for (int i = 0; i < 3; i++) {
            AttributionRecalculationScheduler.DriveOutcome outcome = scheduler.driveOutstandingScopes();
            assertThat(outcome.scopesConsidered()).isZero(); // COMPLETED scope drops out of pendingScopes
        }
        assertThat(recalculation.status(workspace, project)).get()
                .satisfies(run -> {
                    assertThat(run.status()).isEqualTo("COMPLETED");
                    assertThat(run.processed()).isEqualTo(7);
                });
        assertThat(countResults()).isEqualTo(7);
        assertThat(resultSnapshot()).isEqualTo(afterCompletion);
    }

    // ---- Cross-tenant isolation -------------------------------------------------------------------

    @Test
    void oneTickAdvancesMultipleWorkspacesWithoutCrossingTenantBoundaries() {
        customer(project, "cus-shared-id", true);

        UUID otherWorkspace = UUID.randomUUID();
        UUID otherProject = UUID.randomUUID();
        db.sql("INSERT INTO workspaces(id,name,slug) VALUES(:w,'other',:slug)")
                .param("w", otherWorkspace).param("slug", "w-" + otherWorkspace).update();
        project(otherProject, otherWorkspace, "other.example");
        UUID savedWorkspace = workspace, savedProject = project;
        workspace = otherWorkspace;
        project = otherProject;
        customer(otherProject, "cus-shared-id", true); // same stripe_customer_id string, different tenant
        workspace = savedWorkspace;
        project = savedProject;

        var scheduler = new AttributionRecalculationScheduler(
                recalculation, new AttributionRecalculationSchedulerProperties(true, 10, 10));
        // Bounded convergence loop: both scopes are tiny (1 customer each) so this should finish fast.
        for (int guard = 0; guard < 5; guard++) {
            scheduler.driveOutstandingScopes();
        }

        assertThat(statusOf(project)).get().satisfies(run -> assertThat(run.status()).isEqualTo("COMPLETED"));
        assertThat(db.sql("SELECT count(*) FROM customer_attribution_results WHERE workspace_id=:w")
                        .param("w", workspace).query(Long.class).single())
                .isEqualTo(1);
        assertThat(db.sql("SELECT count(*) FROM customer_attribution_results WHERE workspace_id=:w")
                        .param("w", otherWorkspace).query(Long.class).single())
                .isEqualTo(1);
    }

    // ---- Failure recovery: one poisoned scope never blocks or silently completes others ---------

    @Test
    void aFailingScopeIsCaughtLeftUnfinishedAndRecoversOnceFixedWithoutBlockingAHealthyScope() {
        // Healthy project (already set up in @BeforeEach): normal customer.
        customer(project, "cus-healthy", true);

        // Poisoned project: a customer whose link uses the schema-valid but not-yet-approved-for-
        // production 'STRIPE_METADATA' evidence source (see V8's chk_stripe_customer_links_evidence_source
        // constraint, which allows 'EXPLICIT_API' and 'STRIPE_METADATA'). AttributionApplicationService
        // #calculate only accepts 'EXPLICIT_API' today and throws IllegalStateException for the other,
        // rolling back the whole runBatch transaction for that scope (see #recalculate's @Transactional
        // boundary) -- no partial/duplicate result is ever persisted for it.
        UUID poisonedProject = UUID.randomUUID();
        project(poisonedProject, workspace, "poisoned.example");
        customer(poisonedProject, "cus-poisoned", "STRIPE_METADATA");

        var scheduler = new AttributionRecalculationScheduler(
                recalculation, new AttributionRecalculationSchedulerProperties(true, 10, 10));
        AttributionRecalculationScheduler.DriveOutcome outcome = scheduler.driveOutstandingScopes();

        assertThat(outcome.scopesConsidered()).isEqualTo(2);
        assertThat(outcome.scopesAdvanced()).isEqualTo(1);
        assertThat(outcome.scopesFailed()).isEqualTo(1);
        // Healthy scope was not blocked by the poisoned one.
        assertThat(statusOf(project)).get().satisfies(run -> assertThat(run.status()).isEqualTo("COMPLETED"));
        // Poisoned scope: not lost (still pending/discoverable via pendingScopes), not silently
        // completed. runBatch's own @Transactional boundary rolled back the whole failed attempt,
        // including the run row it would have created, so no run exists yet at all -- effectively
        // NOT_STARTED, never COMPLETED.
        assertThat(recalculation.status(workspace, poisonedProject)).isEmpty();
        assertThat(db.sql("SELECT count(*) FROM customer_attribution_results WHERE workspace_id=:w AND project_id=:p")
                        .param("w", workspace).param("p", poisonedProject).query(Long.class).single())
                .isZero();

        // Fix the underlying condition, then a later tick recovers it without disturbing the healthy scope.
        db.sql("UPDATE stripe_customer_links SET evidence_source='EXPLICIT_API' WHERE workspace_id=:w AND stripe_customer_id='cus-poisoned'")
                .param("w", workspace).update();
        AttributionRecalculationScheduler.DriveOutcome recovered = scheduler.driveOutstandingScopes();
        assertThat(recovered.scopesFailed()).isZero();
        assertThat(recalculation.status(workspace, poisonedProject)).get()
                .satisfies(run -> assertThat(run.status()).isEqualTo("COMPLETED"));
        assertThat(db.sql("SELECT count(*) FROM customer_attribution_results WHERE workspace_id=:w AND project_id=:p")
                        .param("w", workspace).param("p", poisonedProject).query(Long.class).single())
                .isEqualTo(1);
        // Healthy scope untouched by the retry.
        assertThat(db.sql("SELECT count(*) FROM customer_attribution_results WHERE workspace_id=:w AND project_id=:p")
                        .param("w", workspace).param("p", project).query(Long.class).single())
                .isEqualTo(1);
    }

    // ---- Configurable / disableable ---------------------------------------------------------------

    @Test
    void tickIsANoOpWhenDisabled() {
        customer(project, "cus-disabled", true);
        var scheduler = new AttributionRecalculationScheduler(
                recalculation, new AttributionRecalculationSchedulerProperties(false, 10, 10));

        scheduler.tick();

        assertThat(recalculation.status(workspace, project)).isEmpty();
        assertThat(countResults()).isZero();
    }

    // ---- helpers ------------------------------------------------------------------------------

    private long projectsCompleted(UUID[] projects) {
        long completed = 0;
        for (UUID p : projects) {
            if (statusOf(p).map(r -> r.status().equals("COMPLETED")).orElse(false)) {
                completed++;
            }
        }
        return completed;
    }

    private java.util.Optional<AttributionRecalculationService.Run> statusOf(UUID projectId) {
        return recalculation.status(workspace, projectId);
    }

    private void project(UUID id, UUID owner, String domain) {
        db.sql("INSERT INTO projects(id,workspace_id,name,domain,public_key) VALUES(:p,:w,'p',:d,:k)")
                .param("p", id).param("w", owner).param("d", domain).param("k", "pk-" + id).update();
    }

    private UUID customer(UUID projectId, String customerId, boolean withTouchpoint) {
        return customer(projectId, customerId, withTouchpoint, "EXPLICIT_API");
    }

    private UUID customer(UUID projectId, String customerId, String evidenceSource) {
        return customer(projectId, customerId, true, evidenceSource);
    }

    private UUID customer(UUID projectId, String customerId, boolean withTouchpoint, String evidenceSource) {
        UUID movement = UUID.nameUUIDFromBytes((workspace + ":" + customerId).getBytes());
        db.sql("INSERT INTO customer_mrr_movements(id,workspace_id,stripe_customer_id,currency,amount_minor,movement_type,effective_at,calculation_version,source_billing_references) VALUES(:id,:w,:c,'USD',100,'NEW',:at,'mrr-v1',ARRAY['billing:test'])")
                .param("id", movement).param("w", workspace).param("c", customerId).param("at", OffsetDateTime.parse("2026-04-01T00:00:00Z")).update();
        UUID identity = UUID.randomUUID();
        db.sql("INSERT INTO external_identities(id,workspace_id,project_id,external_user_id) VALUES(:i,:w,:p,:u)")
                .param("i", identity).param("w", workspace).param("p", projectId).param("u", "user-" + customerId).update();
        db.sql("INSERT INTO billing_customers(id,workspace_id,stripe_customer_id,provider_created_at,source,source_version,source_sequence) VALUES(:id,:w,:c,now(),'BACKFILL',1,:c) ON CONFLICT DO NOTHING")
                .param("id", UUID.randomUUID()).param("w", workspace).param("c", customerId).update();
        db.sql("INSERT INTO stripe_customer_links(id,workspace_id,project_id,external_identity_id,stripe_customer_id,evidence_source,evidence_reference,linked_by_subject_id) VALUES(:id,:w,:p,:i,:c,:src,'evidence','owner')")
                .param("id", UUID.randomUUID()).param("w", workspace).param("p", projectId).param("i", identity).param("c", customerId).param("src", evidenceSource).update();
        if (withTouchpoint) {
            UUID visitor = UUID.randomUUID(), session = UUID.randomUUID(), touchpoint = UUID.randomUUID();
            OffsetDateTime at = OffsetDateTime.parse("2026-03-01T00:00:00Z");
            db.sql("INSERT INTO visitors(id,workspace_id,project_id,external_visitor_id,first_seen_at,last_seen_at) VALUES(:v,:w,:p,:e,:at,:at)")
                    .param("v", visitor).param("w", workspace).param("p", projectId).param("e", visitor.toString()).param("at", at).update();
            db.sql("INSERT INTO visitor_aliases(id,workspace_id,project_id,visitor_id,external_identity_id,identified_at) VALUES(:id,:w,:p,:v,:i,now())")
                    .param("id", UUID.randomUUID()).param("w", workspace).param("p", projectId).param("v", visitor).param("i", identity).update();
            db.sql("INSERT INTO tracking_sessions(id,workspace_id,project_id,visitor_id,external_session_id,started_at) VALUES(:s,:w,:p,:v,:e,:at)")
                    .param("s", session).param("w", workspace).param("p", projectId).param("v", visitor).param("e", session.toString()).param("at", at).update();
            db.sql("INSERT INTO touchpoints(id,workspace_id,project_id,visitor_id,session_id,occurred_at,landing_url,utm_source,created_at) VALUES(:id,:w,:p,:v,:s,:at,'https://example.test/','google',:created)")
                    .param("id", touchpoint).param("w", workspace).param("p", projectId).param("v", visitor).param("s", session).param("at", at).param("created", at.plusSeconds(1)).update();
        }
        return movement;
    }

    private long countResults() {
        return db.sql("SELECT count(*) FROM customer_attribution_results").query(Long.class).single();
    }

    private List<String> resultSnapshot() {
        return db.sql("SELECT movement_id,model_version,confidence,unattributed_reason,first_touchpoint_id,last_touchpoint_id,customer_link_evidence_id FROM customer_attribution_results ORDER BY movement_id,model_version")
                .query((r, n) -> r.getObject(1) + "|" + r.getString(2) + "|" + r.getString(3) + "|" + r.getString(4) + "|" + r.getObject(5) + "|" + r.getObject(6) + "|" + r.getObject(7)).list();
    }
}
