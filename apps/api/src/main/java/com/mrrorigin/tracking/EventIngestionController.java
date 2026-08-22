package com.mrrorigin.tracking;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/v1/events")
public class EventIngestionController {
    /**
     * Request-level rejection counter (P6 observability slice, #28). {@code reason} groups the
     * various {@link EventIngestionException} codes into the same small bounded enum
     * {@link TrackingIngestionFailureRecorder} already uses for its diagnostics rows
     * (invalid_key/blocked_origin/invalid_payload) plus {@code conflict} for the batch/visitor/session
     * conflict codes -- never a raw error message, ingestion key, or origin value. Pre-registered at
     * startup for this exact 4-value vocabulary (review fix, #90): {@link #rejectionReason} maps
     * every {@link EventIngestionException} code this controller/service can throw
     * (invalid_ingestion_key, invalid_origin, origin_not_allowed, unsupported_version,
     * invalid_identify_payload, timestamp_out_of_range, batch_id_conflict, visitor_identity_conflict,
     * session_visitor_conflict) onto one of these 4 values -- the {@code default} branch is
     * unreachable defensive coverage for a code added here without updating this mapping, not a
     * fifth production value, so it is intentionally not pre-registered.
     */
    private static final String REJECTED_METRIC = "mrrorigin.ingestion.rejected";

    private static final List<String> REJECTION_REASONS =
            List.of("invalid_key", "blocked_origin", "invalid_payload", "conflict");

    private final IngestionKeyService keys;
    private final EventIngestionService ingestion;
    private final AllowedDomainService allowedDomains;
    private final TrackingIngestionFailureRecorder failures;
    private final Map<String, Counter> rejectedCounters = new ConcurrentHashMap<>();

    public EventIngestionController(
            IngestionKeyService keys,
            EventIngestionService ingestion,
            AllowedDomainService allowedDomains,
            TrackingIngestionFailureRecorder failures,
            MeterRegistry meterRegistry) {
        this.keys = keys;
        this.ingestion = ingestion;
        this.allowedDomains = allowedDomains;
        this.failures = failures;
        // Pre-registered at startup (rather than created lazily on first increment) -- see
        // EventIngestionService's identical reasoning.
        for (String reason : REJECTION_REASONS) {
            rejectedCounters.put(reason, Counter.builder(REJECTED_METRIC).tag("reason", reason).register(meterRegistry));
        }
    }

    private void recordRejection(String reason) {
        rejectedCounters.get(reason).increment();
    }

    private static String rejectionReason(String code) {
        return switch (code) {
            case "invalid_ingestion_key" -> "invalid_key";
            case "invalid_origin", "origin_not_allowed" -> "blocked_origin";
            case "unsupported_version", "invalid_identify_payload", "timestamp_out_of_range" -> "invalid_payload";
            case "batch_id_conflict", "visitor_identity_conflict", "session_visitor_conflict" -> "conflict";
            default -> throw new IllegalStateException("Unmapped EventIngestionException code: " + code);
        };
    }

    @PostMapping
    ResponseEntity<EventIngestionResponse> ingest(
            @RequestHeader("X-Ingestion-Key") String rawKey,
            @RequestHeader("Origin") String origin,
            @Valid @RequestBody EventIngestionRequest request) {
        IngestionKeyService.ResolvedProject project = keys.resolve(rawKey).orElseGet(() -> {
            keys.resolveProjectByPrefixForDiagnostics(rawKey)
                    .ifPresent(diagnosable -> failures.recordInvalidKey(diagnosable.workspaceId(), diagnosable.projectId()));
            throw new EventIngestionException(
                    HttpStatus.UNAUTHORIZED, "invalid_ingestion_key", "Ingestion key is invalid or revoked");
        });
        boolean allowed;
        String normalizedOrigin = null;
        try {
            normalizedOrigin = AllowedDomainService.normalizeOrigin(origin);
            allowed = allowedDomains.isAllowed(project.workspaceId(), project.projectId(), origin);
        } catch (IllegalArgumentException invalid) {
            failures.recordBlockedOrigin(project.workspaceId(), project.projectId(), null);
            throw new EventIngestionException(HttpStatus.FORBIDDEN, "invalid_origin", "Origin is invalid");
        }
        if (!allowed) {
            failures.recordBlockedOrigin(project.workspaceId(), project.projectId(), normalizedOrigin);
            throw new EventIngestionException(HttpStatus.FORBIDDEN, "origin_not_allowed", "Origin is not allowed");
        }
        return ResponseEntity.status(HttpStatus.OK).body(ingestion.ingest(project, request));
    }

    @ExceptionHandler(EventIngestionException.class)
    ResponseEntity<Map<String, String>> ingestionError(EventIngestionException error) {
        recordRejection(rejectionReason(error.code()));
        return ResponseEntity.status(error.status()).body(Map.of("code", error.code(), "message", error.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> validationError(HttpServletRequest request) {
        recordRejection("invalid_payload");
        recordInvalidPayloadBestEffort(request);
        return ResponseEntity.badRequest().body(Map.of("code", "invalid_envelope", "message", "Envelope validation failed"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> unreadableBody(HttpMessageNotReadableException error, HttpServletRequest request) {
        if (IngestionBodyLimitFilter.causedByBodyLimit(error)) {
            recordRejection("invalid_payload");
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of("code", "request_too_large", "message", "Request body exceeds 1048576 bytes"));
        }
        recordRejection("invalid_payload");
        recordInvalidPayloadBestEffort(request);
        return ResponseEntity.badRequest().body(Map.of("code", "invalid_envelope", "message", "Envelope is not valid JSON"));
    }

    /**
     * Body validation/parsing happens while Spring resolves the {@code @RequestBody} argument, before
     * this controller's own method body (and its key resolution) ever runs -- so attribution here is
     * necessarily best-effort: only a fully valid, active ingestion key can be resolved to a project at
     * all. A malformed request sent with an unresolvable key contributes no diagnostic, matching
     * INVALID_PAYLOAD's "where applicable" scope.
     */
    private void recordInvalidPayloadBestEffort(HttpServletRequest request) {
        keys.resolve(request.getHeader("X-Ingestion-Key"))
                .ifPresent(project -> failures.recordInvalidPayload(project.workspaceId(), project.projectId()));
    }
}
