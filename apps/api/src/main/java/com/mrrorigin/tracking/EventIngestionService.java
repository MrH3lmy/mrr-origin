package com.mrrorigin.tracking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
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

@Service
public class EventIngestionService {
    private static final HexFormat HEX = HexFormat.of();

    private final JdbcClient jdbc;
    private final ObjectMapper canonicalMapper;

    public EventIngestionService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
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
        UUID batchId = UUID.randomUUID();
        int insertedBatch = jdbc.sql("""
                        INSERT INTO tracking_ingestion_batches
                            (id, workspace_id, project_id, external_batch_id, envelope_version, request_hash)
                        VALUES (:id, :workspaceId, :projectId, :batchId, :version, :requestHash)
                        ON CONFLICT (project_id, external_batch_id) DO NOTHING
                        """)
                .param("id", batchId)
                .param("workspaceId", project.workspaceId())
                .param("projectId", project.projectId())
                .param("batchId", request.batchId())
                .param("version", request.version())
                .param("requestHash", requestHash)
                .update();

        if (insertedBatch == 0) {
            ExistingBatch existing = jdbc.sql("""
                            SELECT id, request_hash FROM tracking_ingestion_batches
                            WHERE project_id = :projectId AND external_batch_id = :batchId
                            """)
                    .param("projectId", project.projectId())
                    .param("batchId", request.batchId())
                    .query((rs, row) -> new ExistingBatch(
                            rs.getObject("id", UUID.class), rs.getString("request_hash")))
                    .single();
            if (!MessageDigest.isEqual(
                    existing.requestHash().getBytes(StandardCharsets.US_ASCII),
                    requestHash.getBytes(StandardCharsets.US_ASCII))) {
                throw new EventIngestionException(
                        HttpStatus.CONFLICT, "batch_id_conflict", "Batch ID was already used for different content");
            }
            return duplicateResponse(request);
        }

        List<EventIngestionResponse.EventResult> results = new ArrayList<>();
        for (EventIngestionRequest.Event event : request.events()) {
            UUID visitorId = upsertVisitor(project, event.visitorId(), event.occurredAt());
            UUID sessionId = event.sessionId() == null
                    ? null
                    : upsertSession(project, visitorId, event.sessionId(), event.occurredAt());
            int inserted = insertEvent(project, batchId, visitorId, sessionId, event);
            results.add(new EventIngestionResponse.EventResult(
                    event.eventId(), inserted == 1
                            ? EventIngestionResponse.Status.ACCEPTED
                            : EventIngestionResponse.Status.DUPLICATE));
        }
        return new EventIngestionResponse(request.batchId(), List.copyOf(results));
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

    private int insertEvent(IngestionKeyService.ResolvedProject project, UUID batchId, UUID visitorId,
            UUID sessionId, EventIngestionRequest.Event event) {
        return jdbc.sql("""
                        INSERT INTO tracking_event_envelopes
                            (id, workspace_id, project_id, visitor_id, session_id, ingestion_batch_id,
                             external_event_id, event_type, occurred_at, payload)
                        VALUES (:id, :workspaceId, :projectId, :visitorId, :sessionId, :batchId,
                                :eventId, :eventType, :occurredAt, CAST(:payload AS JSONB))
                        ON CONFLICT (project_id, external_event_id) DO NOTHING
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

    private EventIngestionResponse duplicateResponse(EventIngestionRequest request) {
        return new EventIngestionResponse(request.batchId(), request.events().stream()
                .map(event -> new EventIngestionResponse.EventResult(
                        event.eventId(), EventIngestionResponse.Status.DUPLICATE))
                .toList());
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

    private record ExistingBatch(UUID id, String requestHash) {}
}
