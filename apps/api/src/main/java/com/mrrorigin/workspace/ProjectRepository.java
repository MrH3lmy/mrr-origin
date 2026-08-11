package com.mrrorigin.workspace;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface ProjectRepository extends JpaRepository<Project, UUID> {

    Optional<Project> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    List<Project> findAllByWorkspaceIdOrderByCreatedAtAsc(UUID workspaceId);

    boolean existsByWorkspaceIdAndDomain(UUID workspaceId, String domain);
}
