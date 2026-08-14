package com.mrrorigin.attribution;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * A deterministic-only suggestion for repairing an unattributed Stripe customer, per #20's product
 * scope: "the inbox may suggest a repair only when the suggestion itself is deterministic from
 * existing explicit data" and never from email/name/domain similarity, timestamp proximity, IP
 * matching, or any probabilistic/behavioral scoring (all permanently rejected by ADR-0005).
 *
 * <p>The only qualifying signal available today is the Stripe customer's own prior history in
 * {@code stripe_customer_links}: someone, at some point, explicitly and authoritatively asserted
 * "this external identity is this Stripe customer" (an {@code EXPLICIT_API} row, active or later
 * superseded by an unrelated correction). If exactly one distinct external identity from that
 * history is currently unclaimed (no active link of its own), it uniquely establishes the correct
 * link and is surfaced. Zero candidates, or more than one distinct unclaimed candidate, is
 * ambiguous or unsupported and yields no suggestion -- never a guess.
 */
@Service
public class DeterministicRepairSuggestionService {
    private final JdbcClient db;

    public DeterministicRepairSuggestionService(JdbcClient db) {
        this.db = db;
    }

    public Optional<Suggestion> suggest(UUID workspaceId, UUID projectId, String stripeCustomerId) {
        if (workspaceId == null || projectId == null || stripeCustomerId == null || stripeCustomerId.isBlank()) {
            throw new IllegalArgumentException("workspace, project and Stripe customer are required");
        }
        List<Suggestion> candidates = db.sql(
                        """
                        SELECT DISTINCT scl.external_identity_id, identity.external_user_id, scl.id AS evidence_link_id
                        FROM stripe_customer_links scl
                        JOIN external_identities identity ON identity.id = scl.external_identity_id
                        WHERE scl.workspace_id = :w
                          AND scl.project_id = :p
                          AND scl.stripe_customer_id = :c
                          AND scl.evidence_source = 'EXPLICIT_API'
                          AND NOT EXISTS (
                              SELECT 1 FROM stripe_customer_links active
                              WHERE active.external_identity_id = scl.external_identity_id
                                AND active.superseded_at IS NULL
                          )
                        """)
                .param("w", workspaceId)
                .param("p", projectId)
                .param("c", stripeCustomerId)
                .query((rs, rowNum) -> new Suggestion(
                        rs.getObject("external_identity_id", UUID.class),
                        rs.getString("external_user_id"),
                        rs.getObject("evidence_link_id", UUID.class)))
                .list();
        return candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
    }

    /**
     * {@code evidenceLinkId} is the historical (possibly superseded) {@code stripe_customer_links}
     * row this suggestion is grounded in -- inspectable evidence, never an inferred score.
     */
    public record Suggestion(UUID externalIdentityId, String externalUserId, UUID evidenceLinkId) {}
}
