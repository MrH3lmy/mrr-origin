package com.mrrorigin.workspace;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Purges {@code workspace_deletion_tombstones} rows older than the accepted #62/#27 30-day retention
 * period. A plain cutoff delete needs no database lease the way {@code WeeklySummaryDispatchService}'s
 * stateful jobs do (per {@code ARCHITECTURE.md}'s "scheduled work uses database leases" rule): running
 * on two instances at once, or twice in the same window, only ever deletes already-gone rows again,
 * which is a harmless no-op rather than double-processing.
 */
@Component
class WorkspaceDeletionTombstonePurgeJob {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceDeletionTombstonePurgeJob.class);
    private static final int RETENTION_DAYS = 30;

    private final JdbcClient jdbc;
    private final Clock clock;

    WorkspaceDeletionTombstonePurgeJob(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${mrrorigin.workspace.deletion-tombstone-purge.fixed-delay:P1D}",
            initialDelayString = "${mrrorigin.workspace.deletion-tombstone-purge.initial-delay:PT15M}")
    @Transactional
    void tick() {
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minusDays(RETENTION_DAYS);
        int purged = jdbc.sql("DELETE FROM workspace_deletion_tombstones WHERE created_at < :cutoff")
                .param("cutoff", cutoff)
                .update();
        if (purged > 0) {
            log.info("Purged {} workspace deletion tombstone(s) older than {} days", purged, RETENTION_DAYS);
        }
    }
}
