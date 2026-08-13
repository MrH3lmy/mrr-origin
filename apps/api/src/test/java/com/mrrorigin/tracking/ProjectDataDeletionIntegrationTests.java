package com.mrrorigin.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * #8's resumable, checkpointed, full project tracking-data deletion job: bounded batches across
 * phases, interruption/resume, idempotency, cross-project isolation, evidence preservation, and
 * concurrent-run safety.
 */
@Testcontainers
@Import(ProjectDataDeletionIntegrationTests.FixedClockConfiguration.class)
class ProjectDataDeletionIntegrationTests extends AbstractTrackingIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ProjectDataDeletionService deletionService;

    @Test
    void deletionInterruptedAndResumedAcrossManyBoundedBatchesConvergesToComplete() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);
        UUID visitorId = insertVisitor(workspaceId, projectId, "visitor-1");
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        for (int i = 0; i < 25; i++) {
            insertEnvelope(workspaceId, projectId, visitorId, null, null, "event-" + i, now, now);
        }

        int calls = 0;
        boolean complete = false;
        while (!complete) {
            calls++;
            if (calls > 50) {
                throw new AssertionError("Deletion did not converge within a bounded number of batches");
            }
            String body = mvc.perform(post(runPath(workspaceId, projectId)).param("maxRows", "5").with(token(OWNER)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            complete = body.contains("\"complete\":true");
        }
        assertThat(calls).isGreaterThan(1);

        mvc.perform(get(statusPath(workspaceId, projectId)).with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("DONE"))
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.totalRowsDeleted").value(26)) // 25 envelopes + the now-unreferenced visitor
                .andExpect(jsonPath("$.skippedEvidenceRows").value(0));
        assertThat(envelopeCount(workspaceId, projectId)).isZero();
        assertThat(count("visitors", workspaceId, projectId)).isZero();
    }

    @Test
    void repeatedRunsAfterCompletionAreIdempotent() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);
        UUID visitorId = insertVisitor(workspaceId, projectId, "visitor-1");
        insertEnvelope(workspaceId, projectId, visitorId, null, null, "event-1",
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));

        runToCompletion(workspaceId, projectId, 100);
        var firstStatus = deletionService.status(workspaceId, projectId).orElseThrow();

        mvc.perform(post(runPath(workspaceId, projectId)).with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowsDeletedThisBatch").value(0))
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.totalRowsDeleted").value(firstStatus.totalRowsDeleted()));
        mvc.perform(post(runPath(workspaceId, projectId)).with(token(OWNER)))
                .andExpect(jsonPath("$.totalRowsDeleted").value(firstStatus.totalRowsDeleted()));
    }

    @Test
    void crossProjectIsolationDeletionNeverTouchesAnotherProjectsData() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectA = createProject(workspaceId);
        UUID projectB = createProject(workspaceId);
        UUID visitorA = insertVisitor(workspaceId, projectA, "visitor-a");
        UUID visitorB = insertVisitor(workspaceId, projectB, "visitor-b");
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        insertEnvelope(workspaceId, projectA, visitorA, null, null, "event-a", now, now);
        insertEnvelope(workspaceId, projectB, visitorB, null, null, "event-b", now, now);

        runToCompletion(workspaceId, projectA, 100);

        assertThat(count("visitors", workspaceId, projectA)).isZero();
        assertThat(envelopeCount(workspaceId, projectA)).isZero();
        assertThat(count("visitors", workspaceId, projectB)).isEqualTo(1);
        assertThat(envelopeCount(workspaceId, projectB)).isEqualTo(1);
    }

    @Test
    void touchpointsAndIdentitiesStillReferencedAsAttributionEvidenceAreSkippedNotDeleted() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);

        // Protected chain: an identity linked to a Stripe customer, whose linked touchpoint is
        // referenced by a STRONG customer_attribution_results row (V10's ON DELETE RESTRICT).
        UUID protectedVisitor = insertVisitor(workspaceId, projectId, "protected-visitor");
        UUID protectedSession = insertSession(workspaceId, projectId, protectedVisitor, "protected-session");
        UUID protectedTouchpoint = insertTouchpoint(workspaceId, projectId, protectedVisitor, protectedSession);
        UUID protectedIdentity = insertExternalIdentity(workspaceId, projectId, "protected-user");
        insertVisitorAlias(workspaceId, projectId, protectedVisitor, protectedIdentity);
        insertBillingCustomer(workspaceId, "cus_protected");
        UUID linkId = insertStripeCustomerLink(workspaceId, projectId, protectedIdentity, "cus_protected");
        UUID movementId = insertMrrMovement(workspaceId, "cus_protected");
        insertAttributionResult(workspaceId, projectId, movementId, protectedTouchpoint, linkId);

        // Unprotected: an ordinary visitor/touchpoint/identity with no attribution evidence at all.
        UUID freeVisitor = insertVisitor(workspaceId, projectId, "free-visitor");
        UUID freeSession = insertSession(workspaceId, projectId, freeVisitor, "free-session");
        insertTouchpoint(workspaceId, projectId, freeVisitor, freeSession);
        UUID freeIdentity = insertExternalIdentity(workspaceId, projectId, "free-user");
        insertVisitorAlias(workspaceId, projectId, freeVisitor, freeIdentity);

        var outcome = runToCompletion(workspaceId, projectId, 100);

        assertThat(outcome.complete()).isTrue();
        assertThat(outcome.skippedEvidenceRows()).isEqualTo(2); // 1 protected touchpoint + 1 protected identity
        assertThat(count("touchpoints", workspaceId, projectId)).isEqualTo(1);
        assertThat(jdbc().sql("SELECT COUNT(*) FROM touchpoints WHERE id = :id").param("id", protectedTouchpoint)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(count("external_identities", workspaceId, projectId)).isEqualTo(1);
        assertThat(jdbc().sql("SELECT COUNT(*) FROM external_identities WHERE id = :id").param("id", protectedIdentity)
                .query(Integer.class).single()).isEqualTo(1);
        // The unprotected visitor/session (with no surviving touchpoint) is fully removed.
        assertThat(jdbc().sql("SELECT COUNT(*) FROM visitors WHERE id = :id").param("id", freeVisitor)
                .query(Integer.class).single()).isZero();
        // visitor_aliases are always removed unconditionally, protected identity or not.
        assertThat(count("visitor_aliases", workspaceId, projectId)).isZero();
    }

    @Test
    void concurrentDeletionRunsForTheSameProjectSerializeAndConvergeWithoutDoubleCounting() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);
        UUID visitorId = insertVisitor(workspaceId, projectId, "visitor-1");
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        for (int i = 0; i < 40; i++) {
            insertEnvelope(workspaceId, projectId, visitorId, null, null, "event-" + i, now, now);
        }

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch ready = new CountDownLatch(4);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Void>> futures = new java.util.ArrayList<>();
            for (int t = 0; t < 4; t++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    for (int i = 0; i < 15; i++) {
                        deletionService.runBatch(workspaceId, projectId, 3);
                    }
                    return null;
                }));
            }
            ready.await();
            go.countDown();
            for (Future<Void> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }
        runToCompletion(workspaceId, projectId, 100);

        assertThat(envelopeCount(workspaceId, projectId)).isZero();
        // 40 envelopes + the now-unreferenced visitor; proves no batch was double-counted or lost
        // across the four racing threads despite the per-run-row FOR UPDATE lock serializing them.
        assertThat(deletionService.status(workspaceId, projectId).orElseThrow().totalRowsDeleted()).isEqualTo(41);
    }

    @Test
    void memberWithoutManagePermissionCannotRunDeletion() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        addMember(workspaceId, VIEWER, "VIEWER");
        UUID projectId = createProject(workspaceId);

        mvc.perform(post(runPath(workspaceId, projectId)).with(token(VIEWER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anotherWorkspacesProjectCannotHaveItsDataDeleted() throws Exception {
        UUID workspaceA = createWorkspace(OWNER);
        UUID workspaceB = createWorkspace(OWNER);
        UUID projectB = createProject(workspaceB);

        mvc.perform(post(runPath(workspaceA, projectB)).with(token(OWNER)))
                .andExpect(status().isNotFound());
    }

    private ProjectDataDeletionService.DeletionRunOutcome runToCompletion(UUID workspaceId, UUID projectId, int maxRows) {
        ProjectDataDeletionService.DeletionRunOutcome outcome;
        int guard = 0;
        do {
            outcome = deletionService.runBatch(workspaceId, projectId, maxRows);
            guard++;
            if (guard > 200) {
                throw new AssertionError("Deletion did not converge within a bounded number of batches");
            }
        } while (!outcome.complete());
        return outcome;
    }

    private int envelopeCount(UUID workspaceId, UUID projectId) {
        return count("tracking_event_envelopes", workspaceId, projectId);
    }

    private int count(String table, UUID workspaceId, UUID projectId) {
        return jdbc().sql("SELECT COUNT(*) FROM " + table + " WHERE workspace_id = :w AND project_id = :p")
                .param("w", workspaceId)
                .param("p", projectId)
                .query(Integer.class)
                .single();
    }

    private static String statusPath(UUID workspaceId, UUID projectId) {
        return "/api/workspaces/%s/projects/%s/tracking/deletion".formatted(workspaceId, projectId);
    }

    private static String runPath(UUID workspaceId, UUID projectId) {
        return statusPath(workspaceId, projectId) + "/run";
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
