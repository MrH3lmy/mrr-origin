package com.mrrorigin.tracking;

import java.util.Map;

import jakarta.validation.Valid;
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

    public EventIngestionController(
            IngestionKeyService keys, EventIngestionService ingestion, AllowedDomainService allowedDomains) {
        this.keys = keys;
        this.ingestion = ingestion;
        this.allowedDomains = allowedDomains;
    }

    @PostMapping
    ResponseEntity<EventIngestionResponse> ingest(
            @RequestHeader("X-Ingestion-Key") String rawKey,
            @RequestHeader("Origin") String origin,
            @Valid @RequestBody EventIngestionRequest request) {
        IngestionKeyService.ResolvedProject project = keys.resolve(rawKey)
                .orElseThrow(() -> new EventIngestionException(
                        HttpStatus.UNAUTHORIZED, "invalid_ingestion_key", "Ingestion key is invalid or revoked"));
        boolean allowed;
        try {
            allowed = allowedDomains.isAllowed(project.workspaceId(), project.projectId(), origin);
        } catch (IllegalArgumentException invalid) {
            throw new EventIngestionException(HttpStatus.FORBIDDEN, "invalid_origin", "Origin is invalid");
        }
        if (!allowed) {
            throw new EventIngestionException(HttpStatus.FORBIDDEN, "origin_not_allowed", "Origin is not allowed");
        }
        return ResponseEntity.status(HttpStatus.OK).body(ingestion.ingest(project, request));
    }

    @ExceptionHandler(EventIngestionException.class)
    ResponseEntity<Map<String, String>> ingestionError(EventIngestionException error) {
        return ResponseEntity.status(error.status()).body(Map.of("code", error.code(), "message", error.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> validationError() {
        return ResponseEntity.badRequest().body(Map.of("code", "invalid_envelope", "message", "Envelope validation failed"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> unreadableBody(HttpMessageNotReadableException error) {
        if (IngestionBodyLimitFilter.causedByBodyLimit(error)) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of("code", "request_too_large", "message", "Request body exceeds 262144 bytes"));
        }
        return ResponseEntity.badRequest().body(Map.of("code", "invalid_envelope", "message", "Envelope is not valid JSON"));
    }
}
