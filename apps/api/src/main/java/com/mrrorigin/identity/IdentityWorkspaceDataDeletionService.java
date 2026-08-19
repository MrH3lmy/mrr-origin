package com.mrrorigin.identity;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The identity module's own slice of #62's cross-module workspace deletion: hard-deletes every table
 * this module owns, in the dependency-safe order their foreign keys require --
 * {@code stripe_customer_link_repair_audit_log} before {@code stripe_customer_links} (V17's
 * {@code ON DELETE RESTRICT}), then {@code visitor_aliases} before {@code external_identities} (V6's
 * {@code ON DELETE RESTRICT}). By the time the orchestrator reaches this phase, the attribution phase
 * has already cleared {@code customer_attribution_results}, so nothing still restricts
 * {@code stripe_customer_links} from this module's own side.
 *
 * <p>One call sweeps at most one table: it tries each owned table in order and deletes from the first
 * one that still has rows for this workspace, so a caller repeatedly invoking {@link #deleteBatch}
 * drains every table in turn. {@code exhausted()} is true only once a call finds every owned table
 * already empty -- stateless and idempotent, matching every other module's deletion service (see
 * {@code ReportingWorkspaceDataDeletionService}'s Javadoc for why no separate checkpoint is needed).
 */
@Service
public class IdentityWorkspaceDataDeletionService {

    private final JdbcClient jdbc;

    IdentityWorkspaceDataDeletionService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public BatchResult deleteBatch(UUID workspaceId, int maxRows) {
        int deleted = deleteBounded("stripe_customer_link_repair_audit_log", workspaceId, maxRows);
        if (deleted == 0) {
            deleted = deleteBounded("stripe_customer_links", workspaceId, maxRows);
        }
        if (deleted == 0) {
            deleted = deleteBounded("visitor_aliases", workspaceId, maxRows);
        }
        if (deleted == 0) {
            deleted = deleteBounded("external_identities", workspaceId, maxRows);
        }
        return new BatchResult(deleted, deleted == 0);
    }

    private int deleteBounded(String table, UUID workspaceId, int maxRows) {
        return jdbc.sql("""
                        DELETE FROM %s
                        WHERE ctid IN (SELECT ctid FROM %s WHERE workspace_id = :workspaceId LIMIT :maxRows)
                        """.formatted(table, table))
                .param("workspaceId", workspaceId)
                .param("maxRows", maxRows)
                .update();
    }

    public record BatchResult(int rowsDeleted, boolean exhausted) {}
}
