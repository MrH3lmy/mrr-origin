package com.mrrorigin.workspace;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "workspaces")
public class Workspace {

    @Id
    private UUID id;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(nullable = false, unique = true, length = 80)
    private String slug;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "reporting_currency", nullable = false, length = 3)
    private String reportingCurrency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private WorkspaceStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Workspace() {}

    Workspace(UUID id, String name, String slug, String reportingCurrency) {
        this.id = id;
        this.name = name;
        this.slug = slug;
        this.reportingCurrency = reportingCurrency;
        this.status = WorkspaceStatus.ACTIVE;
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.updatedAt = createdAt;
    }

    UUID id() {
        return id;
    }

    String name() {
        return name;
    }

    String slug() {
        return slug;
    }

    String reportingCurrency() {
        return reportingCurrency;
    }

    WorkspaceStatus status() {
        return status;
    }

    OffsetDateTime createdAt() {
        return createdAt;
    }
}
