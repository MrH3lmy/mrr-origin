package com.mrrorigin.workspacelifecycle;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Purges {@code workspace_deletion_tombstones} rows older than 30 days (#62's accepted contract).
 * That table already holds nothing but the tombstone contract's fields -- request id, workspace UUID,
 * status, timestamps -- see V22's migration comment, so this sweep only has to delete by age,
 * mirroring {@code WeeklySummaryDispatchService#retentionTick}'s cutoff-based cleanup rather than
 * needing its own distributed lock: every statement here is an unconditional, cutoff-scoped {@code
 * DELETE}, so two instances racing the same tick simply divide the same work instead of conflicting.
 */
@Service
class WorkspaceTombstonePurgeService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceTombstonePurgeService.class);
    private static final long TOMBSTONE_RETENTION_DAYS = 30;

    private final JdbcClient jdbc;
    private final Clock clock;

    WorkspaceTombstonePurgeService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${mrrorigin.workspacelifecycle.tombstone-purge.fixed-delay:P1D}",
            initialDelayString = "${mrrorigin.workspacelifecycle.tombstone-purge.initial-delay:PT15M}")
    void purgeExpiredTombstones() {
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minusDays(TOMBSTONE_RETENTION_DAYS);
        int deleted = jdbc.sql("DELETE FROM workspace_deletion_tombstones WHERE completed_at < :cutoff")
                .param("cutoff", cutoff)
                .update();
        if (deleted > 0) {
            log.info("Purged {} workspace deletion tombstone(s) older than {} days.", deleted, TOMBSTONE_RETENTION_DAYS);
        }
    }
}
