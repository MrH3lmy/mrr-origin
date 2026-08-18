package com.mrrorigin.attribution;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The attribution module's own slice of #62's cross-module workspace deletion: hard-deletes
 * {@code customer_attribution_results}, the only table this module owns. Unlike the project-scoped
 * {@code ProjectDataDeletionService} (#8), which must skip touchpoints/identities still referenced by
 * this very table (V10's {@code ON DELETE RESTRICT}), a full workspace deletion clears
 * {@code customer_attribution_results} unconditionally and first -- clearing the RESTRICT-referencing
 * side before the orchestrator ever reaches the identity or tracking phases, so nothing downstream is
 * ever skipped or blocked.
 *
 * <p>Stateless and idempotent across calls, matching every other module's deletion service: see
 * {@code ReportingWorkspaceDataDeletionService}'s Javadoc for why no separate checkpoint is needed.
 */
@Service
public class AttributionWorkspaceDataDeletionService {

    private final JdbcClient jdbc;

    AttributionWorkspaceDataDeletionService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public BatchResult deleteBatch(UUID workspaceId, int maxRows) {
        int deleted = jdbc.sql("""
                        DELETE FROM customer_attribution_results
                        WHERE ctid IN (
                            SELECT ctid FROM customer_attribution_results WHERE workspace_id = :workspaceId LIMIT :maxRows
                        )
                        """)
                .param("workspaceId", workspaceId)
                .param("maxRows", maxRows)
                .update();
        return new BatchResult(deleted, deleted < maxRows);
    }

    public record BatchResult(int rowsDeleted, boolean exhausted) {}
}
