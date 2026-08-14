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
 * <p><b>Only currently-active explicit evidence qualifies -- superseded rows never do.</b> An
 * earlier design surfaced superseded {@code EXPLICIT_API} rows naming this customer, reasoning that
 * "someone once explicitly asserted this pairing." That is wrong: supersession in this codebase
 * <em>is</em> a correction record (see {@link
 * com.mrrorigin.identity.StripeCustomerLinkingService#repair}) -- a row becomes superseded only
 * because a workspace manager explicitly re-pointed the identity or the customer elsewhere. Treating
 * the superseded row as if it were still trustworthy would mean recommending exactly the mapping a
 * human already corrected away, which violates {@code PRODUCT.md}'s "evidence over false precision"
 * principle and ADR-0005's requirement that confidence come from a live, inspectable fact rather
 * than a stale one. A row that is still active for a different customer is likewise not evidence for
 * <em>this</em> customer.
 *
 * <p>Under that rule, a customer whose reason is {@code NO_ACTIVE_LINK} -- by definition, no active
 * {@code stripe_customer_links} row names it -- can never have qualifying evidence today: the only
 * currently-implemented evidence source is {@code EXPLICIT_API}, and any {@code EXPLICIT_API} row
 * naming this exact customer would itself be the active link the customer doesn't have. This mirrors
 * ADR-0005's own note that the Verified ({@code STRIPE_METADATA}) and Moderate tiers "are specified
 * but not yet backed by data" -- until one of those lands, this method is expected to always return
 * empty, which is the correct, honest V1 behavior per this issue's "if deterministic evidence is
 * insufficient, return {@code NO_DETERMINISTIC_REPAIR_AVAILABLE}" instruction. It is kept as a real,
 * general query (not hardcoded empty) so a future evidence writer needs no change here.
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
                        SELECT scl.external_identity_id, identity.external_user_id, scl.id AS evidence_link_id
                        FROM stripe_customer_links scl
                        JOIN external_identities identity ON identity.id = scl.external_identity_id
                        WHERE scl.workspace_id = :w
                          AND scl.project_id = :p
                          AND scl.stripe_customer_id = :c
                          AND scl.evidence_source = 'EXPLICIT_API'
                          AND scl.superseded_at IS NULL
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
     * {@code evidenceLinkId} is the active {@code stripe_customer_links} row this suggestion is
     * grounded in -- inspectable evidence, never an inferred score.
     */
    public record Suggestion(UUID externalIdentityId, String externalUserId, UUID evidenceLinkId) {}
}
