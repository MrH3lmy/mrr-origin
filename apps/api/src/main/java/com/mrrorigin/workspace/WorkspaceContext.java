package com.mrrorigin.workspace;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Component
@RequestScope
public class WorkspaceContext {

    private final WorkspaceMemberRepository memberRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberEmailCaptureService emailCaptureService;
    private final Map<UUID, WorkspaceMember> memberships = new HashMap<>();
    private String subjectId;

    public WorkspaceContext(
            WorkspaceMemberRepository memberRepository,
            WorkspaceRepository workspaceRepository,
            WorkspaceMemberEmailCaptureService emailCaptureService) {
        this.memberRepository = memberRepository;
        this.workspaceRepository = workspaceRepository;
        this.emailCaptureService = emailCaptureService;
    }

    public String subjectId() {
        if (subjectId != null) {
            return subjectId;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)
                || !authentication.isAuthenticated()
                || jwtAuthentication.getToken().getSubject() == null
                || jwtAuthentication.getToken().getSubject().isBlank()) {
            throw new AuthenticationCredentialsNotFoundException("A validated JWT subject is required");
        }

        subjectId = jwtAuthentication.getToken().getSubject();
        return subjectId;
    }

    public WorkspaceMember requireMembership(UUID workspaceId) {
        return memberships.computeIfAbsent(workspaceId, id -> {
            WorkspaceMember member = memberRepository
                    .findByWorkspaceIdAndSubjectId(id, subjectId())
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Workspace not found"));
            captureEmailIfPresent(id, member);
            return member;
        });
    }

    /**
     * Per #59 (accepted B3): captures/refreshes this caller's own email from their JWT on every
     * authenticated request, but only from a claim the identity provider itself has verified --
     * {@code email_verified=true}. An unverified or absent claim never seeds or overwrites the stored
     * value, and a member whose verified address changes is picked up automatically (not just once).
     */
    private void captureEmailIfPresent(UUID workspaceId, WorkspaceMember member) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            return;
        }
        Boolean verified = jwtAuthentication.getToken().getClaimAsBoolean("email_verified");
        if (!Boolean.TRUE.equals(verified)) {
            return;
        }
        String email = jwtAuthentication.getToken().getClaimAsString("email");
        if (email == null || email.isBlank() || email.equals(member.email())) {
            return;
        }
        emailCaptureService.captureOrRefresh(workspaceId, member.subjectId(), email);
    }

    public WorkspaceMember requireManager(UUID workspaceId) {
        WorkspaceMember membership = requireMembership(workspaceId);
        requireNotDeleting(workspaceId);
        if (!membership.role().canManage()) {
            throw new ResponseStatusException(FORBIDDEN, "Workspace management permission required");
        }
        return membership;
    }

    /** Stricter than {@link #requireManager}: only the workspace owner, e.g. for #62's workspace deletion. */
    public WorkspaceMember requireOwner(UUID workspaceId) {
        WorkspaceMember membership = requireMembership(workspaceId);
        requireNotDeleting(workspaceId);
        if (membership.role() != WorkspaceRole.OWNER) {
            throw new ResponseStatusException(FORBIDDEN, "Workspace owner permission required");
        }
        return membership;
    }

    /**
     * Rejects mutations once a workspace has entered #62's DELETING state -- deliberately not applied
     * inside {@link #requireMembership} itself, so reads (including "is my workspace being deleted")
     * keep working throughout. The deletion flow's own endpoints authorize the caller directly against
     * {@link #requireMembership} instead of {@link #requireManager}/{@link #requireOwner}, so they are
     * never blocked by their own state transition.
     */
    private void requireNotDeleting(UUID workspaceId) {
        Workspace workspace = workspaceRepository.findById(workspaceId).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Workspace not found"));
        if (workspace.status() == WorkspaceStatus.DELETING) {
            throw new ResponseStatusException(CONFLICT, "Workspace is being deleted");
        }
    }

    /**
     * Non-throwing capability check for surfaces that must show an explicit "you cannot do this"
     * state rather than a 403 (e.g. {@code #24}'s repair-capability flag): true when the caller is a
     * member with {@link WorkspaceRole#canManage()} authority. Still requires membership -- a
     * non-member gets the usual 404 from {@link #requireMembership}, never leaking whether a
     * workspace exists.
     */
    public boolean canManage(UUID workspaceId) {
        return requireMembership(workspaceId).role().canManage();
    }
}
