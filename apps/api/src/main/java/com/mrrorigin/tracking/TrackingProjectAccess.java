package com.mrrorigin.tracking;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Shared project-ownership check for the authenticated tracking-management APIs (#8). */
@Component
class TrackingProjectAccess {

    private final JdbcClient jdbc;

    TrackingProjectAccess(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void requireProjectInWorkspace(UUID workspaceId, UUID projectId) {
        boolean owned = jdbc.sql("SELECT EXISTS (SELECT 1 FROM projects WHERE id = :projectId AND workspace_id = :workspaceId)")
                .param("projectId", projectId)
                .param("workspaceId", workspaceId)
                .query(Boolean.class)
                .single();
        if (!owned) {
            throw new TrackingManagementException(HttpStatus.NOT_FOUND, "project_not_found", "Project not found");
        }
    }
}
