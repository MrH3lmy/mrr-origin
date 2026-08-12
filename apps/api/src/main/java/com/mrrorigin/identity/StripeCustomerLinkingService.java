package com.mrrorigin.identity;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mrrorigin.workspace.WorkspaceContext;

/**
 * Explicit server-side bridge between a project's tracked external-user identity (V6) and a
 * workspace's Stripe customer (V7), per #16 and ARCHITECTURE.md's identity module. Only the
 * EXPLICIT_API evidence source is implemented here -- see V8's migration comment and the #16 PR
 * description for why STRIPE_METADATA is reserved but not yet populated.
 */
@Service
@Transactional(readOnly = true)
public class StripeCustomerLinkingService {

    private static final String EVIDENCE_SOURCE_EXPLICIT_API = "EXPLICIT_API";
    private static final String EVIDENCE_REFERENCE_EXPLICIT_API =
            "explicit server-side link call by an authenticated workspace manager";

    private final JdbcClient jdbc;
    private final WorkspaceContext workspaceContext;

    public StripeCustomerLinkingService(JdbcClient jdbc, WorkspaceContext workspaceContext) {
        this.jdbc = jdbc;
        this.workspaceContext = workspaceContext;
    }

    @Transactional
    public LinkOutcome link(UUID workspaceId, UUID projectId, String externalUserId, String stripeCustomerId) {
        workspaceContext.requireManager(workspaceId);
        ensureProjectOwnedByWorkspace(workspaceId, projectId);

        UUID externalIdentityId = resolveExternalIdentity(workspaceId, projectId, externalUserId)
                .orElseThrow(() -> new StripeCustomerLinkException(
                        HttpStatus.NOT_FOUND,
                        "external_user_not_tracked",
                        "No tracked external user with this ID exists for this project"));
        ensureBillingCustomerExists(workspaceId, stripeCustomerId);

        Optional<LinkOutcome> existingForIdentity = findActiveLinkByIdentity(externalIdentityId);
        if (existingForIdentity.isPresent()) {
            LinkOutcome existing = existingForIdentity.get();
            if (existing.stripeCustomerId().equals(stripeCustomerId)) {
                return existing;
            }
            throw new StripeCustomerLinkException(
                    HttpStatus.CONFLICT,
                    "external_user_already_linked",
                    "This external user is already linked to a different Stripe customer");
        }

        Optional<LinkOutcome> existingForCustomer = findActiveLinkByCustomer(workspaceId, stripeCustomerId);
        if (existingForCustomer.isPresent()) {
            throw new StripeCustomerLinkException(
                    HttpStatus.CONFLICT,
                    "stripe_customer_already_linked",
                    "This Stripe customer is already linked to a different external user");
        }

        try {
            Optional<LinkOutcome> inserted =
                    tryInsertLink(workspaceId, projectId, externalIdentityId, externalUserId, stripeCustomerId);
            if (inserted.isPresent()) {
                return inserted.get();
            }
            // Lost a race on the active-identity unique index: a concurrent request linked this
            // identity between the check above and this insert. Resolve deterministically instead of
            // reporting a generic conflict, so an identical concurrent retry still comes back idempotent.
            LinkOutcome concurrent = findActiveLinkByIdentity(externalIdentityId)
                    .orElseThrow(() -> new IllegalStateException("Expected a concurrently inserted active link"));
            if (concurrent.stripeCustomerId().equals(stripeCustomerId)) {
                return concurrent;
            }
            throw new StripeCustomerLinkException(
                    HttpStatus.CONFLICT,
                    "external_user_already_linked",
                    "This external user is already linked to a different Stripe customer");
        } catch (DataIntegrityViolationException raceLostOnCustomerIndex) {
            // The other partial unique index (workspace_id, stripe_customer_id) is not targeted by
            // this statement's ON CONFLICT clause, so a concurrent claim on the same Stripe customer
            // by a different identity surfaces here instead.
            throw new StripeCustomerLinkException(
                    HttpStatus.CONFLICT,
                    "stripe_customer_already_linked",
                    "This Stripe customer was linked concurrently to a different external user");
        }
    }

    public Optional<LinkOutcome> activeLink(UUID workspaceId, UUID projectId, String externalUserId) {
        workspaceContext.requireManager(workspaceId);
        ensureProjectOwnedByWorkspace(workspaceId, projectId);
        UUID externalIdentityId = resolveExternalIdentity(workspaceId, projectId, externalUserId)
                .orElseThrow(() -> new StripeCustomerLinkException(
                        HttpStatus.NOT_FOUND,
                        "external_user_not_tracked",
                        "No tracked external user with this ID exists for this project"));
        return findActiveLinkByIdentity(externalIdentityId);
    }

