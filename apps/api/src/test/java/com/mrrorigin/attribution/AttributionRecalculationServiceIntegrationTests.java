package com.mrrorigin.attribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
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
 * Covers #19's batch recalculation contract: bounded resumable batches, idempotent retry/restart,
 * tenant isolation, model-version transition auditability, and concurrency protection. The pure
 * selection/evidence/conflict/inheritance rules are already proven by {@link AttributionV1GoldenFixtureTests}
 * and single-customer recalculation by {@link AttributionApplicationServiceIntegrationTests}; this
 * class exercises the job that drives {@code recalculate} across many customers.
 */
@SpringBootTest
@Testcontainers
class AttributionRecalculationServiceIntegrationTests {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:18-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired AttributionRecalculationService recalculation;
    @Autowired AttributionApplicationService attribution;
    @Autowired JdbcClient db;
    UUID workspace;
    UUID project;

    @BeforeEach void setUp() {
        db.sql("TRUNCATE workspaces CASCADE").update();
        workspace = UUID.randomUUID();
        project = UUID.randomUUID();
        db.sql("INSERT INTO workspaces(id,name,slug) VALUES(:w,'test',:slug)").param("w", workspace).param("slug", "w-" + workspace).update();
        project(project, workspace, "one.example");
    }

    @Test void resumesAcrossInterruptedBatchesWithoutDuplicatingActiveResults() {
        for (int i = 0; i < 5; i++) customer("cus-" + i, true);

        var outcomes = new java.util.ArrayList<AttributionRecalculationService.BatchOutcome>();
        AttributionRecalculationService.BatchOutcome outcome;
        do {
            outcome = recalculation.runBatch(workspace, project, 2);
            outcomes.add(outcome);
        } while (!outcome.complete());

        assertThat(outcomes).hasSize(3); // batches of 2, 2, then 1 -- the short final batch signals completion
        assertThat(outcomes.stream().mapToInt(AttributionRecalculationService.BatchOutcome::customersProcessedThisBatch).sum()).isEqualTo(5);
        assertThat(countResults()).isEqualTo(5);

        // Simulating "interruption": a later call after completion is a safe no-op, not reprocessing.
        var afterCompletion = recalculation.runBatch(workspace, project, 2);
        assertThat(afterCompletion.customersProcessedThisBatch()).isZero();
        assertThat(afterCompletion.complete()).isTrue();
        assertThat(countResults()).isEqualTo(5);
    }

    @Test void retryingAnAlreadyAppliedBatchIsIdempotent() {
        customer("cus-a", true);
        customer("cus-b", true);

        var first = recalculation.runBatch(workspace, project, 10);
        assertThat(first.complete()).isTrue();
        assertThat(countResults()).isEqualTo(2);
        List<String> firstRows = resultSnapshot();

        // Retry: same scope, same starting point (nothing left to process because the checkpoint
        // already advanced past both customers) -- must not create duplicate rows or change results.
        var retry = recalculation.runBatch(workspace, project, 10);
        assertThat(retry.customersProcessedThisBatch()).isZero();
        assertThat(countResults()).isEqualTo(2);
        assertThat(resultSnapshot()).isEqualTo(firstRows);

        // A genuine restart (e.g. late identify() calls since completion) reprocesses from scratch
        // and produces byte-identical results because recalculation is deterministic and inputs
        // haven't changed -- upserts overwrite in place rather than duplicating.
        recalculation.restart(workspace, project);
        var afterRestart = recalculation.runBatch(workspace, project, 10);
        assertThat(afterRestart.customersProcessedThisBatch()).isEqualTo(2);
        assertThat(countResults()).isEqualTo(2);
        assertThat(resultSnapshot()).isEqualTo(firstRows);
    }

    @Test void restartRejectsAnInProgressRunAndRejectsWhenNothingHasEverRun() {
        assertThatThrownBy(() -> recalculation.restart(workspace, project))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no recalculation run");

        for (int i = 0; i < 5; i++) customer("cus-" + i, true);
        recalculation.runBatch(workspace, project, 2); // partial: 2 of 5 processed, still RUNNING

        assertThatThrownBy(() -> recalculation.restart(workspace, project))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("still in progress");
    }

