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

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Component
@RequestScope
public class WorkspaceContext {

    private final WorkspaceMemberRepository memberRepository;
    private final WorkspaceMemberEmailCaptureService emailCaptureService;
    private final Map<UUID, WorkspaceMember> memberships = new HashMap<>();
    private String subjectId;

    public WorkspaceContext(WorkspaceMemberRepository memberRepository, WorkspaceMemberEmailCaptureService emailCaptureService) {
        this.memberRepository = memberRepository;
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

    /** Per #59: best-effort, lazy capture of this caller's own email from their JWT, at most once per member. */
    private void captureEmailIfPresent(UUID workspaceId, WorkspaceMember member) {
        if (member.email() != null) {
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            return;
        }
        String email = jwtAuthentication.getToken().getClaimAsString("email");
        if (email == null || email.isBlank()) {
            return;
        }
        emailCaptureService.captureIfAbsent(workspaceId, member.subjectId(), email);
    }

    public WorkspaceMember requireManager(UUID workspaceId) {
        WorkspaceMember membership = requireMembership(workspaceId);
        if (!membership.role().canManage()) {
            throw new ResponseStatusException(FORBIDDEN, "Workspace management permission required");
        }
        return membership;
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
