package com.mrrorigin.workspace;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@IdClass(WorkspaceMemberId.class)
@Table(name = "workspace_members")
public class WorkspaceMember {

    @Id
    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Id
    @Column(name = "subject_id", nullable = false, length = 255)
    private String subjectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private WorkspaceRole role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected WorkspaceMember() {}

    WorkspaceMember(UUID workspaceId, String subjectId, WorkspaceRole role) {
        this.workspaceId = workspaceId;
        this.subjectId = subjectId;
        this.role = role;
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    UUID workspaceId() {
        return workspaceId;
    }

    String subjectId() {
        return subjectId;
    }

    WorkspaceRole role() {
        return role;
    }

    OffsetDateTime createdAt() {
        return createdAt;
    }
}
