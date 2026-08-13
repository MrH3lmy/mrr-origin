package com.mrrorigin.tracking;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mrrorigin.identity.IdentityLinkingService;

/**
 * Safe, project-scoped installation diagnostics (#8): whether traffic has ever been received, the
 * most recent successfully accepted event, recent public-ingestion rejections by kind, and identity
 * coverage -- everything a founder needs to answer "is my tracker installed correctly, and if not,
 * why", without ever exposing an ingestion secret, a secret hash, or another project's data.
 *
 * <p>{@link DiagnosticState} captures one primary, deterministic diagnosis:
 *
 * <ul>
 *   <li>{@code RECEIVING} -- at least one event has ever been accepted for this project. Traffic is
 *       flowing; the report's failure counts/timestamps are still returned as supplementary detail
 *       (e.g. "receiving, but N blocked-origin attempts were also seen").
 *   <li>Otherwise, the most recent of the three failure kinds (if any) explains why nothing has been
 *       accepted yet: {@code INVALID_KEY}, {@code BLOCKED_ORIGIN}, or {@code INVALID_PAYLOAD}.
 *   <li>{@code NO_TRAFFIC} -- nothing has ever been accepted, and no rejection has been recorded
 *       either: the tracker most likely never sent anything at all.
 * </ul>
 */
@Service
public class TrackingProjectDiagnosticsService {

    private final JdbcClient jdbc;
    private final IdentityLinkingService identities;
    private final Clock clock;

    TrackingProjectDiagnosticsService(JdbcClient jdbc, IdentityLinkingService identities, Clock clock) {
        this.jdbc = jdbc;
        this.identities = identities;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ProjectDiagnosticsReport report(UUID workspaceId, UUID projectId) {
        LastAcceptedEvent lastAccepted = lastAcceptedEvent(workspaceId, projectId);
        boolean everReceivedTraffic = lastAccepted != null;

        FailureSummary blockedOrigin = failureSummary(workspaceId, projectId, "BLOCKED_ORIGIN");
        FailureSummary invalidKey = failureSummary(workspaceId, projectId, "INVALID_KEY");
        FailureSummary invalidPayload = failureSummary(workspaceId, projectId, "INVALID_PAYLOAD");

        DiagnosticState state = state(everReceivedTraffic, blockedOrigin, invalidKey, invalidPayload);

        long totalVisitors = countVisitors(workspaceId, projectId);
        long identifiedVisitors = identities.countIdentifiedVisitors(workspaceId, projectId);

        return new ProjectDiagnosticsReport(
                workspaceId,
                projectId,
                state,
                everReceivedTraffic,
                lastAccepted == null ? null : lastAccepted.occurredAt(),
                lastAccepted == null ? null : lastAccepted.eventType(),
                blockedOrigin,
                invalidKey,
                invalidPayload,
                new IdentityCoverage(totalVisitors, identifiedVisitors),
                OffsetDateTime.now(clock));
    }

    private static DiagnosticState state(
            boolean everReceivedTraffic, FailureSummary blockedOrigin, FailureSummary invalidKey, FailureSummary invalidPayload) {
        if (everReceivedTraffic) {
            return DiagnosticState.RECEIVING;
        }
        FailureSummary mostRecent = null;
        DiagnosticState mostRecentState = null;
        for (Map.Entry<DiagnosticState, FailureSummary> candidate : List.of(
                Map.entry(DiagnosticState.INVALID_KEY, invalidKey),
                Map.entry(DiagnosticState.BLOCKED_ORIGIN, blockedOrigin),
                Map.entry(DiagnosticState.INVALID_PAYLOAD, invalidPayload))) {
            FailureSummary summary = candidate.getValue();
            if (summary.lastOccurredAt() != null
                    && (mostRecent == null || summary.lastOccurredAt().isAfter(mostRecent.lastOccurredAt()))) {
                mostRecent = summary;
                mostRecentState = candidate.getKey();
            }
        }
        return mostRecentState == null ? DiagnosticState.NO_TRAFFIC : mostRecentState;
    }

    private LastAcceptedEvent lastAcceptedEvent(UUID workspaceId, UUID projectId) {
        return jdbc.sql("""
                        SELECT event_type, occurred_at FROM tracking_event_envelopes
                        WHERE workspace_id = :workspaceId AND project_id = :projectId
                        ORDER BY received_at DESC
                        LIMIT 1
                        """)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .query((rs, rowNum) -> new LastAcceptedEvent(
                        rs.getString("event_type"), rs.getObject("occurred_at", OffsetDateTime.class)))
                .optional()
                .orElse(null);
    }

    private FailureSummary failureSummary(UUID workspaceId, UUID projectId, String kind) {
        return jdbc.sql("""
                        SELECT COUNT(*) AS failure_count, MAX(occurred_at) AS last_occurred_at
                        FROM tracking_ingestion_failures
                        WHERE workspace_id = :workspaceId AND project_id = :projectId AND kind = :kind
                        """)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("kind", kind)
                .query((rs, rowNum) -> new FailureSummary(
                        rs.getLong("failure_count"), rs.getObject("last_occurred_at", OffsetDateTime.class)))
                .single();
    }

    private long countVisitors(UUID workspaceId, UUID projectId) {
        return jdbc.sql("SELECT COUNT(*) FROM visitors WHERE workspace_id = :workspaceId AND project_id = :projectId")
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .query(Long.class)
                .single();
    }

    private record LastAcceptedEvent(String eventType, OffsetDateTime occurredAt) {}

    public enum DiagnosticState {
        NO_TRAFFIC,
        BLOCKED_ORIGIN,
        INVALID_KEY,
        INVALID_PAYLOAD,
        RECEIVING
    }

    public record FailureSummary(long count, OffsetDateTime lastOccurredAt) {}

    public record IdentityCoverage(long totalVisitors, long identifiedVisitors) {
        public double identifiedShare() {
            return totalVisitors == 0 ? 0.0 : (double) identifiedVisitors / totalVisitors;
        }
    }

    public record ProjectDiagnosticsReport(
            UUID workspaceId,
            UUID projectId,
            DiagnosticState state,
            boolean everReceivedTraffic,
            OffsetDateTime lastAcceptedEventAt,
            String lastAcceptedEventType,
            FailureSummary blockedOrigin,
            FailureSummary invalidKey,
            FailureSummary invalidPayload,
            IdentityCoverage identityCoverage,
            OffsetDateTime computedAt) {}
}
