package com.mrrorigin.tracking;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Shared workspace/project/ingestion-key setup and public-ingestion request builders for #8's
 * diagnostics/verification/retention/deletion integration tests. Deliberately does NOT declare the
 * {@code @Container} Postgres field -- see {@code AbstractBillingLedgerIntegrationTest}'s Javadoc for
 * why each concrete test class declares its own instead.
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class AbstractTrackingIntegrationTest {

    static final String OWNER = "user-owner";
    static final String VIEWER = "user-viewer";
    static final String OTHER_OWNER = "user-other-owner";

    @Autowired
    IngestionKeyService keys;

    @Autowired
    AllowedDomainService domains;

    private JdbcClient jdbc;

    @Autowired
    void setDataSource(DataSource dataSource) {
        jdbc = JdbcClient.create(dataSource);
    }

    JdbcClient jdbc() {
        return jdbc;
    }

    @BeforeEach
    void clearTrackingData() {
        jdbc.sql("TRUNCATE TABLE workspaces CASCADE").update();
    }

    UUID createWorkspace(String ownerSubject) {
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

    String issueKey(UUID workspaceId, UUID projectId) {
        return keys.issue(workspaceId, projectId).secret();
    }

    void allowDomain(UUID workspaceId, UUID projectId, String domain) {
        domains.add(workspaceId, projectId, domain);
    }

    RequestPostProcessor token(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject)
                .issuer("http://localhost:8081/realms/mrr-origin")
                .audience(List.of("mrr-origin-api")));
    }

    MockHttpServletRequestBuilder ingest(String key, String origin, String body) {
        return post("/api/public/v1/events")
                .header("X-Ingestion-Key", key)
                .header("Origin", origin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    // ---- direct-insert fixtures for retention/deletion tests, bypassing HTTP ingestion so exact
    // timestamps and evidence chains (attribution results, Stripe customer links) can be controlled --

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

    UUID insertBatch(UUID workspaceId, UUID projectId, String externalBatchId, OffsetDateTime receivedAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO tracking_ingestion_batches
                            (id, workspace_id, project_id, external_batch_id, envelope_version, request_hash, received_at)
                        VALUES (:id, :workspaceId, :projectId, :externalId, 1, :hash, :receivedAt)
                        """)
                .param("id", id)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("externalId", externalBatchId)
                .param("hash", "0".repeat(64))
                .param("receivedAt", receivedAt)
                .update();
        return id;
    }

    UUID insertEnvelope(UUID workspaceId, UUID projectId, UUID visitorId, UUID sessionIdOrNull, UUID batchIdOrNull,
            String externalEventId, OffsetDateTime occurredAt, OffsetDateTime receivedAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO tracking_event_envelopes
                            (id, workspace_id, project_id, visitor_id, session_id, ingestion_batch_id,
                             external_event_id, event_type, occurred_at, received_at, payload)
                        VALUES (:id, :workspaceId, :projectId, :visitorId, :sessionId, :batchId,
                                :externalId, 'custom', :occurredAt, :receivedAt, '{}'::JSONB)
                        """)
                .param("id", id)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("visitorId", visitorId)
                .param("sessionId", sessionIdOrNull)
                .param("batchId", batchIdOrNull)
                .param("externalId", externalEventId)
                .param("occurredAt", occurredAt)
                .param("receivedAt", receivedAt)
                .update();
        return id;
    }

    void insertFailure(UUID workspaceId, UUID projectId, String kind, OffsetDateTime occurredAt) {
        jdbc.sql("""
                        INSERT INTO tracking_ingestion_failures (id, workspace_id, project_id, kind, occurred_at)
                        VALUES (:id, :workspaceId, :projectId, :kind, :occurredAt)
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("kind", kind)
                .param("occurredAt", occurredAt)
                .update();
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

    static String pageViewBatch(String batchId, String eventId, String visitorId, String occurredAt) {
        return """
                {"version":1,"batchId":"%s","events":[
                  {"eventId":"%s","visitorId":"%s","sessionId":"session-1","type":"page_view",\
                   "occurredAt":"%s","payload":{"pageUrl":"https://app.example/pricing"}}
                ]}
                """.formatted(batchId, eventId, visitorId, occurredAt);
    }

    static String verificationBatch(String batchId, String eventId, String visitorId, String token, String occurredAt) {
        return """
                {"version":1,"batchId":"%s","events":[
                  {"eventId":"%s","visitorId":"%s","sessionId":"session-1","type":"custom",\
                   "occurredAt":"%s","payload":{"name":"%s","properties":{"verificationToken":"%s"}}}
                ]}
                """.formatted(
                batchId, eventId, visitorId, occurredAt, TrackingVerificationService.VERIFICATION_EVENT_NAME, token);
    }
}