    @Test void keepsWorkspaceScopedRecalculationIsolatedAcrossTenants() {
        customer("cus-shared-id", true);

        UUID otherWorkspace = UUID.randomUUID();
        UUID otherProject = UUID.randomUUID();
        db.sql("INSERT INTO workspaces(id,name,slug) VALUES(:w,'other',:slug)").param("w", otherWorkspace).param("slug", "w-" + otherWorkspace).update();
        project(otherProject, otherWorkspace, "other.example");

        UUID savedWorkspace = workspace, savedProject = project;
        workspace = otherWorkspace;
        project = otherProject;
        customer("cus-shared-id", true); // same stripe_customer_id string, different tenant
        var otherOutcome = recalculation.runBatch(otherWorkspace, otherProject, 10);
        workspace = savedWorkspace;
        project = savedProject;

        assertThat(otherOutcome.customersProcessedThisBatch()).isEqualTo(1);
        assertThat(db.sql("SELECT count(*) FROM customer_attribution_results WHERE workspace_id=:w")
                .param("w", savedWorkspace).query(Long.class).single()).isZero();

        var outcome = recalculation.runBatch(workspace, project, 10);
        assertThat(outcome.customersProcessedThisBatch()).isEqualTo(1);
        assertThat(db.sql("SELECT count(*) FROM customer_attribution_results WHERE workspace_id=:w")
                .param("w", otherWorkspace).query(Long.class).single()).isEqualTo(1);
    }

    @Test void modelVersionTransitionKeepsPriorVersionResultsAuditableAlongsideNewOnes() {
        UUID movementId = customer("cus-a", true);
        recalculation.runBatch(workspace, project, 10);
        assertThat(countResults()).isEqualTo(1);

        // Simulate a prior deployed model version's stored result (a synthetic stand-in for #18's
        // engine before some future revision) to prove recalculation never overwrites or deletes it.
        db.sql("""
                INSERT INTO customer_attribution_results(id,workspace_id,project_id,movement_id,acquisition_movement_id,model_version,
                  confidence,unattributed_reason,source_references,calculated_at)
                VALUES(:id,:w,:p,:m,:m,'attribution-v0','UNATTRIBUTED','NO_ACTIVE_LINK',ARRAY[]::text[],now())
                """).param("id", UUID.randomUUID()).param("w", workspace).param("p", project).param("m", movementId).update();

        assertThat(countResults()).isEqualTo(2);
        assertThat(attribution.explanations(workspace, project, "cus-a", "attribution-v0"))
                .singleElement().satisfies(r -> assertThat(r.unattributedReason()).isEqualTo("NO_ACTIVE_LINK"));
        assertThat(attribution.explanations(workspace, project, "cus-a", AttributionV1Engine.MODEL_VERSION))
                .singleElement().satisfies(r -> assertThat(r.confidence()).isEqualTo("STRONG"));

        // Recalculating again under the current model version must not disturb the old-version row.
        recalculation.restart(workspace, project);
        recalculation.runBatch(workspace, project, 10);
        assertThat(countResults()).isEqualTo(2);
        assertThat(attribution.explanations(workspace, project, "cus-a", "attribution-v0"))
                .singleElement().satisfies(r -> assertThat(r.unattributedReason()).isEqualTo("NO_ACTIVE_LINK"));
    }

    @Test void concurrentBatchRunsForTheSameScopeSerializeInsteadOfDuplicatingWork() throws InterruptedException {
        int total = 12;
        IntStream.range(0, total).forEach(i -> customer("cus-" + i, true));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            var futures = IntStream.range(0, 2).mapToObj(i -> pool.submit(() -> {
                ready.countDown();
                await(go);
                AttributionRecalculationService.BatchOutcome outcome;
                do {
                    outcome = recalculation.runBatch(workspace, project, 3);
                } while (!outcome.complete());
                return outcome;
            })).toList();
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
            for (var future : futures) future.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            pool.shutdown();
        }

