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
    private final WorkspaceMemberEmailCaptureService emailCaptureService;
    private final WorkspaceRepository workspaceRepository;
    private final Map<UUID, WorkspaceMember> memberships = new HashMap<>();
    private String subjectId;

    public WorkspaceContext(
            WorkspaceMemberRepository memberRepository,
            WorkspaceMemberEmailCaptureService emailCaptureService,
            WorkspaceRepository workspaceRepository) {
        this.memberRepository = memberRepository;
        this.emailCaptureService = emailCaptureService;
        this.workspaceRepository = workspaceRepository;
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

    /**
     * Authorizes a mutating call: membership, manager-or-owner role, and -- unlike {@link
     * #requireOwner}, which the workspace-deletion flow itself uses so that flow can keep running
     * while the workspace is {@code DELETING} -- that the workspace is not currently being deleted.
     * Per #62's accepted contract ("all mutating endpoints should 409/423 once a workspace is in this
     * state -- needs a WorkspaceContext-level check, not one check per controller"), every other
     * module's write endpoints route authorization through this one method, so gating writes here
     * covers them uniformly.
     */
    public WorkspaceMember requireManager(UUID workspaceId) {
        WorkspaceMember membership = requireMembership(workspaceId);
        if (!membership.role().canManage()) {
            throw new ResponseStatusException(FORBIDDEN, "Workspace management permission required");
        }
        requireNotDeleting(workspaceId);
        return membership;
    }

    /**
     * Authorizes the workspace-owner-only actions in #62's deletion flow. Stricter than {@link
     * #requireManager} on role (owner only, not owner-or-admin) but deliberately does not gate on
     * deletion status -- the deletion flow's own controller is the one caller allowed to keep writing
     * (advancing the deletion run's phases) while the workspace is {@code DELETING}.
     */
    public WorkspaceMember requireOwner(UUID workspaceId) {
        WorkspaceMember membership = requireMembership(workspaceId);
        if (membership.role() != WorkspaceRole.OWNER) {
            throw new ResponseStatusException(FORBIDDEN, "Workspace owner permission required");
        }
        return membership;
    }

    private void requireNotDeleting(UUID workspaceId) {
        if (!workspaceRepository.existsByIdAndStatus(workspaceId, WorkspaceStatus.ACTIVE)) {
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
