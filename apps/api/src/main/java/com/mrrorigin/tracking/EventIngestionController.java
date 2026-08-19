package com.mrrorigin.tracking;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/v1/events")
public class EventIngestionController {
    private final IngestionKeyService keys;
    private final EventIngestionService ingestion;
    private final AllowedDomainService allowedDomains;
    private final TrackingIngestionFailureRecorder failures;
    private final IngestionRateLimiter rateLimiter;

    public EventIngestionController(
            IngestionKeyService keys,
            EventIngestionService ingestion,
            AllowedDomainService allowedDomains,
            TrackingIngestionFailureRecorder failures,
            IngestionRateLimiter rateLimiter) {
        this.keys = keys;
        this.ingestion = ingestion;
        this.allowedDomains = allowedDomains;
        this.failures = failures;
        this.rateLimiter = rateLimiter;
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
        // Rate limiting runs last, after key resolution and the origin check both succeed. Checking it
        // any earlier would let a stolen-but-valid key probing arbitrary Origins either learn it is
        // being rate limited without ever guessing an allowed origin (401/403 already tell an attacker
        // the key is valid; a 429 reachable from any origin would additionally leak how busy the
        // legitimate integration is), or spend the legitimate integration's own budget on traffic that
        // was always going to be rejected for its origin anyway.
        IngestionRateLimiter.Decision rateLimit =
                rateLimiter.check(project.keyId(), project.workspaceId(), project.projectId());
        if (!rateLimit.allowed()) {
            throw new EventIngestionException(HttpStatus.TOO_MANY_REQUESTS, "rate_limit_exceeded",
                    "Too many requests for this ingestion key", rateLimit.retryAfterSeconds());
        }
        return ResponseEntity.status(HttpStatus.OK).body(ingestion.ingest(project, request));
    }

    @ExceptionHandler(EventIngestionException.class)
    ResponseEntity<Map<String, String>> ingestionError(EventIngestionException error) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(error.status());
        if (error.retryAfterSeconds() != null) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(error.retryAfterSeconds()));
        }
        return response.body(Map.of("code", error.code(), "message", error.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> validationError(HttpServletRequest request) {
        recordInvalidPayloadBestEffort(request);
        return ResponseEntity.badRequest().body(Map.of("code", "invalid_envelope", "message", "Envelope validation failed"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> unreadableBody(HttpMessageNotReadableException error, HttpServletRequest request) {
        if (IngestionBodyLimitFilter.causedByBodyLimit(error)) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of("code", "request_too_large", "message", "Request body exceeds 1048576 bytes"));
        }
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
