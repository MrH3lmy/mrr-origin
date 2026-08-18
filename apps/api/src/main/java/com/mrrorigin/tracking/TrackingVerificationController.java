package com.mrrorigin.tracking;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mrrorigin.workspace.WorkspaceContext;

/**
 * Authenticated, project-scoped live installation verification (#8). Starting/checking an attempt
 * does not itself expose or mutate ingestion secrets or tenant tracking data, so workspace
 * membership (not manager) suffices, matching the other read/diagnostic-style tracking endpoints.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/projects/{projectId}/tracking/verification")
public class TrackingVerificationController {

    private final WorkspaceContext workspaceContext;
    private final TrackingProjectAccess projectAccess;
    private final TrackingVerificationService verification;

    public TrackingVerificationController(
            WorkspaceContext workspaceContext, TrackingProjectAccess projectAccess, TrackingVerificationService verification) {
        this.workspaceContext = workspaceContext;
        this.projectAccess = projectAccess;
        this.verification = verification;
    }

    @PostMapping
    public VerificationResponse start(@PathVariable UUID workspaceId, @PathVariable UUID projectId) {
        workspaceContext.requireMembership(workspaceId);
        workspaceContext.requireWritable(workspaceId);
        projectAccess.requireProjectInWorkspace(workspaceId, projectId);
        return VerificationResponse.from(verification.start(workspaceId, projectId));
    }

    @GetMapping
    public VerificationResponse status(@PathVariable UUID workspaceId, @PathVariable UUID projectId) {
        workspaceContext.requireMembership(workspaceId);
        projectAccess.requireProjectInWorkspace(workspaceId, projectId);
        return verification
                .status(workspaceId, projectId)
                .map(VerificationResponse::from)
                .orElseThrow(TrackingVerificationService::notFound);
    }

    @ExceptionHandler(TrackingManagementException.class)
    ResponseEntity<Map<String, String>> managementError(TrackingManagementException error) {
        return ResponseEntity.status(error.status()).body(Map.of("code", error.code(), "message", error.getMessage()));
    }

    public record VerificationResponse(
            UUID id,
            String token,
            TrackingVerificationService.VerificationStatus status,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt,
            OffsetDateTime succeededAt) {

        static VerificationResponse from(TrackingVerificationService.VerificationAttempt attempt) {
            return new VerificationResponse(
                    attempt.id(), attempt.token(), attempt.status(), attempt.createdAt(), attempt.expiresAt(), attempt.succeededAt());
        }
    }
}
