package com.mrrorigin.attribution;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Append-only audit trail (V17) for #20's manual Stripe-customer-link repairs. A row is written
 * only when {@link CustomerLinkRepairService} actually creates or corrects an active link -- a pure
 * idempotent replay of an already-correct request writes nothing here, matching V17's migration
 * comment. Rows are never updated or deleted: a later repair appends a new row and prior rows stay
 * exactly as written, so "what changed after the repair" is reconstructable without re-deriving it
 * from {@code stripe_customer_links.superseded_by_id} chains.
 */
@Service
public class CustomerLinkRepairAuditService {
    private final JdbcClient db;
    private final Clock clock;

    public CustomerLinkRepairAuditService(JdbcClient db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    void record(
            UUID workspaceId,
            UUID projectId,
            String stripeCustomerId,
            String externalUserId,
            String actionType,
            UUID newLinkId,
            UUID previousIdentityLinkId,
            UUID previousCustomerLinkId,
            String actorSubjectId) {
        db.sql(
                        """
                        INSERT INTO stripe_customer_link_repair_audit_log
                            (id, workspace_id, project_id, stripe_customer_id, external_user_id, action_type,
                             new_link_id, previous_identity_link_id, previous_customer_link_id, actor_subject_id,
                             created_at)
                        VALUES (:id, :w, :p, :c, :externalUserId, :actionType, :newLinkId, :previousIdentityLinkId,
                                :previousCustomerLinkId, :actor, :at)
                        """)
                .param("id", UUID.randomUUID())
                .param("w", workspaceId)
                .param("p", projectId)
                .param("c", stripeCustomerId)
                .param("externalUserId", externalUserId)
                .param("actionType", actionType)
                .param("newLinkId", newLinkId)
                .param("previousIdentityLinkId", previousIdentityLinkId)
                .param("previousCustomerLinkId", previousCustomerLinkId)
                .param("actor", actorSubjectId)
                .param("at", OffsetDateTime.now(clock))
                .update();
    }

    /** Audit history for one Stripe customer, most recent first, bounded and stably ordered. */
    public List<AuditEntry> history(UUID workspaceId, UUID projectId, String stripeCustomerId, int limit) {
        if (workspaceId == null || projectId == null || stripeCustomerId == null || stripeCustomerId.isBlank()) {
            throw new IllegalArgumentException("workspace, project and Stripe customer are required");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return db.sql(
                        """
                        SELECT id, external_user_id, action_type, new_link_id, previous_identity_link_id,
                               previous_customer_link_id, actor_subject_id, created_at
                        FROM stripe_customer_link_repair_audit_log
                        WHERE workspace_id = :w AND project_id = :p AND stripe_customer_id = :c
                        ORDER BY created_at DESC, id DESC
                        LIMIT :limit
                        """)
                .param("w", workspaceId)
                .param("p", projectId)
                .param("c", stripeCustomerId)
                .param("limit", limit)
                .query((rs, rowNum) -> new AuditEntry(
                        rs.getObject("id", UUID.class),
                        rs.getString("external_user_id"),
                        rs.getString("action_type"),
                        rs.getObject("new_link_id", UUID.class),
                        rs.getObject("previous_identity_link_id", UUID.class),
                        rs.getObject("previous_customer_link_id", UUID.class),
                        rs.getString("actor_subject_id"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    public record AuditEntry(
            UUID id,
            String externalUserId,
            String actionType,
            UUID newLinkId,
            UUID previousIdentityLinkId,
            UUID previousCustomerLinkId,
            String actorSubjectId,
            OffsetDateTime createdAt) {}
}
