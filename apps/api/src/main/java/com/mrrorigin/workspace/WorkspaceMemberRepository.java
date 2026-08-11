package com.mrrorigin.workspace;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, WorkspaceMemberId> {

    Optional<WorkspaceMember> findByWorkspaceIdAndSubjectId(UUID workspaceId, String subjectId);

    List<WorkspaceMember> findAllByWorkspaceIdOrderByCreatedAtAsc(UUID workspaceId);

    List<WorkspaceMember> findAllBySubjectIdOrderByCreatedAtAsc(String subjectId);

    boolean existsByWorkspaceIdAndSubjectId(UUID workspaceId, String subjectId);
}
