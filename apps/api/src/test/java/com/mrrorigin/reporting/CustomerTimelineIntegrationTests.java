package com.mrrorigin.reporting;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

import com.mrrorigin.attribution.AttributionApplicationService;
import com.mrrorigin.attribution.AttributionV1Engine;

import tools.jackson.databind.ObjectMapper;

/**
 * #24's single-customer detail and evidence timeline: deterministic ordering, workspace/project
 * isolation, relinking, reconciliation with the underlying stored records, all required
 * confidence/reason states, role-based repair capability, and stable keyset pagination.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CustomerTimelineIntegrationTests {
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
    void deterministicOrderingWhenMultipleEventTypesShareTheSameTimestamp() throws Exception {
        String at = "2026-05-01T12:00:00Z";
        insertBillingCustomer("cus_tie", at);
        link(project, "cus_tie", at);
        movement("cus_tie", "USD", 1_000, "NEW", at);
        String subscriptionId = subscription("cus_tie", "active", "USD");
        statusEvent(subscriptionId, null, "active", at);

        mockMvc.perform(timeline(OWNER, "cus_tie", null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(3))
                .andExpect(jsonPath("$.entries[0].eventType").value("IDENTITY_LINK_CREATED"))
                .andExpect(jsonPath("$.entries[1].eventType").value("SUBSCRIPTION_STATUS_CHANGED"))
                .andExpect(jsonPath("$.entries[2].eventType").value("MRR_MOVEMENT"))
                .andExpect(jsonPath("$.entries[0].at").value(at))
                .andExpect(jsonPath("$.entries[1].at").value(at))
                .andExpect(jsonPath("$.entries[2].at").value(at));
    }

    @Test
    void crossProjectEvidenceReturns404RatherThanLeakingExistence() throws Exception {
        insertBillingCustomer("cus_owned_elsewhere", "2026-01-01T00:00:00Z");
        link(project, "cus_owned_elsewhere", "2026-01-01T00:00:00Z");
        movement("cus_owned_elsewhere", "USD", 1_000, "NEW", "2026-01-01T00:00:00Z");
        attribution.recalculate(workspace, project, "cus_owned_elsewhere");

        UUID otherProject = UUID.randomUUID();
        db.sql("INSERT INTO projects (id, workspace_id, name, domain, public_key) VALUES (:p, :w, 'p2', 'two.example', :k)")
                .param("p", otherProject).param("w", workspace).param("k", "pk-" + otherProject).update();

        mockMvc.perform(get(
                                "/api/workspaces/{workspaceId}/projects/{projectId}/customers/{customerId}/timeline",
                                workspace, otherProject, "cus_owned_elsewhere")
                        .with(token(OWNER)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get(
                                "/api/workspaces/{workspaceId}/projects/{projectId}/customers/{customerId}/timeline",
                                workspace, otherProject, "cus_never_observed")
                        .with(token(OWNER)))
                .andExpect(status().isNotFound());
    }

    @Test
    void customerRelinkingMovesTheCompleteTimelineToTheCorrectOwningProject() throws Exception {
        insertBillingCustomer("cus_movable", "2026-01-01T00:00:00Z");
        movement("cus_movable", "USD", 1_000, "NEW", "2026-01-01T00:00:00Z");
        String subscriptionId = subscription("cus_movable", "active", "USD");
        statusEvent(subscriptionId, null, "active", "2026-01-01T00:00:00Z");
        identify(project, "user_movable");

        mockMvc.perform(repair(OWNER, project, "user_movable", "cus_movable")).andExpect(status().isOk());

        mockMvc.perform(timeline(OWNER, "cus_movable", null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[?(@.eventType=='MRR_MOVEMENT')].length()").value(1))
                .andExpect(jsonPath("$.entries[?(@.eventType=='SUBSCRIPTION_STATUS_CHANGED')].length()").value(1))
                .andExpect(jsonPath("$.entries[?(@.eventType=='IDENTITY_LINK_CREATED')].length()").value(1));

        UUID otherProject = UUID.randomUUID();
        db.sql("INSERT INTO projects (id, workspace_id, name, domain, public_key) VALUES (:p, :w, 'p2', 'two.example', :k)")
                .param("p", otherProject).param("w", workspace).param("k", "pk-" + otherProject).update();
        identify(otherProject, "user_movable_2");

        mockMvc.perform(repair(OWNER, otherProject, "user_movable_2", "cus_movable")).andExpect(status().isOk());

        // Old project no longer owns this customer at all.
        mockMvc.perform(timeline(OWNER, "cus_movable", null, null)).andExpect(status().isNotFound());

        // The new project sees the full, non-project-scoped billing/revenue history plus its own
        // identity link -- never the old project's superseded link.
        mockMvc.perform(get(
                                "/api/workspaces/{workspaceId}/projects/{projectId}/customers/{customerId}/timeline",
                                workspace, otherProject, "cus_movable")
                        .with(token(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[?(@.eventType=='MRR_MOVEMENT')].length()").value(1))
                .andExpect(jsonPath("$.entries[?(@.eventType=='SUBSCRIPTION_STATUS_CHANGED')].length()").value(1))
                .andExpect(jsonPath("$.entries[?(@.eventType=='IDENTITY_LINK_CREATED')].length()").value(1))
                .andExpect(jsonPath("$.entries[?(@.eventType=='IDENTITY_LINK_CREATED')].externalUserId")
                        .value(List.of("user_movable_2")));
    }

    @Test
    void firstAndLastTouchAndModelVersionReconcileWithStoredAttributionResults() throws Exception {
        insertBillingCustomer("cus_evidence", "2026-01-01T00:00:00Z");
        link(project, "cus_evidence", "2026-01-01T00:00:00Z");
        touchpoint("cus_evidence", "2025-12-01T00:00:00Z", "google", "spring");
        touchpoint("cus_evidence", "2025-12-15T00:00:00Z", "newsletter", "launch");
        movement("cus_evidence", "USD", 2_000, "NEW", "2026-01-05T00:00:00Z");
        attribution.recalculate(workspace, project, "cus_evidence");

        var stored = attribution.explanations(workspace, project, "cus_evidence", AttributionV1Engine.MODEL_VERSION).getFirst();

        mockMvc.perform(timeline(OWNER, "cus_evidence", null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detail.acquisition.modelVersion").value(stored.modelVersion()))
                .andExpect(jsonPath("$.detail.acquisition.status").value("STRONG"))
                .andExpect(jsonPath("$.detail.acquisition.firstTouch.source").value(stored.firstTouch().source()))
                .andExpect(jsonPath("$.detail.acquisition.firstTouch.campaign").value(stored.firstTouch().campaign()))
                .andExpect(jsonPath("$.detail.acquisition.lastTouch.source").value(stored.lastTouch().source()))
                .andExpect(jsonPath("$.detail.acquisition.lastTouch.campaign").value(stored.lastTouch().campaign()))
                .andExpect(jsonPath("$.detail.acquisition.customerLinkEvidenceId")
                        .value(stored.customerLinkEvidenceId().toString()));
    }

    @Test
    void mrrMovementEntriesReconcileWithTheMovementDrilldown() throws Exception {
        insertBillingCustomer("cus_movements", "2026-01-01T00:00:00Z");
        link(project, "cus_movements", "2026-01-01T00:00:00Z");
        touchpoint("cus_movements", "2025-12-01T00:00:00Z", "google", null);
        movement("cus_movements", "USD", 2_000, "NEW", "2026-01-05T00:00:00Z");
        movement("cus_movements", "USD", 3_000, "EXPANSION", "2026-02-05T00:00:00Z");
        attribution.recalculate(workspace, project, "cus_movements");

        var drilldown = new RevenueMovementsService(db)
                .list(workspace, project, OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                        OffsetDateTime.parse("2026-03-01T00:00:00Z"), null, null, false, false, null, false, null,
                        false, null, null, null);

        mockMvc.perform(timeline(OWNER, "cus_movements", null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[?(@.eventType=='MRR_MOVEMENT' && @.movementType=='NEW')].amountMinor")
                        .value(List.of(2000)))
                .andExpect(jsonPath("$.entries[?(@.eventType=='MRR_MOVEMENT' && @.movementType=='EXPANSION')].amountMinor")
                        .value(List.of(3000)))
                .andExpect(jsonPath("$.entries[?(@.eventType=='MRR_MOVEMENT')].confidence")
                        .value(List.of("STRONG", "STRONG")));

        org.assertj.core.api.Assertions.assertThat(drilldown.entries()).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(
                        drilldown.entries().stream().mapToLong(RevenueMovementsService.Entry::amountMinor).sum())
                .isEqualTo(5000);
    }

    @Test
    void currentMrrAndSubscriptionStatusReconcileWithSnapshotsAndNormalizedBillingRecords() throws Exception {
        insertBillingCustomer("cus_status", "2026-01-01T00:00:00Z");
        link(project, "cus_status", "2026-01-01T00:00:00Z");
        snapshot("cus_status", "USD", 4_200, "2026-02-01T00:00:00Z");
        subscription("cus_status", "past_due", "USD");

        mockMvc.perform(timeline(OWNER, "cus_status", null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detail.currentMrr[0].currency").value("USD"))
                .andExpect(jsonPath("$.detail.currentMrr[0].amountMinor").value(4200))
                .andExpect(jsonPath("$.detail.subscriptions[0].status").value("past_due"))
                .andExpect(jsonPath("$.detail.subscriptions[0].currency").value("USD"));
    }

    @Test
    void multiCurrencySeparationNeverCalculatesCrossCurrencyTotals() throws Exception {
        insertBillingCustomer("cus_currencies", "2026-01-01T00:00:00Z");
        link(project, "cus_currencies", "2026-01-01T00:00:00Z");
        snapshot("cus_currencies", "USD", 1_000, "2026-01-01T00:00:00Z");
        snapshot("cus_currencies", "EUR", 900, "2026-01-01T00:00:00Z");
        movement("cus_currencies", "USD", 1_000, "NEW", "2026-01-01T00:00:00Z");
        movement("cus_currencies", "EUR", 900, "NEW", "2026-01-01T00:00:00Z");

        mockMvc.perform(timeline(OWNER, "cus_currencies", null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detail.currentMrr.length()").value(2))
                .andExpect(jsonPath("$.entries[?(@.eventType=='MRR_MOVEMENT')].length()").value(2));
    }

    @Test
    void strongUnattributedNotRecalculatedAndNoActiveLinkNoEligibleTouchpointStates() throws Exception {
        insertBillingCustomer("cus_strong", "2026-01-01T00:00:00Z");
        link(project, "cus_strong", "2026-01-01T00:00:00Z");
        touchpoint("cus_strong", "2025-12-01T00:00:00Z", "google", null);
        movement("cus_strong", "USD", 1_000, "NEW", "2026-01-05T00:00:00Z");
        attribution.recalculate(workspace, project, "cus_strong");

        insertBillingCustomer("cus_no_active_link", "2026-01-01T00:00:00Z");
        movement("cus_no_active_link", "USD", 1_000, "NEW", "2026-01-05T00:00:00Z");
        attribution.recalculate(workspace, project, "cus_no_active_link");

        insertBillingCustomer("cus_no_touchpoint", "2026-01-01T00:00:00Z");
        link(project, "cus_no_touchpoint", "2026-01-01T00:00:00Z");
        movement("cus_no_touchpoint", "USD", 1_000, "NEW", "2026-01-05T00:00:00Z");
        attribution.recalculate(workspace, project, "cus_no_touchpoint");

        insertBillingCustomer("cus_not_recalculated", "2026-01-01T00:00:00Z");
        link(project, "cus_not_recalculated", "2026-01-01T00:00:00Z");
        movement("cus_not_recalculated", "USD", 1_000, "NEW", "2026-01-05T00:00:00Z");

        mockMvc.perform(timeline(OWNER, "cus_strong", null, null))
                .andExpect(jsonPath("$.detail.acquisition.status").value("STRONG"))
                .andExpect(jsonPath("$.detail.acquisition.unattributedReason").doesNotExist());

        mockMvc.perform(timeline(OWNER, "cus_no_active_link", null, null))
                .andExpect(jsonPath("$.detail.acquisition.status").value("UNATTRIBUTED"))
                .andExpect(jsonPath("$.detail.acquisition.unattributedReason").value("NO_ACTIVE_LINK"));

        mockMvc.perform(timeline(OWNER, "cus_no_touchpoint", null, null))
                .andExpect(jsonPath("$.detail.acquisition.status").value("UNATTRIBUTED"))
                .andExpect(jsonPath("$.detail.acquisition.unattributedReason").value("NO_ELIGIBLE_TOUCHPOINT"));

        mockMvc.perform(timeline(OWNER, "cus_not_recalculated", null, null))
                .andExpect(jsonPath("$.detail.acquisition.status").value("NOT_RECALCULATED"))
                .andExpect(jsonPath("$.detail.acquisition.unattributedReason").doesNotExist());
    }

    @Test
    void missingEvidenceRemainsExplicitAndNeverBecomesASyntheticFallback() throws Exception {
        // Linked (so the project owns it) but never had positive MRR (trial only) -- a real identity
        // link is honest evidence to show, while the acquisition itself stays explicitly absent
        // rather than a fabricated "unattributed" guess with no movement behind it.
        insertBillingCustomer("cus_trial_only", "2026-01-01T00:00:00Z");
        link(project, "cus_trial_only", "2026-01-01T00:00:00Z");
        subscription("cus_trial_only", "trialing", "USD");

        mockMvc.perform(timeline(OWNER, "cus_trial_only", null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detail.acquisition.status").value("NO_ACQUISITION_MOVEMENT"))
                .andExpect(jsonPath("$.detail.acquisition.movementId").doesNotExist())
                .andExpect(jsonPath("$.detail.acquisition.firstTouch").doesNotExist())
                .andExpect(jsonPath("$.detail.acquisition.calculatedAt").doesNotExist())
                .andExpect(jsonPath("$.detail.currentMrr.length()").value(0))
                .andExpect(jsonPath("$.detail.activeLink.externalUserId").value("user-cus_trial_only"));
    }

    @Test
    void roleBasedRedactionAndRepairCapabilityBehavior() throws Exception {
        db.sql("INSERT INTO workspace_members (workspace_id, subject_id, role) VALUES (:w, :s, 'MEMBER')")
                .param("w", workspace).param("s", "user-member").update();

        insertBillingCustomer("cus_capability", "2026-01-01T00:00:00Z");
        link(project, "cus_capability", "2026-01-01T00:00:00Z");

        mockMvc.perform(timeline(OWNER, "cus_capability", null, null))
                .andExpect(jsonPath("$.detail.repairCapability.canRepair").value(true))
                .andExpect(jsonPath("$.detail.repairCapability.reason").doesNotExist());

        mockMvc.perform(timeline("user-member", "cus_capability", null, null))
                .andExpect(jsonPath("$.detail.repairCapability.canRepair").value(false))
                .andExpect(jsonPath("$.detail.repairCapability.reason").value("WORKSPACE_ROLE_INSUFFICIENT"));

        mockMvc.perform(timeline("user-not-a-member", "cus_capability", null, null)).andExpect(status().isNotFound());
    }

    @Test
    void repairHistoryAppearsDeterministicallyAfterASuccessfulRepair() throws Exception {
        insertBillingCustomer("cus_repaired", "2026-01-01T00:00:00Z");
        movement("cus_repaired", "USD", 1_000, "NEW", "2026-01-05T00:00:00Z");
        // No active link yet -- recalculate still owns this customer to `project` via the NO_ACTIVE_LINK
        // result row (OWNER_CTE's "latest calculated result" branch), so it is visible before repair.
        attribution.recalculate(workspace, project, "cus_repaired");
        identify(project, "user_repaired");

        mockMvc.perform(timeline(OWNER, "cus_repaired", null, null))
                .andExpect(jsonPath("$.entries[?(@.eventType=='REPAIR_AUDIT')].length()").value(0));

        mockMvc.perform(repair(OWNER, project, "user_repaired", "cus_repaired")).andExpect(status().isOk());

        mockMvc.perform(timeline(OWNER, "cus_repaired", null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[?(@.eventType=='REPAIR_AUDIT')].length()").value(1))
                .andExpect(jsonPath("$.entries[?(@.eventType=='REPAIR_AUDIT')].actionType").value(List.of("CREATED")))
                .andExpect(jsonPath("$.entries[?(@.eventType=='REPAIR_AUDIT')].externalUserId")
                        .value(List.of("user_repaired")));
    }

    @Test
    void stableCursorPaginationWithEqualTimestamps() throws Exception {
        insertBillingCustomer("cus_paged", "2026-01-01T00:00:00Z");
        // Ownership via a plain link (not recalculate()) so the only entries are the three
        // same-instant movements below plus this one link, at a distinct earlier timestamp -- keeping
        // the tied-timestamp group exactly the three movements this test is about.
        link(project, "cus_paged", "2026-01-01T00:00:00Z");
        String at = "2026-03-01T00:00:00Z";
        movement("cus_paged", "USD", 1_000, "NEW", at);
        movement("cus_paged", "EUR", 900, "NEW", at);
        movement("cus_paged", "GBP", 800, "NEW", at);

        String fullPage = mockMvc.perform(timeline(OWNER, "cus_paged", null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(4))
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<String> fullOrder = referenceIdOrder(fullPage);

        List<String> pagedOrder = new java.util.ArrayList<>();
        String cursor = null;
        for (int i = 0; i < 4; i++) {
            String page = mockMvc.perform(timeline(OWNER, "cus_paged", cursor, 1))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.entries.length()").value(1))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            pagedOrder.addAll(referenceIdOrder(page));
            var pageTree = new ObjectMapper().readTree(page);
            cursor = pageTree.has("nextCursor") ? pageTree.get("nextCursor").asText() : null;
        }
        org.assertj.core.api.Assertions.assertThat(cursor).isNull();

        org.assertj.core.api.Assertions.assertThat(pagedOrder).isEqualTo(fullOrder);
        org.assertj.core.api.Assertions.assertThat(new java.util.HashSet<>(pagedOrder)).hasSize(4);
    }

    private List<String> referenceIdOrder(String responseBody) throws Exception {
        var tree = new ObjectMapper().readTree(responseBody).get("entries");
        List<String> referenceIds = new java.util.ArrayList<>();
        tree.forEach(node -> referenceIds.add(node.get("referenceId").asText()));
        return referenceIds;
    }

    private MockHttpServletRequestBuilder repair(String actor, UUID targetProject, String externalUserId, String stripeCustomerId) {
        return post("/api/workspaces/{workspaceId}/projects/{projectId}/unattributed-revenue/repairs", workspace, targetProject)
                .with(token(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content(writeJson(Map.of("externalUserId", externalUserId, "stripeCustomerId", stripeCustomerId)));
    }

    private String writeJson(Object value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void movement(String stripeCustomerId, String currency, long amountMinor, String movementType, String effectiveAt) {
        db.sql(
                        """
                        INSERT INTO customer_mrr_movements
                            (id, workspace_id, stripe_customer_id, currency, amount_minor, movement_type,
                             effective_at, calculation_version, source_billing_references)
                        VALUES (:id, :w, :c, :cur, :amt, :type, :at, 'mrr-v1', ARRAY['billing:test'])
                        """)
                .param("id", UUID.randomUUID())
                .param("w", workspace)
                .param("c", stripeCustomerId)
                .param("cur", currency)
                .param("amt", amountMinor)
                .param("type", movementType)
                .param("at", OffsetDateTime.parse(effectiveAt))
                .update();
    }

    private void link(UUID linkProject, String stripeCustomerId, String createdAt) {
        UUID identity = UUID.randomUUID();
        String externalUserId = "user-" + stripeCustomerId;
        db.sql("INSERT INTO external_identities (id, workspace_id, project_id, external_user_id) VALUES (:i, :w, :p, :u)")
                .param("i", identity).param("w", workspace).param("p", linkProject).param("u", externalUserId)
                .update();
        db.sql(
                        """
                        INSERT INTO stripe_customer_links
                            (id, workspace_id, project_id, external_identity_id, stripe_customer_id,
                             evidence_source, evidence_reference, linked_by_subject_id, created_at)
                        VALUES (:id, :w, :p, :i, :c, 'EXPLICIT_API', 'evidence', 'owner', :at)
                        """)
                .param("id", UUID.randomUUID()).param("w", workspace).param("p", linkProject).param("i", identity)
                .param("c", stripeCustomerId)
                .param("at", OffsetDateTime.parse(createdAt))
                .update();
    }

    private void identify(UUID identityProject, String externalUserId) {
        db.sql("INSERT INTO external_identities (id, workspace_id, project_id, external_user_id) VALUES (:i, :w, :p, :u)")
                .param("i", UUID.randomUUID()).param("w", workspace).param("p", identityProject).param("u", externalUserId)
                .update();
    }

    private void insertBillingCustomer(String stripeCustomerId, String providerCreatedAt) {
        db.sql(
                        """
                        INSERT INTO billing_customers
                            (id, workspace_id, stripe_customer_id, provider_created_at, source, source_version, source_sequence)
                        VALUES (:id, :w, :c, :at, 'BACKFILL', 1, :c)
                        """)
                .param("id", UUID.randomUUID())
                .param("w", workspace)
                .param("c", stripeCustomerId)
                .param("at", OffsetDateTime.parse(providerCreatedAt))
                .update();
    }

    private void snapshot(String stripeCustomerId, String currency, long amountMinor, String effectiveAt) {
        db.sql(
                        """
                        INSERT INTO customer_mrr_snapshots
                            (id, workspace_id, stripe_customer_id, currency, amount_minor, effective_at,
                             calculation_version, supported, source_billing_references)
                        VALUES (:id, :w, :c, :cur, :amt, :at, 'mrr-v1', TRUE, ARRAY['billing:test'])
                        """)
                .param("id", UUID.randomUUID())
                .param("w", workspace)
                .param("c", stripeCustomerId)
                .param("cur", currency)
                .param("amt", amountMinor)
                .param("at", OffsetDateTime.parse(effectiveAt))
                .update();
    }

    private String subscription(String stripeCustomerId, String status, String currency) {
        String stripeSubscriptionId = "sub_" + UUID.randomUUID();
        db.sql(
                        """
                        INSERT INTO billing_subscriptions
                            (id, workspace_id, stripe_subscription_id, stripe_customer_id, status, currency,
                             source, source_version, source_sequence)
                        VALUES (:id, :w, :sub, :c, :status, :cur, 'BACKFILL', 1, :sub)
                        """)
                .param("id", UUID.randomUUID())
                .param("w", workspace)
                .param("sub", stripeSubscriptionId)
                .param("c", stripeCustomerId)
                .param("status", status)
                .param("cur", currency)
                .update();
        return stripeSubscriptionId;
    }

    private void statusEvent(String stripeSubscriptionId, String previousStatus, String newStatus, String at) {
        UUID subscriptionId = db.sql("SELECT id FROM billing_subscriptions WHERE workspace_id = :w AND stripe_subscription_id = :sub")
                .param("w", workspace).param("sub", stripeSubscriptionId).query(UUID.class).single();
        db.sql(
                        """
                        INSERT INTO billing_subscription_status_events
                            (id, workspace_id, subscription_id, stripe_subscription_id, previous_status, new_status,
                             source, source_version, source_sequence, created_at)
                        VALUES (:id, :w, :sub, :ssub, :prev, :new, 'BACKFILL', :ver, :seq, :at)
                        """)
                .param("id", UUID.randomUUID())
                .param("w", workspace)
                .param("sub", subscriptionId)
                .param("ssub", stripeSubscriptionId)
                .param("prev", previousStatus)
                .param("new", newStatus)
                .param("ver", System.nanoTime())
                .param("seq", UUID.randomUUID().toString())
                .param("at", OffsetDateTime.parse(at))
                .update();
    }

    private void touchpoint(String stripeCustomerId, String at, String utmSource, String utmCampaign) {
        UUID identity = db.sql(
                        "SELECT external_identity_id FROM stripe_customer_links WHERE workspace_id = :w AND stripe_customer_id = :c")
                .param("w", workspace).param("c", stripeCustomerId).query(UUID.class).single();
        UUID visitor = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        OffsetDateTime time = OffsetDateTime.parse(at);
        db.sql(
                        "INSERT INTO visitors (id, workspace_id, project_id, external_visitor_id, first_seen_at, last_seen_at) "
                                + "VALUES (:v, :w, :p, :e, :at, :at)")
                .param("v", visitor).param("w", workspace).param("p", project).param("e", visitor.toString()).param("at", time)
                .update();
        db.sql(
                        "INSERT INTO visitor_aliases (id, workspace_id, project_id, visitor_id, external_identity_id, identified_at) "
                                + "VALUES (:id, :w, :p, :v, :i, now())")
                .param("id", UUID.randomUUID()).param("w", workspace).param("p", project).param("v", visitor).param("i", identity)
                .update();
        db.sql(
                        "INSERT INTO tracking_sessions (id, workspace_id, project_id, visitor_id, external_session_id, started_at) "
                                + "VALUES (:s, :w, :p, :v, :e, :at)")
                .param("s", session).param("w", workspace).param("p", project).param("v", visitor).param("e", session.toString())
                .param("at", time)
                .update();
        db.sql(
                        "INSERT INTO touchpoints (id, workspace_id, project_id, visitor_id, session_id, occurred_at, landing_url, "
                                + "utm_source, utm_campaign, created_at) VALUES (:id, :w, :p, :v, :s, :at, 'https://example.test/', :src, :camp, :created)")
                .param("id", UUID.randomUUID()).param("w", workspace).param("p", project).param("v", visitor).param("s", session)
                .param("at", time).param("src", utmSource).param("camp", utmCampaign).param("created", time.plusSeconds(1))
                .update();
    }

    private MockHttpServletRequestBuilder timeline(String actor, String stripeCustomerId, String cursor, Integer limit) {
        var request = get(
                        "/api/workspaces/{workspaceId}/projects/{projectId}/customers/{customerId}/timeline",
                        workspace, project, stripeCustomerId)
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
