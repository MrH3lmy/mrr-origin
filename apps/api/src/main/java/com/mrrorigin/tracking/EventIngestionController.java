package com.mrrorigin.tracking;

import java.net.IDN;
import java.net.URI;
import java.util.Locale;
import java.util.Map;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
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
    private final IngestionKeyService keys;
    private final EventIngestionService ingestion;
    private final JdbcClient jdbc;

    public EventIngestionController(IngestionKeyService keys, EventIngestionService ingestion, JdbcClient jdbc) {
        this.keys = keys;
        this.ingestion = ingestion;
        this.jdbc = jdbc;
    }

    @PostMapping
    ResponseEntity<EventIngestionResponse> ingest(
            @RequestHeader("X-Ingestion-Key") String rawKey,
            @RequestHeader("Origin") String origin,
            @Valid @RequestBody EventIngestionRequest request) {
        IngestionKeyService.ResolvedProject project = keys.resolve(rawKey)
                .orElseThrow(() -> new EventIngestionException(
                        HttpStatus.UNAUTHORIZED, "invalid_ingestion_key", "Ingestion key is invalid or revoked"));
        String host = originHost(origin);
        boolean allowed = jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM project_allowed_domains
                            WHERE workspace_id = :workspaceId AND project_id = :projectId AND domain = :domain)
                        """)
                .param("workspaceId", project.workspaceId())
                .param("projectId", project.projectId())
                .param("domain", host)
                .query(Boolean.class)
                .single();
        if (!allowed) {
            throw new EventIngestionException(HttpStatus.FORBIDDEN, "origin_not_allowed", "Origin is not allowed");
        }
        return ResponseEntity.status(HttpStatus.OK).body(ingestion.ingest(project, request));
    }

    private static String originHost(String origin) {
        try {
            URI uri = URI.create(origin);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getPath() != null && !uri.getPath().isEmpty()
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            return IDN.toASCII(uri.getHost().toLowerCase(Locale.ROOT), IDN.USE_STD3_ASCII_RULES);
        } catch (IllegalArgumentException invalid) {
            throw new EventIngestionException(HttpStatus.FORBIDDEN, "invalid_origin", "Origin is invalid");
        }
    }

    @ExceptionHandler(EventIngestionException.class)
    ResponseEntity<Map<String, String>> ingestionError(EventIngestionException error) {
        return ResponseEntity.status(error.status()).body(Map.of("code", error.code(), "message", error.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> validationError() {
        return ResponseEntity.badRequest().body(Map.of("code", "invalid_envelope", "message", "Envelope validation failed"));
    }
}
