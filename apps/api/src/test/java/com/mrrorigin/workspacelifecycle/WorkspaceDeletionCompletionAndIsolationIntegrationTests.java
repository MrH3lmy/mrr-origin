package com.mrrorigin.workspacelifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Regression coverage for the two final #62 review findings on PR #76. */
@Testcontainers
class WorkspaceDeletionCompletionAndIsolationIntegrationTests extends AbstractWorkspaceLifecycleIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    private static final List<String> WORKSPACE_OWNED_TABLES = List.of(
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
            "attribution_recalculation_runs",
            "export_audit_log",
            "weekly_summary_deliveries",
            "weekly_summary_opt_outs",
            "projects",
            "workspace_members");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private WorkspaceDeletionRequestService deletionService;

    @Test
    void completedDeletionRemainsIdempotentThroughHttpEndpoints() throws Exception {
        UUID workspaceId = createWorkspace(OWNER);

        mvc.perform(post(deletionPath(workspaceId))
                        .with(token(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmationBody(workspaceId)))
                .andExpect(status().isOk());

        runToCompletion(workspaceId);
        assertThat(workspaceExists(workspaceId)).isFalse();
        assertThat(count("workspace_deletion_tombstones", workspaceId)).isEqualTo(1);

        // The owner membership row is gone, but the required post-completion /run retry is still an
        // idempotent no-op backed by the opaque tombstone rather than a 404 from requireOwner().
        mvc.perform(post(deletionPath(workspaceId) + "/run").with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("DONE"))
                .andExpect(jsonPath("$.complete").value(true));

        mvc.perform(get(deletionPath(workspaceId)).with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("DONE"))
                .andExpect(jsonPath("$.complete").value(true));

        // POST /deletion also remains idempotent, while its confirmation requirement is still enforced
        // even after completion.
        mvc.perform(post(deletionPath(workspaceId))
                        .with(token(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmationBody(workspaceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("DONE"))
                .andExpect(jsonPath("$.complete").value(true));

        mvc.perform(post(deletionPath(workspaceId))
                        .with(token(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmation\":\"DELETE wrong\"}"))
                .andExpect(status().isBadRequest());

        assertThat(count("workspace_deletion_runs", workspaceId)).isZero();
        assertThat(count("workspace_deletion_tombstones", workspaceId)).isEqualTo(1);
    }

    @Test
    void deletingWorkspaceANeverTouchesWorkspaceBRows() {
        UUID workspaceA = createWorkspace(OWNER);
        UUID workspaceB = createWorkspace(OTHER_OWNER);
        seedWorkspaceData(workspaceA, "a", OWNER);
        seedWorkspaceData(workspaceB, "b", OTHER_OWNER);

        Map<String, Long> workspaceBCountsBefore = new LinkedHashMap<>();
        long populatedTables = 0;
        for (String table : WORKSPACE_OWNED_TABLES) {
            long workspaceACount = count(table, workspaceA);
            long workspaceBCount = count(table, workspaceB);
            assertThat(workspaceACount)
                    .as("matching pre-delete fixture count for %s", table)
                    .isEqualTo(workspaceBCount);
            workspaceBCountsBefore.put(table, workspaceBCount);
            if (workspaceBCount > 0) {
                populatedTables++;
            }
        }
        // Make sure this remains a genuinely cross-module structural test rather than accidentally
        // degenerating into a few empty-table equality assertions as the schema evolves.
        assertThat(populatedTables).as("cross-module fixture breadth").isGreaterThan(20);

        deletionService.createOrGetRequest(workspaceA, "DELETE " + workspaceA);
        runToCompletion(workspaceA);

        assertThat(workspaceExists(workspaceA)).isFalse();
        assertThat(workspaceExists(workspaceB)).isTrue();
        for (String table : WORKSPACE_OWNED_TABLES) {
            assertThat(count(table, workspaceA)).as("workspace A deleted: %s", table).isZero();
            assertThat(count(table, workspaceB))
                    .as("workspace B preserved exactly: %s", table)
                    .isEqualTo(workspaceBCountsBefore.get(table));
        }
    }

    private void seedWorkspaceData(UUID workspaceId, String suffix, String recipientSubject) {
        UUID projectId = createProject(workspaceId);
        insertAllowedDomain(workspaceId, projectId, suffix + ".example.com");
        insertRetentionSetting(workspaceId, projectId);
        insertVerificationAttempt(workspaceId, projectId);
        insertIngestionFailure(workspaceId, projectId);
        insertIngestionKey(workspaceId, projectId);

        UUID visitor = insertVisitor(workspaceId, projectId, "visitor-" + suffix);
        UUID session = insertSession(workspaceId, projectId, visitor, "session-" + suffix);
        UUID touchpoint = insertTouchpoint(workspaceId, projectId, visitor, session);
        insertEnvelope(workspaceId, projectId, visitor, session, "event-" + suffix);

        UUID identity = insertExternalIdentity(workspaceId, projectId, "user-" + suffix);
        insertVisitorAlias(workspaceId, projectId, visitor, identity);

        String customerId = "cus_" + suffix;
        String subscriptionId = "sub_" + suffix;
        insertBillingCustomer(workspaceId, customerId);
        insertBillingPrice(workspaceId, "price_" + suffix);
        insertBillingSubscription(workspaceId, subscriptionId, customerId);
        insertBillingInvoice(workspaceId, "in_" + suffix, customerId);
        insertBillingPayment(workspaceId, "ch_" + suffix, customerId);
        insertBillingRefund(workspaceId, "re_" + suffix, "ch_" + suffix);
        insertBillingDiscount(workspaceId, "di_" + suffix, customerId);
        UUID connectionId = insertStripeConnection(workspaceId, "acct_" + suffix, "ACTIVE");
        insertStripeWebhookEvent(connectionId, workspaceId, "evt_" + suffix);

        UUID linkId = insertStripeCustomerLink(workspaceId, projectId, identity, customerId);
        insertLinkRepairAuditLog(workspaceId, projectId, customerId, linkId);

        UUID movementId = insertMrrMovement(workspaceId, customerId);
        insertMrrSnapshot(workspaceId, customerId);
        insertRevenueSubscriptionState(workspaceId, customerId, subscriptionId);
        insertAttributionResult(workspaceId, projectId, movementId, touchpoint, linkId);
        insertAttributionRecalculationRun(workspaceId, projectId);

        insertExportAuditLog(workspaceId, projectId);
        insertWeeklySummaryDelivery(workspaceId, projectId, recipientSubject);
        insertWeeklySummaryOptOut(workspaceId, projectId, recipientSubject);
    }

    private WorkspaceDeletionRequestService.DeletionRunOutcome runToCompletion(UUID workspaceId) {
        WorkspaceDeletionRequestService.DeletionRunOutcome outcome;
        int guard = 0;
        do {
            outcome = deletionService.runBatch(workspaceId, 50);
            if (++guard > 250) {
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
}
