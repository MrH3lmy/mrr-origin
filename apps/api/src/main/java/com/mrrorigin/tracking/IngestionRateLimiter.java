package com.mrrorigin.tracking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * DB-backed, multi-instance-safe fixed-window rate limiter for the public ingestion endpoint
 * (#65), scoped per ingestion key. A single Postgres row per (ingestion key, window) is the
 * source of truth, so every API instance counts against the same value regardless of which one
 * handled a given request -- there is no in-process counter to keep synchronized or lose on
 * restart.
 *
 * <p>{@link #check} runs in its own new transaction ({@link Propagation#REQUIRES_NEW}), matching
 * {@link TrackingIngestionFailureRecorder}: the increment must commit and stay committed even when
 * the request it is counting is itself rejected or later rolled back downstream, because the
 * budget is spent by the attempt reaching the server, not by its eventual outcome.
 */
@Service
class IngestionRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final IngestionRateLimitProperties properties;

    IngestionRateLimiter(JdbcClient jdbc, Clock clock, IngestionRateLimitProperties properties) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Decision check(UUID ingestionKeyId, UUID workspaceId, UUID projectId) {
        long windowSeconds = WINDOW.getSeconds();
        Instant now = clock.instant();
        long windowStartEpoch = now.getEpochSecond() - (now.getEpochSecond() % windowSeconds);
        OffsetDateTime windowStart = OffsetDateTime.ofInstant(Instant.ofEpochSecond(windowStartEpoch), ZoneOffset.UTC);

        int requestCount = jdbc.sql("""
                        INSERT INTO tracking_ingestion_rate_limit_windows
                            (ingestion_key_id, workspace_id, project_id, window_start, request_count)
                        VALUES (:keyId, :workspaceId, :projectId, :windowStart, 1)
                        ON CONFLICT (ingestion_key_id, window_start)
                        DO UPDATE SET request_count = tracking_ingestion_rate_limit_windows.request_count + 1
                        RETURNING request_count
                        """)
                .param("keyId", ingestionKeyId)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("windowStart", windowStart)
                .query(Integer.class)
                .single();

        jdbc.sql("""
                        DELETE FROM tracking_ingestion_rate_limit_windows
                        WHERE ingestion_key_id = :keyId AND window_start < :windowStart
                        """)
                .param("keyId", ingestionKeyId)
                .param("windowStart", windowStart)
                .update();

        long retryAfterSeconds = windowStartEpoch + windowSeconds - now.getEpochSecond();
        return new Decision(requestCount <= properties.requestsPerMinute(), Math.max(retryAfterSeconds, 1));
    }

    record Decision(boolean allowed, long retryAfterSeconds) {}
}