        assertThat(countResults()).isEqualTo(total);
        assertThat(recalculation.status(workspace, project)).get()
                .satisfies(run -> assertThat(run.processed()).isEqualTo(total));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private void project(UUID id, UUID owner, String domain) {
        db.sql("INSERT INTO projects(id,workspace_id,name,domain,public_key) VALUES(:p,:w,'p',:d,:k)")
                .param("p", id).param("w", owner).param("d", domain).param("k", "pk-" + id).update();
    }

    private UUID customer(String customerId, boolean withTouchpoint) {
        UUID movement = UUID.nameUUIDFromBytes((workspace + ":" + customerId).getBytes());
        db.sql("INSERT INTO customer_mrr_movements(id,workspace_id,stripe_customer_id,currency,amount_minor,movement_type,effective_at,calculation_version,source_billing_references) VALUES(:id,:w,:c,'USD',100,'NEW',:at,'mrr-v1',ARRAY['billing:test'])")
                .param("id", movement).param("w", workspace).param("c", customerId).param("at", OffsetDateTime.parse("2026-04-01T00:00:00Z")).update();
        UUID identity = UUID.randomUUID();
        db.sql("INSERT INTO external_identities(id,workspace_id,project_id,external_user_id) VALUES(:i,:w,:p,:u)")
                .param("i", identity).param("w", workspace).param("p", project).param("u", "user-" + customerId).update();
        db.sql("INSERT INTO billing_customers(id,workspace_id,stripe_customer_id,provider_created_at,source,source_version,source_sequence) VALUES(:id,:w,:c,now(),'BACKFILL',1,:c) ON CONFLICT DO NOTHING")
                .param("id", UUID.randomUUID()).param("w", workspace).param("c", customerId).update();
        db.sql("INSERT INTO stripe_customer_links(id,workspace_id,project_id,external_identity_id,stripe_customer_id,evidence_source,evidence_reference,linked_by_subject_id) VALUES(:id,:w,:p,:i,:c,'EXPLICIT_API','evidence','owner')")
                .param("id", UUID.randomUUID()).param("w", workspace).param("p", project).param("i", identity).param("c", customerId).update();
        if (withTouchpoint) {
            UUID visitor = UUID.randomUUID(), session = UUID.randomUUID(), touchpoint = UUID.randomUUID();
            OffsetDateTime at = OffsetDateTime.parse("2026-03-01T00:00:00Z");
            db.sql("INSERT INTO visitors(id,workspace_id,project_id,external_visitor_id,first_seen_at,last_seen_at) VALUES(:v,:w,:p,:e,:at,:at)")
                    .param("v", visitor).param("w", workspace).param("p", project).param("e", visitor.toString()).param("at", at).update();
            db.sql("INSERT INTO visitor_aliases(id,workspace_id,project_id,visitor_id,external_identity_id,identified_at) VALUES(:id,:w,:p,:v,:i,now())")
                    .param("id", UUID.randomUUID()).param("w", workspace).param("p", project).param("v", visitor).param("i", identity).update();
            db.sql("INSERT INTO tracking_sessions(id,workspace_id,project_id,visitor_id,external_session_id,started_at) VALUES(:s,:w,:p,:v,:e,:at)")
                    .param("s", session).param("w", workspace).param("p", project).param("v", visitor).param("e", session.toString()).param("at", at).update();
            db.sql("INSERT INTO touchpoints(id,workspace_id,project_id,visitor_id,session_id,occurred_at,landing_url,utm_source,created_at) VALUES(:id,:w,:p,:v,:s,:at,'https://example.test/','google',:created)")
                    .param("id", touchpoint).param("w", workspace).param("p", project).param("v", visitor).param("s", session).param("at", at).param("created", at.plusSeconds(1)).update();
        }
        return movement;
    }

    private long countResults() { return db.sql("SELECT count(*) FROM customer_attribution_results").query(Long.class).single(); }

    private List<String> resultSnapshot() {
        return db.sql("SELECT movement_id,model_version,confidence,unattributed_reason,first_touchpoint_id,last_touchpoint_id,customer_link_evidence_id FROM customer_attribution_results ORDER BY movement_id,model_version")
                .query((r, n) -> r.getObject(1) + "|" + r.getString(2) + "|" + r.getString(3) + "|" + r.getString(4) + "|" + r.getObject(5) + "|" + r.getObject(6) + "|" + r.getObject(7)).list();
    }
}
