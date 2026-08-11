package com.mrrorigin.workspace;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    boolean existsBySlug(String slug);

    List<Workspace> findAllByIdIn(Collection<UUID> ids);
}
