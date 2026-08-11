package com.mrrorigin.workspace;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "projects")
public class Project {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(nullable = false, length = 253)
    private String domain;

    @Column(name = "public_key", nullable = false, unique = true, length = 80)
    private String publicKey;

    @Column(nullable = false, length = 64)
    private String timezone;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Project() {}

    Project(UUID id, UUID workspaceId, String name, String domain, String publicKey, String timezone) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.name = name;
        this.domain = domain;
        this.publicKey = publicKey;
        this.timezone = timezone;
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.updatedAt = createdAt;
    }

    UUID id() {
        return id;
    }

    UUID workspaceId() {
        return workspaceId;
    }

    String name() {
        return name;
    }

    String domain() {
        return domain;
    }

    String publicKey() {
        return publicKey;
    }

    String timezone() {
        return timezone;
    }

    OffsetDateTime createdAt() {
        return createdAt;
    }
}
