package com.mrrorigin.tracking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

import com.mrrorigin.identity.IdentityLinkingService;

@Service
public class EventIngestionService {
    private static final HexFormat HEX = HexFormat.of();

    private final JdbcClient jdbc;
    private final ObjectMapper canonicalMapper;
    private final Clock clock;
    private final IdentityLinkingService identities;

    public EventIngestionService(JdbcClient jdbc, ObjectMapper objectMapper, Clock clock,
            IdentityLinkingService identities) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.identities = identities;
        this.canonicalMapper = objectMapper.rebuild()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
    }

    @Transactional
    public EventIngestionResponse ingest(
            IngestionKeyService.ResolvedProject project, EventIngestionRequest request) {
        if (request.version() != 1) {
            throw new EventIngestionException(HttpStatus.BAD_REQUEST, "unsupported_version", "Only envelope version 1 is supported");
        }
        ensureDistinctEventIds(request);
        String requestHash = requestHash(request);
        lockProject(project.projectId());
        ExistingBatch existing = findBatch(project.projectId(), request.batchId());
        if (existing != null) {
            if (!MessageDigest.isEqual(
                    existing.requestHash().getBytes(StandardCharsets.US_ASCII),
                    requestHash.getBytes(StandardCharsets.US_ASCII))) {
                throw new EventIngestionException(
                        HttpStatus.CONFLICT, "batch_id_conflict", "Batch ID was already used for different content");
            }
            return storedResponse(request.batchId(), existing.eventResults());
        }
        validateTimestamps(request);

        UUID batchId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO tracking_ingestion_batches
                            (id, workspace_id, project_id, external_batch_id, envelope_version, request_hash)
                        VALUES (:id, :workspaceId, :projectId, :batchId, :version, :requestHash)
                        """)
                .param("id", batchId)
                .param("workspaceId", project.workspaceId())
                .param("projectId", project.projectId())
                .param("batchId", request.batchId())
                .param("version", request.version())
                .param("requestHash", requestHash)
                .update();

        List<EventIngestionResponse.EventResult> results = new ArrayList<>();
        for (EventIngestionRequest.Event event : request.events()) {
            if (eventExists(project.projectId(), event.eventId())) {
                results.add(new EventIngestionResponse.EventResult(
                        event.eventId(), EventIngestionResponse.Status.DUPLICATE));
                continue;
            }
            UUID visitorId = upsertVisitor(project, event.visitorId(), event.occurredAt());
            if (event.type().equals("identify")) {
                if (!identities.identify(project, visitorId, externalUserId(event.payload()), event.occurredAt())) {
                    throw new EventIngestionException(HttpStatus.CONFLICT, "visitor_identity_conflict",
                            "Visitor is already linked to another external identity");
                }
            }
            UUID sessionId = event.sessionId() == null
                    ? null
                    : upsertSession(project, visitorId, event.sessionId(), event.occurredAt());
            insertEvent(project, batchId, visitorId, sessionId, event);
            results.add(new EventIngestionResponse.EventResult(
                    event.eventId(), EventIngestionResponse.Status.ACCEPTED));
        }
        EventIngestionResponse response = new EventIngestionResponse(request.batchId(), List.copyOf(results));
        jdbc.sql("""
                        UPDATE tracking_ingestion_batches
                        SET event_results = CAST(:results AS JSONB)
                        WHERE id = :id AND workspace_id = :workspaceId AND project_id = :projectId
                        """)
                .param("results", json(response.events()))
                .param("id", batchId)
                .param("workspaceId", project.workspaceId())
                .param("projectId", project.projectId())
                .update();
        return response;
    }

    private String externalUserId(Map<String, Object> payload) {
        if (!(payload.get("externalUserId") instanceof String value)
                || value.isBlank() || value.length() > 160 || !value.equals(value.trim())) {
            throw new EventIngestionException(HttpStatus.BAD_REQUEST, "invalid_identify_payload",
                    "Identify events require one non-blank externalUserId of at most 160 characters");
        }
        return value;
    }

    private void lockProject(UUID projectId) {
        long lockKey = projectId.getMostSignificantBits() ^ projectId.getLeastSignificantBits();
        jdbc.sql("SELECT pg_advisory_xact_lock(:lockKey)")
                .param("lockKey", lockKey)
                .query((rs, row) -> 0)
                .single();
    }

    private ExistingBatch findBatch(UUID projectId, String externalBatchId) {
        return jdbc.sql("""
                        SELECT request_hash, event_results::TEXT AS event_results
                        FROM tracking_ingestion_batches
                        WHERE project_id = :projectId AND external_batch_id = :batchId
                        """)
                .param("projectId", projectId)
                .param("batchId", externalBatchId)
                .query((rs, row) -> new ExistingBatch(
                        rs.getString("request_hash"), rs.getString("event_results")))
                .optional()
                .orElse(null);
    }

    private void validateTimestamps(EventIngestionRequest request) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime oldest = now.minus(Duration.ofDays(30));
        OffsetDateTime newest = now.plus(Duration.ofMinutes(5));
        for (EventIngestionRequest.Event event : request.events()) {
            if (event.occurredAt().isBefore(oldest) || event.occurredAt().isAfter(newest)) {
                throw new EventIngestionException(HttpStatus.BAD_REQUEST, "timestamp_out_of_range",
                        "Event timestamps must be at most 30 days old and no more than 5 minutes in the future");
            }
        }
    }

    private boolean eventExists(UUID projectId, String externalEventId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM tracking_event_envelopes
                            WHERE project_id = :projectId AND external_event_id = :eventId)
                        """)
                .param("projectId", projectId)
                .param("eventId", externalEventId)
                .query(Boolean.class)
                .single();
    }

    private void ensureDistinctEventIds(EventIngestionRequest request) {
        Set<String> ids = new HashSet<>();
        for (EventIngestionRequest.Event event : request.events()) {
            if (!ids.add(event.eventId())) {
                throw new EventIngestionException(
                        HttpStatus.BAD_REQUEST, "duplicate_event_id", "Event IDs must be unique within a batch");
            }
        }
    }

    private UUID upsertVisitor(
            IngestionKeyService.ResolvedProject project, String externalId, OffsetDateTime occurredAt) {
        return jdbc.sql("""
                        INSERT INTO visitors
                            (id, workspace_id, project_id, external_visitor_id, first_seen_at, last_seen_at)
                        VALUES (:id, :workspaceId, :projectId, :externalId, :occurredAt, :occurredAt)
                        ON CONFLICT (project_id, external_visitor_id) DO UPDATE
                        SET first_seen_at = LEAST(visitors.first_seen_at, EXCLUDED.first_seen_at),
                            last_seen_at = GREATEST(visitors.last_seen_at, EXCLUDED.last_seen_at)
                        RETURNING id
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", project.workspaceId())
                .param("projectId", project.projectId())
                .param("externalId", externalId)
                .param("occurredAt", occurredAt)
                .query(UUID.class)
                .single();
    }

    private UUID upsertSession(IngestionKeyService.ResolvedProject project, UUID visitorId,
            String externalId, OffsetDateTime occurredAt) {
        return jdbc.sql("""
                        INSERT INTO tracking_sessions
                            (id, workspace_id, project_id, visitor_id, external_session_id, started_at)
                        VALUES (:id, :workspaceId, :projectId, :visitorId, :externalId, :occurredAt)
                        ON CONFLICT (project_id, external_session_id) DO UPDATE
                        SET started_at = LEAST(tracking_sessions.started_at, EXCLUDED.started_at)
                        WHERE tracking_sessions.visitor_id = EXCLUDED.visitor_id
                        RETURNING id
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", project.workspaceId())
                .param("projectId", project.projectId())
                .param("visitorId", visitorId)
                .param("externalId", externalId)
                .param("occurredAt", occurredAt)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new EventIngestionException(
                        HttpStatus.CONFLICT, "session_visitor_conflict", "Session ID belongs to another visitor"));
    }

    private void insertEvent(IngestionKeyService.ResolvedProject project, UUID batchId, UUID visitorId,
            UUID sessionId, EventIngestionRequest.Event event) {
        jdbc.sql("""
                        INSERT INTO tracking_event_envelopes
                            (id, workspace_id, project_id, visitor_id, session_id, ingestion_batch_id,
                             external_event_id, event_type, occurred_at, payload)
                        VALUES (:id, :workspaceId, :projectId, :visitorId, :sessionId, :batchId,
                                :eventId, :eventType, :occurredAt, CAST(:payload AS JSONB))
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", project.workspaceId())
                .param("projectId", project.projectId())
                .param("visitorId", visitorId)
                .param("sessionId", sessionId)
                .param("batchId", batchId)
                .param("eventId", event.eventId())
                .param("eventType", event.type())
                .param("occurredAt", event.occurredAt())
                .param("payload", json(event.payload()))
                .update();
    }

    private EventIngestionResponse storedResponse(String batchId, String resultsJson) {
        try {
            EventIngestionResponse.EventResult[] results =
                    canonicalMapper.readValue(resultsJson, EventIngestionResponse.EventResult[].class);
            return new EventIngestionResponse(batchId, List.of(results));
        } catch (JacksonException invalidStoredResponse) {
            throw new IllegalStateException("Stored ingestion response is invalid", invalidStoredResponse);
        }
    }

    private String requestHash(EventIngestionRequest request) {
        return sha256(jsonWith(canonicalMapper, request));
    }

    private String json(Object value) {
        return jsonWith(canonicalMapper, value);
    }

    private static String jsonWith(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException impossible) {
            throw new IllegalArgumentException("Request contains an unsupported JSON value", impossible);
        }
    }

    private static String sha256(String value) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record ExistingBatch(String requestHash, String eventResults) {}
}
