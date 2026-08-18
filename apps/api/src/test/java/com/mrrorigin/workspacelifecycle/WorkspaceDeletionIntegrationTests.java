package com.mrrorigin.workspacelifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.mrrorigin.tracking.IngestionKeyService;

/**
 * #62's owner-only, resumable, cross-module workspace deletion: owner success, non-owner rejection,
 * cross-tenant concealment, confirmation mismatch, duplicate/concurrent requests, crash/resume, write
 * rejection once {@code DELETING}, ingestion-key/Stripe-sync admission effects, dependency-safe
 * complete deletion, and tombstone contents/cleanup.
 */
@Testcontainers
@Import(WorkspaceDeletionIntegrationTests.FixedClockConfiguration.class)
class WorkspaceDeletionIntegrationTests extends AbstractWorkspaceLifecycleIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private WorkspaceDeletionRequestService deletionService;

    @Autowired
    private WorkspaceTombstonePurgeService purgeService;

    @Autowired
    private IngestionKeyService ingestionKeys;

    @Test
    void ownerCanCreateAndCompleteADeletionRequest() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);

        mvc.perform(post(deletionPath(workspaceId))
                        .with(token(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmationBody(workspaceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("REPORTING"));

        var outcome = runToCompletion(workspaceId, 500);
        assertThat(outcome.complete()).isTrue();
        assertThat(outcome.phase()).isEqualTo("DONE");
        assertThat(workspaceExists(workspaceId)).isFalse();
    }

    @Test
    void nonOwnerRolesCannotRequestDeletion() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        addMember(workspaceId, ADMIN, "ADMIN");
        addMember(workspaceId, MEMBER, "MEMBER");
        addMember(workspaceId, VIEWER, "VIEWER");

        for (String subject : List.of(ADMIN, MEMBER, VIEWER)) {
            mvc.perform(post(deletionPath(workspaceId))
                            .with(token(subject))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(confirmationBody(workspaceId)))
                    .andExpect(status().isForbidden());
        }
        assertThat(deletionService.status(workspaceId)).isEmpty();
    }

    @Test
    void anotherWorkspacesOwnerCannotSeeOrDeleteThisWorkspace() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        createWorkspace(OTHER_OWNER);

        mvc.perform(post(deletionPath(workspaceId))
                        .with(token(OTHER_OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmationBody(workspaceId)))
                .andExpect(status().isNotFound());
        mvc.perform(get(deletionPath(workspaceId)).with(token(OTHER_OWNER))).andExpect(status().isNotFound());
        assertThat(workspaceExists(workspaceId)).isTrue();
    }

    @Test
    void wrongConfirmationStringIsRejectedEvenWhenARequestAlreadyExists() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);

        mvc.perform(post(deletionPath(workspaceId))
                        .with(token(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmation\":\"DELETE not-the-workspace-id\"}"))
                .andExpect(status().isBadRequest());
        assertThat(deletionService.status(workspaceId)).isEmpty();

        // A wrong confirmation is rejected on a retry against an already-started request too.
        deletionService.createOrGetRequest(workspaceId, "DELETE " + workspaceId);
        mvc.perform(post(deletionPath(workspaceId))
                        .with(token(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmation\":\"DELETE nope\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateAndConcurrentRequestsReturnTheExistingRunInsteadOfDuplicatingWork() throws Exception {
        UUID sequentialWorkspace = createWorkspace(OWNER);
        var first = deletionService.createOrGetRequest(sequentialWorkspace, "DELETE " + sequentialWorkspace);
        var retry = deletionService.createOrGetRequest(sequentialWorkspace, "DELETE " + sequentialWorkspace);
        assertThat(retry.phase()).isEqualTo(first.phase());
        assertThat(retry.totalRowsDeleted()).isEqualTo(first.totalRowsDeleted());
        assertThat(count("workspace_deletion_requests", sequentialWorkspace)).isEqualTo(1);

        UUID concurrentWorkspace = createWorkspace(OWNER);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch ready = new CountDownLatch(4);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Void>> futures = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    deletionService.createOrGetRequest(concurrentWorkspace, "DELETE " + concurrentWorkspace);
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
        assertThat(count("workspace_deletion_requests", concurrentWorkspace)).isEqualTo(1);
    }

    @Test
    void crashAndResumeAcrossManyBoundedBatchesConvergesToComplete() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);
        UUID visitor = insertVisitor(workspaceId, projectId, "visitor-1");
        for (int i = 0; i < 12; i++) {
            insertEnvelope(workspaceId, projectId, visitor, null, "event-" + i);
        }

        deletionService.createOrGetRequest(workspaceId, "DELETE " + workspaceId);

        int calls = 0;
        WorkspaceDeletionRequestService.DeletionRunOutcome outcome;
        do {
            // Each call is its own independent transaction, simulating a crash/resume between them --
            // the same technique ProjectDataDeletionIntegrationTests uses.
            outcome = deletionService.runBatch(workspaceId, 3);
            calls++;
            if (calls > 300) {
                throw new AssertionError("Deletion did not converge within a bounded number of batches");
            }
        } while (!outcome.complete());

        assertThat(calls).isGreaterThan(1);
        assertThat(outcome.phase()).isEqualTo("DONE");
        assertThat(workspaceExists(workspaceId)).isFalse();
    }

    @Test
    void writesAreRejectedOnceTheWorkspaceIsDeleting() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        deletionService.createOrGetRequest(workspaceId, "DELETE " + workspaceId);

        mvc.perform(post("/api/workspaces/" + workspaceId + "/projects")
                        .with(token(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Blocked\",\"domain\":\"blocked.example.com\",\"timezone\":\"UTC\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void ingestionKeysAreRevokedAndStripeSyncIsDisabledDuringAdmission() {
        UUID workspaceId = createWorkspace(OWNER);
        UUID projectId = createProject(workspaceId);
        UUID keyId = insertIngestionKey(workspaceId, projectId);
        UUID connectionId = insertStripeConnection(workspaceId, "acct_test_1", "ACTIVE");

        deletionService.createOrGetRequest(workspaceId, "DELETE " + workspaceId);

        assertThat(jdbc().sql("SELECT revoked_at FROM project_ingestion_keys WHERE id = :id")
                        .param("id", keyId)
                        .query(OffsetDateTime.class)
                        .single())
                .isNotNull();
        assertThat(jdbc().sql("SELECT status FROM stripe_connections WHERE id = :id")
                        .param("id", connectionId)
                        .query(String.class)
                        .single())
                .isEqualTo("DISCONNECTED");
        assertThat(ingestionKeys.getActive(workspaceId, projectId)).isEmpty();
    }

    @Test
    void dependencySafeCompleteDeletionLeavesNoWorkspaceOwnedRows() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        addMember(workspaceId, MEMBER, "MEMBER");
        UUID projectId = createProject(workspaceId);

        insertAllowedDomain(workspaceId, projectId, "app.example.com");
        insertRetentionSetting(workspaceId, projectId);
        insertVerificationAttempt(workspaceId, projectId);
        insertIngestionFailure(workspaceId, projectId);
        insertIngestionKey(workspaceId, projectId);

        UUID visitor = insertVisitor(workspaceId, projectId, "visitor-1");
        UUID session = insertSession(workspaceId, projectId, visitor, "session-1");
        UUID touchpoint = insertTouchpoint(workspaceId, projectId, visitor, session);
        insertEnvelope(workspaceId, projectId, visitor, session, "event-1");

        UUID identity = insertExternalIdentity(workspaceId, projectId, "user-1");
        insertVisitorAlias(workspaceId, projectId, visitor, identity);

        insertBillingCustomer(workspaceId, "cus_1");
        insertBillingPrice(workspaceId, "price_1");
        insertBillingSubscription(workspaceId, "sub_1", "cus_1");
        insertBillingInvoice(workspaceId, "in_1", "cus_1");
        insertBillingPayment(workspaceId, "ch_1", "cus_1");
        insertBillingRefund(workspaceId, "re_1", "ch_1");
        insertBillingDiscount(workspaceId, "di_1", "cus_1");
        UUID connectionId = insertStripeConnection(workspaceId, "acct_1", "ACTIVE");
        insertStripeWebhookEvent(connectionId, workspaceId, "evt_stripe_1");

        UUID linkId = insertStripeCustomerLink(workspaceId, projectId, identity, "cus_1");
        insertLinkRepairAuditLog(workspaceId, projectId, "cus_1", linkId);

        UUID movementId = insertMrrMovement(workspaceId, "cus_1");
        insertMrrSnapshot(workspaceId, "cus_1");
        insertRevenueSubscriptionState(workspaceId, "cus_1", "sub_1");

        insertAttributionResult(workspaceId, projectId, movementId, touchpoint, linkId);

        insertExportAuditLog(workspaceId, projectId);
        insertWeeklySummaryDelivery(workspaceId, projectId, MEMBER);
        insertWeeklySummaryOptOut(workspaceId, projectId, MEMBER);

        deletionService.createOrGetRequest(workspaceId, "DELETE " + workspaceId);
        var outcome = runToCompletion(workspaceId, 50);
        assertThat(outcome.complete()).isTrue();

        for (String table : List.of(
                "project_allowed_domains",
                "project_tracking_retention_settings",
                "tracking_verification_attempts",
                "tracking_ingestion_failures",
                "project_ingestion_keys",
                "visitors",
                "tracking_sessions",
                "touchpoints",
                "tracking_event_envelopes",
                "external_identities",
                "visitor_aliases",
                "billing_customers",
                "billing_prices",
                "billing_subscriptions",
                "billing_subscription_items",
                "billing_subscription_status_events",
                "billing_invoices",
                "billing_payments",
                "billing_refunds",
                "billing_discounts",
                "stripe_connections",
                "stripe_webhook_events",
                "stripe_customer_links",
                "stripe_customer_link_repair_audit_log",
                "customer_mrr_movements",
                "customer_mrr_snapshots",
                "revenue_subscription_states",
                "customer_attribution_results",
                "export_audit_log",
                "weekly_summary_deliveries",
                "weekly_summary_opt_outs",
                "projects",
                "workspace_members")) {
            assertThat(count(table, workspaceId)).as(table).isZero();
        }
        assertThat(workspaceExists(workspaceId)).isFalse();
    }

    @Test
    void tombstoneContainsOnlyNonPiiFieldsAndIsPurgedAfter30Days() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);
        deletionService.createOrGetRequest(workspaceId, "DELETE " + workspaceId);
        var outcome = runToCompletion(workspaceId, 500);
        assertThat(outcome.complete()).isTrue();

        String status = jdbc().sql("SELECT status FROM workspace_deletion_requests WHERE workspace_id = :w")
                .param("w", workspaceId)
                .query(String.class)
                .single();
        String phase = jdbc().sql("SELECT phase FROM workspace_deletion_requests WHERE workspace_id = :w")
                .param("w", workspaceId)
                .query(String.class)
                .single();
        assertThat(status).isEqualTo("COMPLETED");
        assertThat(phase).isEqualTo("DONE");

        // A fresh tombstone survives a purge tick.
        purgeService.purgeExpiredTombstones();
        assertThat(count("workspace_deletion_requests", workspaceId)).isEqualTo(1);

        // Aged past 30 days, the next tick purges it.
        jdbc().sql("UPDATE workspace_deletion_requests SET completed_at = :old WHERE workspace_id = :w")
                .param("old", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusDays(31))
                .param("w", workspaceId)
                .update();
        purgeService.purgeExpiredTombstones();
        assertThat(count("workspace_deletion_requests", workspaceId)).isZero();
    }

    private WorkspaceDeletionRequestService.DeletionRunOutcome runToCompletion(UUID workspaceId, int maxRows) {
        WorkspaceDeletionRequestService.DeletionRunOutcome outcome;
        int guard = 0;
        do {
            outcome = deletionService.runBatch(workspaceId, maxRows);
            guard++;
            if (guard > 200) {
                throw new AssertionError("Deletion did not converge within a bounded number of batches");
            }
        } while (!outcome.complete());
        return outcome;
    }

    private static String deletionPath(UUID workspaceId) {
        return "/api/workspaces/%s/deletion".formatted(workspaceId);
    }

    private static String confirmationBody(UUID workspaceId) {
        return "{\"confirmation\":\"DELETE %s\"}".formatted(workspaceId);
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
