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
