package com.mrrorigin.reporting;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reporting module's own slice of #62's cross-module workspace deletion: hard-deletes
 * {@code export_audit_log}, the only table this module owns. Exposed as a public application service
 * so the cross-module orchestrator (the {@code workspacelifecycle} module) can drive it without ever
 * reaching into this module's persistence internals directly, per ARCHITECTURE.md's module-ownership
 * rule and #62's "module-owned deletion services" requirement.
 *
 * <p>Stateless and idempotent across calls: each {@link #deleteBatch} call re-checks this module's one
 * owned table from scratch rather than tracking its own checkpoint, so the orchestrator's persisted
 * phase (which only advances once a call reports {@code exhausted() == true}) is the only checkpoint
 * needed -- a crash mid-sweep is resumed correctly by simply calling this method again.
 */
@Service
public class ReportingWorkspaceDataDeletionService {

    private final JdbcClient jdbc;

    ReportingWorkspaceDataDeletionService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public BatchResult deleteBatch(UUID workspaceId, int maxRows) {
        int deleted = jdbc.sql("""
                        DELETE FROM export_audit_log
                        WHERE ctid IN (SELECT ctid FROM export_audit_log WHERE workspace_id = :workspaceId LIMIT :maxRows)
                        """)
                .param("workspaceId", workspaceId)
                .param("maxRows", maxRows)
                .update();
        return new BatchResult(deleted, deleted < maxRows);
    }

    public record BatchResult(int rowsDeleted, boolean exhausted) {}
}
