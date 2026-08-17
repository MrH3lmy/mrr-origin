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

    /**
     * Best-effort, nullable: captured lazily from the member's own JWT {@code email} claim (see
     * {@link WorkspaceContext#requireMembership}), never required at row-creation time. Used only by
     * weekly-summary recipient resolution (#59); a null value simply excludes the member from that
     * resolution rather than failing anything.
     */
    @Column(length = 320)
    private String email;

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

    /** Nullable; see the field's own doc comment. */
    String email() {
        return email;
    }

    /** Best-effort capture of the member's own email from their JWT claim; see the field's doc comment. */
    void captureEmail(String email) {
        this.email = email;
    }

    OffsetDateTime createdAt() {
        return createdAt;
    }
}
