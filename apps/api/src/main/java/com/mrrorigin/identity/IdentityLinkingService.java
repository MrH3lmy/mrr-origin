package com.mrrorigin.identity;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.mrrorigin.tracking.IngestionKeyService;

@Service
public class IdentityLinkingService {
    private final JdbcClient jdbc;

    public IdentityLinkingService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Count of distinct visitors this project has ever identified (i.e. rows in {@code visitor_aliases}). */
    public long countIdentifiedVisitors(UUID workspaceId, UUID projectId) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM visitor_aliases
                        WHERE workspace_id = :workspaceId AND project_id = :projectId
                        """)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .query(Long.class)
                .single();
    }

    /**
     * Deletes up to {@code maxRows} of this project's {@code visitor_aliases} rows (unconditional --
     * nothing else references a {@code visitor_aliases} row by foreign key), then up to {@code maxRows}
     * {@code external_identities} rows that are <em>not</em> linked to a Stripe customer ({@code
     * stripe_customer_links.fk_stripe_customer_links_identity}, V8, is {@code ON DELETE RESTRICT}).
     * Aliases are removed first because {@code fk_visitor_aliases_identity} (V6) is also {@code ON
     * DELETE RESTRICT}: an identity can only be deleted once nothing still points to it.
     *
     * <p>An identity that any {@code stripe_customer_links} row still references -- active or
     * superseded -- is Stripe-attribution evidence and is skipped, not force-removed, matching how
     * {@code customer_attribution_results} protects touchpoints (V10). {@link #countProtectedIdentities}
     * reports how many are being left in place once this phase is otherwise exhausted.
     *
     * <p>Both deletes are plain {@code DELETE ... LIMIT}-style bounded statements (via a subquery), so
     * this method is naturally idempotent and safe to call repeatedly by {@code
     * ProjectDataDeletionService} until it reports zero rows removed for both tables.
     */
    public IdentityDeletionBatch deleteIdentityDataBatch(UUID workspaceId, UUID projectId, int maxRows) {
        int aliasesDeleted = jdbc.sql("""
                        DELETE FROM visitor_aliases
                        WHERE id IN (
                            SELECT id FROM visitor_aliases
                            WHERE workspace_id = :workspaceId AND project_id = :projectId
                            LIMIT :maxRows
                        )
                        """)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("maxRows", maxRows)
                .update();
        int identitiesDeleted = jdbc.sql("""
                        DELETE FROM external_identities
                        WHERE id IN (
                            SELECT ei.id FROM external_identities ei
                            WHERE ei.workspace_id = :workspaceId AND ei.project_id = :projectId
                              AND NOT EXISTS (
                                  SELECT 1 FROM stripe_customer_links l WHERE l.external_identity_id = ei.id)
                            LIMIT :maxRows
                        )
                        """)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("maxRows", maxRows)
                .update();
        return new IdentityDeletionBatch(aliasesDeleted, identitiesDeleted);
    }

    /** Remaining {@code external_identities} rows this project cannot delete because a Stripe link protects them. */
    public long countProtectedIdentities(UUID workspaceId, UUID projectId) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM external_identities ei
                        WHERE ei.workspace_id = :workspaceId AND ei.project_id = :projectId
                          AND EXISTS (SELECT 1 FROM stripe_customer_links l WHERE l.external_identity_id = ei.id)
                        """)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .query(Long.class)
                .single();
    }

    public record IdentityDeletionBatch(int aliasesDeleted, int identitiesDeleted) {
        public int totalDeleted() {
            return aliasesDeleted + identitiesDeleted;
        }
    }

    public boolean identify(IngestionKeyService.ResolvedProject project, UUID visitorId,
            String externalUserId, OffsetDateTime identifiedAt) {
        UUID identityId = jdbc.sql("""
                        INSERT INTO external_identities
                            (id, workspace_id, project_id, external_user_id)
                        VALUES (:id, :workspaceId, :projectId, :externalUserId)
                        ON CONFLICT (project_id, external_user_id) DO UPDATE
                        SET external_user_id = EXCLUDED.external_user_id
                        RETURNING id
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", project.workspaceId())
                .param("projectId", project.projectId())
                .param("externalUserId", externalUserId)
                .query(UUID.class)
                .single();

        UUID linkedIdentity = jdbc.sql("""
                        INSERT INTO visitor_aliases
                            (id, workspace_id, project_id, visitor_id, external_identity_id, identified_at)
                        VALUES (:id, :workspaceId, :projectId, :visitorId, :identityId, :identifiedAt)
                        ON CONFLICT (project_id, visitor_id) DO UPDATE
                        SET identified_at = LEAST(visitor_aliases.identified_at, EXCLUDED.identified_at)
                        WHERE visitor_aliases.external_identity_id = EXCLUDED.external_identity_id
                        RETURNING external_identity_id
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", project.workspaceId())
                .param("projectId", project.projectId())
                .param("visitorId", visitorId)
                .param("identityId", identityId)
                .param("identifiedAt", identifiedAt)
                .query(UUID.class)
                .optional()
                .orElse(null);

        if (linkedIdentity == null) {
            return false;
        }
        if (!linkedIdentity.equals(identityId)) {
            throw new IllegalStateException("Identity link returned an unexpected owner");
        }
        return true;
    }
}
