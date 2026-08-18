package com.mrrorigin.workspace;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ProjectRepository extends JpaRepository<Project, UUID> {

    Optional<Project> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    List<Project> findAllByWorkspaceIdOrderByCreatedAtAsc(UUID workspaceId);

    boolean existsByWorkspaceIdAndDomain(UUID workspaceId, String domain);

    /**
     * Keyset page ordered by id, for the weekly-summary scheduler's own tick (#59): unlike {@code
     * findAll()}, repeated calls with the previous page's last id as {@code id} make every project
     * eventually reachable within one tick, instead of a fixed cap permanently starving projects
     * beyond it.
     *
     * <p>Excludes projects whose workspace is {@code DELETING} (#62's accepted contract: scheduled-job
     * writes stop once a workspace enters that state) -- filtered here, at the source of the
     * scheduler's candidate set, rather than as a per-project check downstream.
     */
    @Query("""
            SELECT p FROM Project p
            WHERE p.id > :id AND NOT EXISTS (
                SELECT 1 FROM Workspace w WHERE w.id = p.workspaceId AND w.status = com.mrrorigin.workspace.WorkspaceStatus.DELETING)
            ORDER BY p.id ASC
            """)
    List<Project> findByIdGreaterThanOrderByIdAsc(UUID id, Pageable pageable);
}
