package com.mrrorigin.attribution;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The attribution module's own slice of #62's cross-module workspace deletion: hard-deletes every
 * table this module owns -- {@code customer_attribution_results} and {@code
 * attribution_recalculation_runs} (V11's recalculation checkpoint, easy to miss since it is
 * operational bookkeeping rather than a derived result, but still workspace-owned data). Unlike the
 * project-scoped {@code ProjectDataDeletionService} (#8), which must skip touchpoints/identities
 * still referenced by {@code customer_attribution_results} (V10's {@code ON DELETE RESTRICT}), a full
 * workspace deletion clears it unconditionally and first -- clearing the RESTRICT-referencing side
 * before the orchestrator ever reaches the identity or tracking phases, so nothing downstream is ever
 * skipped or blocked. {@code attribution_recalculation_runs} has no RESTRICT dependents (V11's only
 * foreign key is a plain {@code ON DELETE CASCADE} to {@code projects}), so its order relative to the
 * results table does not matter.
 *
 * <p>One call sweeps at most one table: it tries each owned table in order and deletes from the first
 * one that still has rows for this workspace, so a caller repeatedly invoking {@link #deleteBatch}
 * drains every table in turn. {@code exhausted()} is true only once a call finds every owned table
 * already empty -- stateless and idempotent, matching every other module's deletion service (see
 * {@code ReportingWorkspaceDataDeletionService}'s Javadoc for why no separate checkpoint is needed).
 */
@Service
public class AttributionWorkspaceDataDeletionService {

    private static final List<String> TABLES_IN_ORDER =
            List.of("customer_attribution_results", "attribution_recalculation_runs");

    private final JdbcClient jdbc;

    AttributionWorkspaceDataDeletionService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public BatchResult deleteBatch(UUID workspaceId, int maxRows) {
        for (String table : TABLES_IN_ORDER) {
            int deleted = deleteBounded(table, workspaceId, maxRows);
            if (deleted > 0) {
                return new BatchResult(deleted, false);
            }
        }
        return new BatchResult(0, true);
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
