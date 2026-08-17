package com.mrrorigin.workspace;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Best-effort capture of a member's email from their own JWT {@code email} claim (#59), called from
 * {@link WorkspaceContext} on every authenticated request whose cached {@link WorkspaceMember} has no
 * email yet. Runs in its own {@code REQUIRES_NEW} transaction so it always gets a writable connection
 * even when the calling request is inside a {@code readOnly = true} transaction (most reporting GETs
 * are) -- a read-only ambient transaction would otherwise silently reject this write. Failures here
 * are swallowed: this is profile enrichment, never a reason to fail the caller's actual request.
 */
@Service
class WorkspaceMemberEmailCaptureService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceMemberEmailCaptureService.class);

    private final WorkspaceMemberRepository memberRepository;

    WorkspaceMemberEmailCaptureService(WorkspaceMemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void captureIfAbsent(UUID workspaceId, String subjectId, String email) {
        try {
            memberRepository
                    .findByWorkspaceIdAndSubjectId(workspaceId, subjectId)
                    .filter(member -> member.email() == null)
                    .ifPresent(member -> member.captureEmail(email));
        } catch (RuntimeException failure) {
            log.warn("Could not capture email for workspace member (workspaceId={}): {}", workspaceId, failure.getMessage());
        }
    }
}
