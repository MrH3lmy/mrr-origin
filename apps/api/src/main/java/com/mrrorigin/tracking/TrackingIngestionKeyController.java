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
 * Authenticated, project-scoped ingestion-key management for the #21 onboarding UI's tracker
 * installation step. Wraps {@link IngestionKeyService}'s already-tested issue/rotate/lookup
 * operations with no new schema or service logic -- {@link IngestionKeyService} previously had no
 * REST surface a founder's browser could call, so there was no way to obtain a working installation
 * key without direct database access.
 *
 * <p>The raw secret is returned only from {@link #issueOrRotate}, exactly once, at the moment it is
 * created or rotated; {@link #getActive} never echoes it back.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/projects/{projectId}/tracking/ingestion-key")
public class TrackingIngestionKeyController {

    private final WorkspaceContext workspaceContext;
    private final TrackingProjectAccess projectAccess;
    private final IngestionKeyService keys;

    public TrackingIngestionKeyController(
            WorkspaceContext workspaceContext, TrackingProjectAccess projectAccess, IngestionKeyService keys) {
        this.workspaceContext = workspaceContext;
        this.projectAccess = projectAccess;
        this.keys = keys;
    }

    @GetMapping
    public ActiveKeyResponse getActive(@PathVariable UUID workspaceId, @PathVariable UUID projectId) {
        workspaceContext.requireMembership(workspaceId);
        projectAccess.requireProjectInWorkspace(workspaceId, projectId);
        return keys.getActive(workspaceId, projectId)
                .map(ActiveKeyResponse::from)
                .orElse(ActiveKeyResponse.none());
    }

    /** Issues a first key, or rotates the existing one, immediately revoking the prior secret. */
    @PostMapping
    public IssuedKeyResponse issueOrRotate(@PathVariable UUID workspaceId, @PathVariable UUID projectId) {
        workspaceContext.requireManager(workspaceId);
        projectAccess.requireProjectInWorkspace(workspaceId, projectId);
        boolean hadActiveKey = keys.getActive(workspaceId, projectId).isPresent();
        IngestionKeyService.IssuedKey issued =
                hadActiveKey ? keys.rotate(workspaceId, projectId) : keys.issue(workspaceId, projectId);
        return IssuedKeyResponse.from(issued, hadActiveKey);
    }

    @ExceptionHandler(TrackingManagementException.class)
    ResponseEntity<Map<String, String>> managementError(TrackingManagementException error) {
        return ResponseEntity.status(error.status()).body(Map.of("code", error.code(), "message", error.getMessage()));
    }

    public record ActiveKeyResponse(boolean present, UUID id, String prefix, OffsetDateTime createdAt) {
        static ActiveKeyResponse from(IngestionKeyService.ActiveKeySummary summary) {
            return new ActiveKeyResponse(true, summary.id(), summary.prefix(), summary.createdAt());
        }

        static ActiveKeyResponse none() {
            return new ActiveKeyResponse(false, null, null, null);
        }
    }

    public record IssuedKeyResponse(UUID id, String secret, String prefix, boolean rotated) {
        static IssuedKeyResponse from(IngestionKeyService.IssuedKey issued, boolean rotated) {
            return new IssuedKeyResponse(issued.id(), issued.secret(), issued.prefix(), rotated);
        }
    }
}
