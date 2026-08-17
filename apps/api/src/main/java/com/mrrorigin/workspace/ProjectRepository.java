package com.mrrorigin.workspace;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProjectRepository extends JpaRepository<Project, UUID> {

    Optional<Project> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    List<Project> findAllByWorkspaceIdOrderByCreatedAtAsc(UUID workspaceId);

    boolean existsByWorkspaceIdAndDomain(UUID workspaceId, String domain);

    /**
     * Keyset page ordered by id, for the weekly-summary scheduler's own tick (#59): unlike {@code
     * findAll()}, repeated calls with the previous page's last id as {@code id} make every project
     * eventually reachable within one tick, instead of a fixed cap permanently starving projects
     * beyond it.
     */
    List<Project> findByIdGreaterThanOrderByIdAsc(UUID id, Pageable pageable);
}
