package com.mrrorigin.workspace;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceManagementController {

    private final WorkspaceManagementService service;

    public WorkspaceManagementController(WorkspaceManagementService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<WorkspaceResponse> createWorkspace(@Valid @RequestBody CreateWorkspaceRequest request) {
        WorkspaceManagementService.WorkspaceAccess created =
                service.createWorkspace(request.name(), request.slug(), request.reportingCurrency());
        WorkspaceResponse response = WorkspaceResponse.from(created);
        return ResponseEntity.created(URI.create("/api/workspaces/" + response.id())).body(response);
    }

    @GetMapping
    public List<WorkspaceResponse> listWorkspaces() {
        return service.listWorkspaces().stream().map(WorkspaceResponse::from).toList();
    }

    @GetMapping("/{workspaceId}")
    public WorkspaceResponse getWorkspace(@PathVariable UUID workspaceId) {
        return WorkspaceResponse.from(service.getWorkspace(workspaceId));
    }

    @PostMapping("/{workspaceId}/members")
    public ResponseEntity<MemberResponse> addMember(
            @PathVariable UUID workspaceId, @Valid @RequestBody CreateMemberRequest request) {
        WorkspaceMember member = service.addMember(workspaceId, request.subjectId(), request.role());
        URI location = UriComponentsBuilder.fromPath("/api/workspaces/{workspaceId}/members/{subjectId}")
                .buildAndExpand(workspaceId, member.subjectId())
                .encode()
                .toUri();
        return ResponseEntity.created(location)
                .body(MemberResponse.from(member));
    }

    @GetMapping("/{workspaceId}/members")
    public List<MemberResponse> listMembers(@PathVariable UUID workspaceId) {
        return service.listMembers(workspaceId).stream().map(MemberResponse::from).toList();
    }

    @GetMapping("/{workspaceId}/members/{subjectId}")
    public MemberResponse getMember(@PathVariable UUID workspaceId, @PathVariable String subjectId) {
        return MemberResponse.from(service.getMember(workspaceId, subjectId));
    }

    @PostMapping("/{workspaceId}/projects")
    public ResponseEntity<ProjectResponse> createProject(
            @PathVariable UUID workspaceId, @Valid @RequestBody CreateProjectRequest request) {
        Project project = service.createProject(workspaceId, request.name(), request.domain(), request.timezone());
        ProjectResponse response = ProjectResponse.from(project);
        return ResponseEntity.created(URI.create(
                        "/api/workspaces/" + workspaceId + "/projects/" + response.id()))
                .body(response);
    }

    @GetMapping("/{workspaceId}/projects")
    public List<ProjectResponse> listProjects(@PathVariable UUID workspaceId) {
        return service.listProjects(workspaceId).stream().map(ProjectResponse::from).toList();
    }

    @GetMapping("/{workspaceId}/projects/{projectId}")
    public ProjectResponse getProject(@PathVariable UUID workspaceId, @PathVariable UUID projectId) {
        return ProjectResponse.from(service.getProject(workspaceId, projectId));
    }

    public record CreateWorkspaceRequest(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 80) @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$") String slug,
            @Pattern(regexp = "^[A-Z]{3}$") String reportingCurrency) {}

    public record WorkspaceResponse(
            UUID id,
            String name,
            String slug,
            String reportingCurrency,
            WorkspaceRole role,
            OffsetDateTime createdAt) {

        static WorkspaceResponse from(WorkspaceManagementService.WorkspaceAccess access) {
            Workspace workspace = access.workspace();
            return new WorkspaceResponse(
                    workspace.id(),
                    workspace.name(),
                    workspace.slug(),
                    workspace.reportingCurrency(),
                    access.role(),
                    workspace.createdAt());
        }
    }

    public record CreateMemberRequest(
            @NotBlank @Size(max = 255) String subjectId, @NotNull WorkspaceRole role) {}

    public record MemberResponse(
            UUID workspaceId, String subjectId, WorkspaceRole role, OffsetDateTime createdAt) {

        static MemberResponse from(WorkspaceMember member) {
            return new MemberResponse(
                    member.workspaceId(), member.subjectId(), member.role(), member.createdAt());
        }
    }

    public record CreateProjectRequest(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 253) String domain,
            @Size(max = 64) String timezone) {}

    public record ProjectResponse(
            UUID id,
            UUID workspaceId,
            String name,
            String domain,
            String publicKey,
            String timezone,
            OffsetDateTime createdAt) {

        static ProjectResponse from(Project project) {
            return new ProjectResponse(
                    project.id(),
                    project.workspaceId(),
                    project.name(),
                    project.domain(),
                    project.publicKey(),
                    project.timezone(),
                    project.createdAt());
        }
    }
}
