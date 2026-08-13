package com.mrrorigin.tracking;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.mrrorigin.workspace.WorkspaceContext;

/** Authenticated, project-scoped tracking-data retention configuration and batch execution (#8). */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/projects/{projectId}/tracking/retention")
public class TrackingRetentionController {

    private static final int DEFAULT_MAX_ROWS = 1000;
    private static final int MAX_MAX_ROWS = 10_000;

    private final WorkspaceContext workspaceContext;
    private final TrackingProjectAccess projectAccess;
    private final TrackingRetentionSettingsService settings;
    private final TrackingRetentionService retention;

    public TrackingRetentionController(
            WorkspaceContext workspaceContext,
            TrackingProjectAccess projectAccess,
            TrackingRetentionSettingsService settings,
            TrackingRetentionService retention) {
        this.workspaceContext = workspaceContext;
        this.projectAccess = projectAccess;
        this.settings = settings;
        this.retention = retention;
    }

    @GetMapping
    public RetentionSettingsResponse getSettings(@PathVariable UUID workspaceId, @PathVariable UUID projectId) {
        workspaceContext.requireMembership(workspaceId);
        projectAccess.requireProjectInWorkspace(workspaceId, projectId);
        return new RetentionSettingsResponse(settings.retentionDays(workspaceId, projectId));
    }

    @PutMapping
    public RetentionSettingsResponse updateSettings(
            @PathVariable UUID workspaceId, @PathVariable UUID projectId, @RequestBody UpdateRetentionRequest request) {
        workspaceContext.requireManager(workspaceId);
        projectAccess.requireProjectInWorkspace(workspaceId, projectId);
        try {
            return new RetentionSettingsResponse(
                    settings.updateRetentionDays(workspaceId, projectId, request.retentionDays()));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
    }

    @PostMapping("/run")
    public RetentionRunResponse run(
            @PathVariable UUID workspaceId, @PathVariable UUID projectId, @RequestParam(required = false) Integer maxRows) {
        workspaceContext.requireManager(workspaceId);
        projectAccess.requireProjectInWorkspace(workspaceId, projectId);
        int bounded = boundedOrDefault(maxRows);
        TrackingRetentionService.RetentionRunOutcome outcome = retention.runBatch(workspaceId, projectId, bounded);
        return new RetentionRunResponse(
                outcome.retentionDays(), outcome.cutoff(), outcome.envelopesDeleted(),
                outcome.batchesDeleted(), outcome.failuresDeleted(), outcome.complete());
    }

    private static int boundedOrDefault(Integer requested) {
        if (requested == null) {
            return DEFAULT_MAX_ROWS;
        }
        if (requested < 1 || requested > MAX_MAX_ROWS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "maxRows must be between 1 and " + MAX_MAX_ROWS);
        }
        return requested;
    }

    @ExceptionHandler(TrackingManagementException.class)
    ResponseEntity<Map<String, String>> managementError(TrackingManagementException error) {
        return ResponseEntity.status(error.status()).body(Map.of("code", error.code(), "message", error.getMessage()));
    }

    public record UpdateRetentionRequest(int retentionDays) {}

    public record RetentionSettingsResponse(int retentionDays) {}

    public record RetentionRunResponse(
            int retentionDays,
            OffsetDateTime cutoff,
            int envelopesDeleted,
            int batchesDeleted,
            int failuresDeleted,
            boolean complete) {}
}
