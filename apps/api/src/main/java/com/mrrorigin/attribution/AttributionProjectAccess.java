package com.mrrorigin.attribution;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared project-ownership check for authenticated attribution-recovery APIs (#84), mirroring {@code
 * tracking.TrackingProjectAccess}'s convention: a project id is never trusted to belong to the path's
 * workspace id just because it is a valid UUID, so every request re-validates it against {@code
 * projects} directly.
 */
@Component
class AttributionProjectAccess {

    private final JdbcClient jdbc;

    AttributionProjectAccess(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void requireProjectInWorkspace(UUID workspaceId, UUID projectId) {
        boolean owned = jdbc.sql("SELECT EXISTS (SELECT 1 FROM projects WHERE id = :projectId AND workspace_id = :workspaceId)")
                .param("projectId", projectId)
                .param("workspaceId", workspaceId)
                .query(Boolean.class)
                .single();
        if (!owned) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }
    }
}