    private void ensureProjectOwnedByWorkspace(UUID workspaceId, UUID projectId) {
        boolean owned = jdbc.sql("SELECT EXISTS (SELECT 1 FROM projects WHERE id = :projectId AND workspace_id = :workspaceId)")
                .param("projectId", projectId)
                .param("workspaceId", workspaceId)
                .query(Boolean.class)
                .single();
        if (!owned) {
            throw new StripeCustomerLinkException(HttpStatus.NOT_FOUND, "project_not_found", "Project not found");
        }
    }

    private void ensureBillingCustomerExists(UUID workspaceId, String stripeCustomerId) {
        boolean exists = jdbc.sql(
                        "SELECT EXISTS (SELECT 1 FROM billing_customers WHERE workspace_id = :workspaceId AND stripe_customer_id = :stripeCustomerId)")
                .param("workspaceId", workspaceId)
                .param("stripeCustomerId", stripeCustomerId)
                .query(Boolean.class)
                .single();
        if (!exists) {
            throw new StripeCustomerLinkException(
                    HttpStatus.NOT_FOUND,
                    "stripe_customer_not_found",
                    "No Stripe customer with this ID has been observed in this workspace's billing ledger");
        }
    }

    private Optional<UUID> resolveExternalIdentity(UUID workspaceId, UUID projectId, String externalUserId) {
        return jdbc.sql(
                        """
                        SELECT id FROM external_identities
                        WHERE workspace_id = :workspaceId AND project_id = :projectId AND external_user_id = :externalUserId
                        """)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("externalUserId", externalUserId)
                .query(UUID.class)
                .optional();
    }

    private Optional<LinkOutcome> findActiveLinkByIdentity(UUID externalIdentityId) {
        return jdbc.sql(
                        """
                        SELECT id, workspace_id, project_id, external_user_id, stripe_customer_id,
                               evidence_source, evidence_reference, linked_by_subject_id, created_at
                        FROM stripe_customer_links
                        WHERE external_identity_id = :externalIdentityId AND superseded_at IS NULL
                        """)
                .param("externalIdentityId", externalIdentityId)
                .query(LinkOutcome.ROW_MAPPER)
                .optional();
    }

    private Optional<LinkOutcome> findActiveLinkByCustomer(UUID workspaceId, String stripeCustomerId) {
        return jdbc.sql(
                        """
                        SELECT id, workspace_id, project_id, external_user_id, stripe_customer_id,
                               evidence_source, evidence_reference, linked_by_subject_id, created_at
                        FROM stripe_customer_links
                        WHERE workspace_id = :workspaceId AND stripe_customer_id = :stripeCustomerId AND superseded_at IS NULL
                        """)
                .param("workspaceId", workspaceId)
                .param("stripeCustomerId", stripeCustomerId)
                .query(LinkOutcome.ROW_MAPPER)
                .optional();
    }

    private Optional<LinkOutcome> tryInsertLink(
            UUID workspaceId, UUID projectId, UUID externalIdentityId, String externalUserId, String stripeCustomerId) {
        String actor = workspaceContext.subjectId();
        return jdbc.sql(
                        """
                        INSERT INTO stripe_customer_links
                            (id, workspace_id, project_id, external_identity_id, external_user_id, stripe_customer_id,
                             evidence_source, evidence_reference, linked_by_subject_id)
                        VALUES (:id, :workspaceId, :projectId, :externalIdentityId, :externalUserId, :stripeCustomerId,
                                :evidenceSource, :evidenceReference, :linkedBy)
                        ON CONFLICT (external_identity_id) WHERE superseded_at IS NULL DO NOTHING
                        RETURNING id, workspace_id, project_id, external_user_id, stripe_customer_id,
                                  evidence_source, evidence_reference, linked_by_subject_id, created_at
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("externalIdentityId", externalIdentityId)
                .param("externalUserId", externalUserId)
                .param("stripeCustomerId", stripeCustomerId)
                .param("evidenceSource", EVIDENCE_SOURCE_EXPLICIT_API)
                .param("evidenceReference", EVIDENCE_REFERENCE_EXPLICIT_API)
                .param("linkedBy", actor)
                .query(LinkOutcome.ROW_MAPPER)
                .optional();
    }

    public record LinkOutcome(
            UUID id,
            UUID workspaceId,
            UUID projectId,
            String externalUserId,
            String stripeCustomerId,
            String evidenceSource,
            String evidenceReference,
            String linkedBySubjectId,
            OffsetDateTime createdAt) {

        static final org.springframework.jdbc.core.RowMapper<LinkOutcome> ROW_MAPPER = (rs, rowNum) -> new LinkOutcome(
                rs.getObject("id", UUID.class),
                rs.getObject("workspace_id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getString("external_user_id"),
                rs.getString("stripe_customer_id"),
                rs.getString("evidence_source"),
                rs.getString("evidence_reference"),
                rs.getString("linked_by_subject_id"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
