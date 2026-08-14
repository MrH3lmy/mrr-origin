package com.mrrorigin.tracking;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.mrrorigin.workspace.WorkspaceContext;

/**
 * Authenticated, project-scoped allowed-domain management for the #21 onboarding UI's tracker
 * installation step. Wraps {@link AllowedDomainService}'s already-tested add/list/remove operations
 * with no new schema or service logic -- previously only the public ingestion path read this table;
 * nothing let a founder configure it without direct database access.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/projects/{projectId}/tracking/allowed-domains")
public class AllowedDomainController {

    private final WorkspaceContext workspaceContext;
    private final TrackingProjectAccess projectAccess;
    private final AllowedDomainService domains;

    public AllowedDomainController(
            WorkspaceContext workspaceContext, TrackingProjectAccess projectAccess, AllowedDomainService domains) {
        this.workspaceContext = workspaceContext;
        this.projectAccess = projectAccess;
        this.domains = domains;
    }

    @GetMapping
    public List<AllowedDomainResponse> list(@PathVariable UUID workspaceId, @PathVariable UUID projectId) {
        workspaceContext.requireMembership(workspaceId);
        projectAccess.requireProjectInWorkspace(workspaceId, projectId);
        return domains.list(workspaceId, projectId).stream().map(AllowedDomainResponse::from).toList();
    }

    @PostMapping
    public AllowedDomainResponse add(
            @PathVariable UUID workspaceId, @PathVariable UUID projectId, @Valid @RequestBody AddDomainRequest request) {
        workspaceContext.requireManager(workspaceId);
        projectAccess.requireProjectInWorkspace(workspaceId, projectId);
        try {
            return AllowedDomainResponse.from(domains.add(workspaceId, projectId, request.domain()));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
    }

    @DeleteMapping("/{domainId}")
    public ResponseEntity<Void> remove(
            @PathVariable UUID workspaceId, @PathVariable UUID projectId, @PathVariable UUID domainId) {
        workspaceContext.requireManager(workspaceId);
        projectAccess.requireProjectInWorkspace(workspaceId, projectId);
        if (!domains.remove(workspaceId, projectId, domainId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Allowed domain not found");
        }
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(TrackingManagementException.class)
    ResponseEntity<Map<String, String>> managementError(TrackingManagementException error) {
        return ResponseEntity.status(error.status()).body(Map.of("code", error.code(), "message", error.getMessage()));
    }

    public record AddDomainRequest(@NotBlank @Size(max = 253) String domain) {}

    public record AllowedDomainResponse(UUID id, String domain) {
        static AllowedDomainResponse from(AllowedDomainService.AllowedDomain domain) {
            return new AllowedDomainResponse(domain.id(), domain.domain());
        }
    }
}
