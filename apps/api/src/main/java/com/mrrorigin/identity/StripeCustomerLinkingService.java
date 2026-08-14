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
            boolean inserted = tryInsertLink(workspaceId, projectId, externalIdentityId, stripeCustomerId);
            if (inserted) {
                return findActiveLinkByIdentity(externalIdentityId)
                        .orElseThrow(() -> new IllegalStateException("Inserted Stripe customer link was not readable"));
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

    /**
     * Explicit, workspace-manager-authorized create-or-correct entry point for #20's repair
     * workflow. Unlike {@link #link}, which rejects any conflicting active link outright and never
     * mutates existing rows, this method implements V8's reserved {@code superseded_at}/{@code
     * superseded_by_id} contract: a conflicting active link on either side (the identity already
     * points elsewhere, or the Stripe customer is already claimed by a different identity) is
     * durably superseded by the newly inserted active link in the same transaction, so the prior
     * link remains inspectable rather than being deleted or silently overwritten, per ADR-0002.
     *
     * <p>Still explicit-only: the caller supplies both sides by ID, exactly like {@link #link}. No
     * candidate is guessed, scored, or ranked here.
     *
     * <p>Serializes on a workspace-scoped advisory lock (rather than per-identity/per-customer) so
     * the multi-row supersede-then-insert sequence below can never interleave with a concurrent
     * repair in the same workspace. Repairs are infrequent, manual, authorized actions, so
     * workspace-wide serialization is a deliberate simplicity trade-off, not a throughput path.
     */
    @Transactional
    public RepairOutcome repair(UUID workspaceId, UUID projectId, String externalUserId, String stripeCustomerId) {
        workspaceContext.requireManager(workspaceId);
        ensureProjectOwnedByWorkspace(workspaceId, projectId);
        lockWorkspace(workspaceId);

        UUID externalIdentityId = resolveExternalIdentity(workspaceId, projectId, externalUserId)
                .orElseThrow(() -> new StripeCustomerLinkException(
                        HttpStatus.NOT_FOUND,
                        "external_user_not_tracked",
                        "No tracked external user with this ID exists for this project"));
        ensureBillingCustomerExists(workspaceId, stripeCustomerId);

        Optional<LinkOutcome> identityActive = findActiveLinkByIdentity(externalIdentityId);
        if (identityActive.isPresent() && identityActive.get().stripeCustomerId().equals(stripeCustomerId)) {
            return new RepairOutcome(identityActive.get(), "UNCHANGED", null, null);
        }

        Optional<LinkOutcome> customerActive = findActiveLinkByCustomer(workspaceId, stripeCustomerId);
        if (customerActive.isPresent() && !customerActive.get().projectId().equals(projectId)) {
            throw new StripeCustomerLinkException(
                    HttpStatus.CONFLICT,
                    "stripe_customer_linked_in_different_project",
                    "This Stripe customer is actively linked from a different project and cannot be "
                            + "corrected from this project");
        }

        UUID newLinkId = UUID.randomUUID();
        identityActive.ifPresent(existing -> supersede(existing.id(), newLinkId));
        customerActive
                .filter(existing -> identityActive.isEmpty() || !existing.id().equals(identityActive.get().id()))
                .ifPresent(existing -> supersede(existing.id(), newLinkId));

        insertLinkWithId(newLinkId, workspaceId, projectId, externalIdentityId, stripeCustomerId);
        LinkOutcome created = findActiveLinkByIdentity(externalIdentityId)
                .orElseThrow(() -> new IllegalStateException("Inserted Stripe customer link was not readable"));
        String actionType = identityActive.isPresent() || customerActive.isPresent() ? "CORRECTED" : "CREATED";
        return new RepairOutcome(created, actionType, identityActive.orElse(null), customerActive.orElse(null));
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
                        SELECT link.id, link.workspace_id, link.project_id, identity.external_user_id,
                               link.stripe_customer_id, link.evidence_source, link.evidence_reference,
                               link.linked_by_subject_id, link.created_at
                        FROM stripe_customer_links link
                        JOIN external_identities identity
                          ON identity.id = link.external_identity_id
                         AND identity.project_id = link.project_id
                         AND identity.workspace_id = link.workspace_id
                        WHERE link.external_identity_id = :externalIdentityId
                          AND link.superseded_at IS NULL
                        """)
                .param("externalIdentityId", externalIdentityId)
                .query(LinkOutcome.ROW_MAPPER)
                .optional();
    }

    private Optional<LinkOutcome> findActiveLinkByCustomer(UUID workspaceId, String stripeCustomerId) {
        return jdbc.sql(
                        """
                        SELECT link.id, link.workspace_id, link.project_id, identity.external_user_id,
                               link.stripe_customer_id, link.evidence_source, link.evidence_reference,
                               link.linked_by_subject_id, link.created_at
                        FROM stripe_customer_links link
                        JOIN external_identities identity
                          ON identity.id = link.external_identity_id
                         AND identity.project_id = link.project_id
                         AND identity.workspace_id = link.workspace_id
                        WHERE link.workspace_id = :workspaceId
                          AND link.stripe_customer_id = :stripeCustomerId
                          AND link.superseded_at IS NULL
                        """)
                .param("workspaceId", workspaceId)
                .param("stripeCustomerId", stripeCustomerId)
                .query(LinkOutcome.ROW_MAPPER)
                .optional();
    }

    private boolean tryInsertLink(
            UUID workspaceId, UUID projectId, UUID externalIdentityId, String stripeCustomerId) {
        return jdbc.sql(
                        """
                        INSERT INTO stripe_customer_links
                            (id, workspace_id, project_id, external_identity_id, stripe_customer_id,
                             evidence_source, evidence_reference, linked_by_subject_id)
                        VALUES (:id, :workspaceId, :projectId, :externalIdentityId, :stripeCustomerId,
                                :evidenceSource, :evidenceReference, :linkedBy)
                        ON CONFLICT (external_identity_id) WHERE superseded_at IS NULL DO NOTHING
                        RETURNING id
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("externalIdentityId", externalIdentityId)
                .param("stripeCustomerId", stripeCustomerId)
                .param("evidenceSource", EVIDENCE_SOURCE_EXPLICIT_API)
                .param("evidenceReference", EVIDENCE_REFERENCE_EXPLICIT_API)
                .param("linkedBy", workspaceContext.subjectId())
                .query(UUID.class)
                .optional()
                .isPresent();
    }

    /** Serializes {@link #repair}'s multi-row supersede-then-insert sequence within one workspace. */
    private void lockWorkspace(UUID workspaceId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:workspaceId))")
                .param("workspaceId", workspaceId.toString())
                .query((rs, rowNum) -> 0)
                .single();
    }

    /**
     * Marks {@code oldLinkId} superseded by {@code newLinkId} in one statement, satisfying
     * V8's {@code chk_stripe_customer_links_supersession_pair} check (both columns must become
     * non-null together) even though {@code newLinkId} is not yet a row -- {@code
     * fk_stripe_customer_links_superseded_by} is {@code DEFERRABLE INITIALLY DEFERRED}, so it is only
     * validated at commit, by which point {@link #insertLinkWithId} has inserted it. Guarded by
     * {@link #repair}'s workspace advisory lock, so the affected row is always still active here.
     */
    private void supersede(UUID oldLinkId, UUID newLinkId) {
        int updated = jdbc.sql(
                        """
                        UPDATE stripe_customer_links
                        SET superseded_at = CURRENT_TIMESTAMP, superseded_by_id = :newLinkId
                        WHERE id = :oldLinkId AND superseded_at IS NULL
                        """)
                .param("newLinkId", newLinkId)
                .param("oldLinkId", oldLinkId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Expected to supersede exactly one active link");
        }
    }

    private void insertLinkWithId(
            UUID id, UUID workspaceId, UUID projectId, UUID externalIdentityId, String stripeCustomerId) {
        jdbc.sql(
                        """
                        INSERT INTO stripe_customer_links
                            (id, workspace_id, project_id, external_identity_id, stripe_customer_id,
                             evidence_source, evidence_reference, linked_by_subject_id)
                        VALUES (:id, :workspaceId, :projectId, :externalIdentityId, :stripeCustomerId,
                                :evidenceSource, :evidenceReference, :linkedBy)
                        """)
                .param("id", id)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("externalIdentityId", externalIdentityId)
                .param("stripeCustomerId", stripeCustomerId)
                .param("evidenceSource", EVIDENCE_SOURCE_EXPLICIT_API)
                .param(
                        "evidenceReference",
                        "explicit server-side repair call by an authenticated workspace manager")
                .param("linkedBy", workspaceContext.subjectId())
                .update();
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

    /**
     * Result of {@link #repair}. {@code actionType} is {@code UNCHANGED} when the requested pair was
     * already the active link (pure idempotent replay -- no row was written), {@code CREATED} when
     * neither side had an active link, or {@code CORRECTED} when at least one prior active link was
     * superseded. {@code previousIdentityLink}/{@code previousCustomerLink} are the specific rows
     * that were superseded (or null if that side had no conflict), letting a caller identify exactly
     * which other Stripe customer, if any, needs its derived attribution recalculated.
     */
    public record RepairOutcome(
            LinkOutcome link, String actionType, LinkOutcome previousIdentityLink, LinkOutcome previousCustomerLink) {}
}
