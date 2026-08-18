package com.mrrorigin.workspacelifecycle;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Shared workspace/project/module-fixture plumbing for #62's cross-module workspace-deletion
 * integration tests: a minimal valid row in every table each per-module deletion service owns, so
 * dependency-safe complete deletion and "no remaining workspace-owned rows" can be verified against
 * the real foreign-key graph rather than a subset of it.
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class AbstractWorkspaceLifecycleIntegrationTest {

    static final String OWNER = "user-owner";
    static final String ADMIN = "user-admin";
    static final String MEMBER = "user-member";
    static final String VIEWER = "user-viewer";
    static final String OTHER_OWNER = "user-other-owner";

    @DynamicPropertySource
    static void stripeProperties(DynamicPropertyRegistry registry) {
        registry.add("mrrorigin.stripe.connect.test-client-id", () -> "ca_test_123");
        registry.add("mrrorigin.stripe.connect.test-secret-key", () -> "sk_test_platform_secret");
        registry.add("mrrorigin.stripe.connect.live-client-id", () -> "ca_live_123");
        registry.add("mrrorigin.stripe.connect.live-secret-key", () -> "sk_live_platform_secret");
        registry.add("mrrorigin.stripe.connect.test-webhook-secret", () -> "whsec_test_platform_secret");
        registry.add("mrrorigin.stripe.connect.live-webhook-secret", () -> "whsec_live_platform_secret");
    }

    @Autowired
    private DataSource dataSource;

    private JdbcClient jdbc;

    @Autowired
    void setDataSource(DataSource dataSource) {
        jdbc = JdbcClient.create(dataSource);
    }

    JdbcClient jdbc() {
        return jdbc;
    }

    @BeforeEach
    void clearState() {
        new JdbcTemplate(dataSource)
                .execute("TRUNCATE TABLE workspaces, workspace_deletion_runs, workspace_deletion_tombstones CASCADE");
    }

    RequestPostProcessor token(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject)
                .issuer("http://localhost:8081/realms/mrr-origin")
                .audience(List.of("mrr-origin-api")));
    }

    UUID createWorkspace(String ownerSubject) {
        UUID workspaceId = UUID.randomUUID();
        jdbc.sql("INSERT INTO workspaces (id, name, slug) VALUES (:id, :name, :slug)")
                .param("id", workspaceId)
                .param("name", "Workspace " + workspaceId)
                .param("slug", "workspace-" + workspaceId)
                .update();
        addMember(workspaceId, ownerSubject, "OWNER");
        return workspaceId;
    }

    void addMember(UUID workspaceId, String subject, String role) {
        jdbc.sql("INSERT INTO workspace_members (workspace_id, subject_id, role) VALUES (:workspaceId, :subject, :role)")
                .param("workspaceId", workspaceId)
                .param("subject", subject)
                .param("role", role)
                .update();
    }

    UUID createProject(UUID workspaceId) {
        UUID projectId = UUID.randomUUID();
        jdbc.sql("""
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

    UUID insertIngestionKey(UUID workspaceId, UUID projectId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO project_ingestion_keys (id, workspace_id, project_id, key_prefix, secret_hash)
                        VALUES (:id, :workspaceId, :projectId, :prefix, :hash)
                        """)
                .param("id", id)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("prefix", "mrr_" + id.toString().substring(0, 8))
                .param("hash", "0".repeat(64))
                .update();
        return id;
    }

    void insertAllowedDomain(UUID workspaceId, UUID projectId, String domain) {
        jdbc.sql("""
                        INSERT INTO project_allowed_domains (id, workspace_id, project_id, domain)
                        VALUES (:id, :workspaceId, :projectId, :domain)
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("domain", domain)
                .update();
    }

    void insertRetentionSetting(UUID workspaceId, UUID projectId) {
        jdbc.sql("""
                        INSERT INTO project_tracking_retention_settings (workspace_id, project_id, retention_days, updated_at)
                        VALUES (:workspaceId, :projectId, 90, CURRENT_TIMESTAMP)
                        """)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .update();
    }

    void insertVerificationAttempt(UUID workspaceId, UUID projectId) {
        jdbc.sql("""
                        INSERT INTO tracking_verification_attempts (id, workspace_id, project_id, token, status, created_at, expires_at)
                        VALUES (:id, :workspaceId, :projectId, :token, 'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '1 hour')
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("token", "0".repeat(43))
                .update();
    }

    void insertIngestionFailure(UUID workspaceId, UUID projectId) {
        jdbc.sql("""
                        INSERT INTO tracking_ingestion_failures (id, workspace_id, project_id, kind, occurred_at)
                        VALUES (:id, :workspaceId, :projectId, 'INVALID_KEY', CURRENT_TIMESTAMP)
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .update();
    }

    UUID insertVisitor(UUID workspaceId, UUID projectId, String externalVisitorId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO visitors (id, workspace_id, project_id, external_visitor_id, first_seen_at, last_seen_at)
                        VALUES (:id, :workspaceId, :projectId, :externalId, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """)
                .param("id", id)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("externalId", externalVisitorId)
                .update();
        return id;
    }

    UUID insertSession(UUID workspaceId, UUID projectId, UUID visitorId, String externalSessionId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO tracking_sessions (id, workspace_id, project_id, visitor_id, external_session_id, started_at)
                        VALUES (:id, :workspaceId, :projectId, :visitorId, :externalId, CURRENT_TIMESTAMP)
                        """)
                .param("id", id)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("visitorId", visitorId)
                .param("externalId", externalSessionId)
                .update();
        return id;
    }

    UUID insertTouchpoint(UUID workspaceId, UUID projectId, UUID visitorId, UUID sessionId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO touchpoints (id, workspace_id, project_id, visitor_id, session_id, occurred_at, landing_url)
                        VALUES (:id, :workspaceId, :projectId, :visitorId, :sessionId, CURRENT_TIMESTAMP, 'https://app.example/')
                        """)
                .param("id", id)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("visitorId", visitorId)
                .param("sessionId", sessionId)
                .update();
        return id;
    }

    UUID insertEnvelope(UUID workspaceId, UUID projectId, UUID visitorId, UUID sessionId, String externalEventId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO tracking_event_envelopes
                            (id, workspace_id, project_id, visitor_id, session_id, external_event_id, event_type, occurred_at, payload)
                        VALUES (:id, :workspaceId, :projectId, :visitorId, :sessionId, :externalId, 'custom', CURRENT_TIMESTAMP, '{}'::JSONB)
                        """)
                .param("id", id)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("visitorId", visitorId)
                .param("sessionId", sessionId)
                .param("externalId", externalEventId)
                .update();
        return id;
    }

    UUID insertExternalIdentity(UUID workspaceId, UUID projectId, String externalUserId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO external_identities (id, workspace_id, project_id, external_user_id)
                        VALUES (:id, :workspaceId, :projectId, :externalUserId)
                        """)
                .param("id", id)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("externalUserId", externalUserId)
                .update();
        return id;
    }

    void insertVisitorAlias(UUID workspaceId, UUID projectId, UUID visitorId, UUID externalIdentityId) {
        jdbc.sql("""
                        INSERT INTO visitor_aliases (id, workspace_id, project_id, visitor_id, external_identity_id, identified_at)
                        VALUES (:id, :workspaceId, :projectId, :visitorId, :identityId, CURRENT_TIMESTAMP)
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("visitorId", visitorId)
                .param("identityId", externalIdentityId)
                .update();
    }

    UUID insertStripeCustomerLink(UUID workspaceId, UUID projectId, UUID externalIdentityId, String stripeCustomerId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO stripe_customer_links
                            (id, workspace_id, project_id, external_identity_id, stripe_customer_id,
                             evidence_source, evidence_reference, linked_by_subject_id)
                        VALUES (:id, :workspaceId, :projectId, :externalIdentityId, :stripeCustomerId,
                                'EXPLICIT_API', 'test fixture', 'test-actor')
                        """)
                .param("id", id)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("externalIdentityId", externalIdentityId)
                .param("stripeCustomerId", stripeCustomerId)
                .update();
        return id;
    }

    void insertLinkRepairAuditLog(UUID workspaceId, UUID projectId, String stripeCustomerId, UUID newLinkId) {
        jdbc.sql("""
                        INSERT INTO stripe_customer_link_repair_audit_log
                            (id, workspace_id, project_id, stripe_customer_id, external_user_id, action_type,
                             new_link_id, actor_subject_id)
                        VALUES (:id, :workspaceId, :projectId, :stripeCustomerId, 'test-user', 'CREATED', :newLinkId, 'test-actor')
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("stripeCustomerId", stripeCustomerId)
                .param("newLinkId", newLinkId)
                .update();
    }

    void insertBillingCustomer(UUID workspaceId, String stripeCustomerId) {
        jdbc.sql("""
                        INSERT INTO billing_customers
                            (id, workspace_id, stripe_customer_id, provider_created_at, source, source_version, source_sequence)
                        VALUES (:id, :workspaceId, :stripeCustomerId, CURRENT_TIMESTAMP, 'BACKFILL', 1, 'test')
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("stripeCustomerId", stripeCustomerId)
                .update();
    }

    void insertBillingPrice(UUID workspaceId, String stripePriceId) {
        jdbc.sql("""
                        INSERT INTO billing_prices
                            (id, workspace_id, stripe_price_id, stripe_product_id, currency, billing_scheme, type,
                             active, source, source_version, source_sequence)
                        VALUES (:id, :workspaceId, :stripePriceId, 'prod_test', 'USD', 'per_unit', 'one_time',
                                TRUE, 'BACKFILL', 1, 'test')
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("stripePriceId", stripePriceId)
                .update();
    }

    UUID insertBillingSubscription(UUID workspaceId, String stripeSubscriptionId, String stripeCustomerId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO billing_subscriptions
                            (id, workspace_id, stripe_subscription_id, stripe_customer_id, status, currency,
                             source, source_version, source_sequence)
                        VALUES (:id, :workspaceId, :stripeSubscriptionId, :stripeCustomerId, 'active', 'USD',
                                'BACKFILL', 1, 'test')
                        """)
                .param("id", id)
                .param("workspaceId", workspaceId)
                .param("stripeSubscriptionId", stripeSubscriptionId)
                .param("stripeCustomerId", stripeCustomerId)
                .update();
        jdbc.sql("""
                        INSERT INTO billing_subscription_items
                            (id, workspace_id, subscription_id, stripe_subscription_item_id, stripe_price_id,
                             source_version, source_sequence)
                        VALUES (:id, :workspaceId, :subscriptionId, :itemId, :priceId, 1, 'test')
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("subscriptionId", id)
                .param("itemId", "si_" + id)
                .param("priceId", "price_test")
                .update();
        jdbc.sql("""
                        INSERT INTO billing_subscription_status_events
                            (id, workspace_id, subscription_id, stripe_subscription_id, new_status, source,
                             source_version, source_sequence)
                        VALUES (:id, :workspaceId, :subscriptionId, :stripeSubscriptionId, 'active', 'BACKFILL', 1, 'test')
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("subscriptionId", id)
                .param("stripeSubscriptionId", stripeSubscriptionId)
                .update();
        return id;
    }

    void insertBillingInvoice(UUID workspaceId, String stripeInvoiceId, String stripeCustomerId) {
        jdbc.sql("""
                        INSERT INTO billing_invoices
                            (id, workspace_id, stripe_invoice_id, stripe_customer_id, status, currency,
                             amount_due, amount_paid, amount_remaining, provider_created_at, source, source_version, source_sequence)
                        VALUES (:id, :workspaceId, :stripeInvoiceId, :stripeCustomerId, 'paid', 'USD', 1000, 1000, 0,
                                CURRENT_TIMESTAMP, 'BACKFILL', 1, 'test')
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("stripeInvoiceId", stripeInvoiceId)
                .param("stripeCustomerId", stripeCustomerId)
                .update();
    }

    void insertBillingPayment(UUID workspaceId, String stripeChargeId, String stripeCustomerId) {
        jdbc.sql("""
                        INSERT INTO billing_payments
                            (id, workspace_id, stripe_charge_id, stripe_customer_id, amount, currency, status, paid,
                             provider_created_at, source, source_version, source_sequence)
                        VALUES (:id, :workspaceId, :stripeChargeId, :stripeCustomerId, 1000, 'USD', 'succeeded', TRUE,
                                CURRENT_TIMESTAMP, 'BACKFILL', 1, 'test')
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("stripeChargeId", stripeChargeId)
                .param("stripeCustomerId", stripeCustomerId)
                .update();
    }

    void insertBillingRefund(UUID workspaceId, String stripeRefundId, String stripeChargeId) {
        jdbc.sql("""
                        INSERT INTO billing_refunds
                            (id, workspace_id, stripe_refund_id, stripe_charge_id, amount, currency, status,
                             provider_created_at, source, source_version, source_sequence)
                        VALUES (:id, :workspaceId, :stripeRefundId, :stripeChargeId, 500, 'USD', 'succeeded',
                                CURRENT_TIMESTAMP, 'BACKFILL', 1, 'test')
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("stripeRefundId", stripeRefundId)
                .param("stripeChargeId", stripeChargeId)
                .update();
    }

    void insertBillingDiscount(UUID workspaceId, String stripeDiscountId, String stripeCustomerId) {
        jdbc.sql("""
                        INSERT INTO billing_discounts
                            (id, workspace_id, stripe_discount_id, stripe_customer_id, stripe_coupon_id, start_at,
                             source, source_version, source_sequence)
                        VALUES (:id, :workspaceId, :stripeDiscountId, :stripeCustomerId, 'coupon_test', CURRENT_TIMESTAMP,
                                'BACKFILL', 1, 'test')
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("stripeDiscountId", stripeDiscountId)
                .param("stripeCustomerId", stripeCustomerId)
                .update();
    }

    UUID insertStripeConnection(UUID workspaceId, String stripeAccountId, String status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO stripe_connections
                            (id, workspace_id, stripe_account_id, mode, granted_scope, status, verification_status, connected_at)
                        VALUES (:id, :workspaceId, :accountId, 'TEST', 'read_only', :status, 'VERIFIED', CURRENT_TIMESTAMP)
                        """)
                .param("id", id)
                .param("workspaceId", workspaceId)
                .param("accountId", stripeAccountId)
                .param("status", status)
                .update();
        return id;
    }

    void insertStripeWebhookEvent(UUID connectionId, UUID workspaceId, String stripeEventId) {
        jdbc.sql("""
                        INSERT INTO stripe_webhook_events
                            (id, stripe_event_id, stripe_account_id, mode, connection_id, workspace_id, event_type,
                             stripe_created_at, raw_payload, payload, processing_state)
                        VALUES (:id, :eventId, 'acct_test', 'TEST', :connectionId, :workspaceId, 'customer.updated',
                                CURRENT_TIMESTAMP, :payload, '{}'::JSONB, 'PROCESSED')
                        """)
                .param("id", UUID.randomUUID())
                .param("eventId", stripeEventId)
                .param("connectionId", connectionId)
                .param("workspaceId", workspaceId)
                .param("payload", "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .update();
    }

    UUID insertMrrMovement(UUID workspaceId, String stripeCustomerId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO customer_mrr_movements
                            (id, workspace_id, stripe_customer_id, currency, amount_minor, movement_type,
                             effective_at, calculation_version, source_billing_references)
                        VALUES (:id, :workspaceId, :stripeCustomerId, 'USD', 1000, 'NEW',
                                CURRENT_TIMESTAMP, 'mrr-v1', ARRAY['test'])
                        """)
                .param("id", id)
                .param("workspaceId", workspaceId)
                .param("stripeCustomerId", stripeCustomerId)
                .update();
        return id;
    }

    void insertMrrSnapshot(UUID workspaceId, String stripeCustomerId) {
        jdbc.sql("""
                        INSERT INTO customer_mrr_snapshots
                            (id, workspace_id, stripe_customer_id, currency, amount_minor, effective_at,
                             calculation_version, supported, source_billing_references)
                        VALUES (:id, :workspaceId, :stripeCustomerId, 'USD', 1000, CURRENT_TIMESTAMP, 'mrr-v1', TRUE, ARRAY['test'])
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("stripeCustomerId", stripeCustomerId)
                .update();
    }

    void insertRevenueSubscriptionState(UUID workspaceId, String stripeCustomerId, String stripeSubscriptionId) {
        jdbc.sql("""
                        INSERT INTO revenue_subscription_states
                            (id, workspace_id, stripe_customer_id, stripe_subscription_id, effective_at, status, source_billing_reference)
                        VALUES (:id, :workspaceId, :stripeCustomerId, :stripeSubscriptionId, CURRENT_TIMESTAMP, 'active', :ref)
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("stripeCustomerId", stripeCustomerId)
                .param("stripeSubscriptionId", stripeSubscriptionId)
                .param("ref", "test-" + UUID.randomUUID())
                .update();
    }

    void insertAttributionRecalculationRun(UUID workspaceId, UUID projectId) {
        jdbc.sql("""
                        INSERT INTO attribution_recalculation_runs
                            (id, workspace_id, project_id, model_version, status, started_at, updated_at)
                        VALUES (:id, :workspaceId, :projectId, 'attribution-v1', 'RUNNING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .update();
    }

    UUID insertAttributionResult(UUID workspaceId, UUID projectId, UUID movementId, UUID touchpointId, UUID customerLinkEvidenceId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO customer_attribution_results
                            (id, workspace_id, project_id, movement_id, acquisition_movement_id, model_version,
                             first_touchpoint_id, last_touchpoint_id, customer_link_evidence_id, confidence,
                             source_references, calculated_at)
                        VALUES (:id, :workspaceId, :projectId, :movementId, :movementId, 'attribution-v1',
                                :touchpointId, :touchpointId, :evidenceId, 'STRONG', ARRAY['test'], CURRENT_TIMESTAMP)
                        """)
                .param("id", id)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("movementId", movementId)
                .param("touchpointId", touchpointId)
                .param("evidenceId", customerLinkEvidenceId)
                .update();
        return id;
    }

    void insertExportAuditLog(UUID workspaceId, UUID projectId) {
        jdbc.sql("""
                        INSERT INTO export_audit_log (id, workspace_id, project_id, export_type, schema_version, actor_subject_id, filters, row_count)
                        VALUES (:id, :workspaceId, :projectId, 'CUSTOMERS', 'v1', 'test-actor', '{}'::JSONB, 1)
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .update();
    }

    void insertWeeklySummaryDelivery(UUID workspaceId, UUID projectId, String recipientSubjectId) {
        jdbc.sql("""
                        INSERT INTO weekly_summary_deliveries
                            (id, workspace_id, project_id, recipient_subject_id, recipient_email, week_start, next_attempt_at)
                        VALUES (:id, :workspaceId, :projectId, :subject, 'founder@example.com', CURRENT_DATE, CURRENT_TIMESTAMP)
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("subject", recipientSubjectId)
                .update();
    }

    void insertWeeklySummaryOptOut(UUID workspaceId, UUID projectId, String subjectId) {
        jdbc.sql("""
                        INSERT INTO weekly_summary_opt_outs (workspace_id, project_id, subject_id, opted_out_at)
                        VALUES (:workspaceId, :projectId, :subject, CURRENT_TIMESTAMP)
                        """)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("subject", subjectId)
                .update();
    }

    int count(String table, UUID workspaceId) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE workspace_id = :w")
                .param("w", workspaceId)
                .query(Integer.class)
                .single();
    }

    boolean workspaceExists(UUID workspaceId) {
        return jdbc.sql("SELECT COUNT(*) FROM workspaces WHERE id = :id")
                .param("id", workspaceId)
                .query(Integer.class)
                .single() > 0;
    }

    OffsetDateTime now() {
        return OffsetDateTime.now();
    }
}
