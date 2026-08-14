package com.mrrorigin.tracking;

import java.net.IDN;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Manages normalized host-only origins for one tenant-owned project. */
@Service
public class AllowedDomainService {

    private final JdbcClient jdbc;

    public AllowedDomainService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public AllowedDomain add(UUID workspaceId, UUID projectId, String candidate) {
        String domain = normalize(candidate);
        UUID id = UUID.randomUUID();
        int inserted = jdbc.sql("""
                        INSERT INTO project_allowed_domains
                            (id, workspace_id, project_id, domain)
                        SELECT :id, :workspaceId, id, :domain
                        FROM projects
                        WHERE id = :projectId AND workspace_id = :workspaceId
                        """)
                .param("id", id)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("domain", domain)
                .update();
        if (inserted != 1) {
            throw new IllegalArgumentException("Project does not belong to workspace");
        }
        return new AllowedDomain(id, workspaceId, projectId, domain);
    }

    @Transactional(readOnly = true)
    public List<AllowedDomain> list(UUID workspaceId, UUID projectId) {
        return jdbc.sql("""
                        SELECT id, domain FROM project_allowed_domains
                        WHERE workspace_id = :workspaceId AND project_id = :projectId
                        ORDER BY created_at ASC
                        """)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .query((rs, rowNum) -> new AllowedDomain(
                        rs.getObject("id", UUID.class), workspaceId, projectId, rs.getString("domain")))
                .list();
    }

    @Transactional
    public boolean remove(UUID workspaceId, UUID projectId, UUID domainId) {
        return jdbc.sql("""
                        DELETE FROM project_allowed_domains
                        WHERE id = :domainId AND workspace_id = :workspaceId AND project_id = :projectId
                        """)
                .param("domainId", domainId)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .update()
                == 1;
    }

    @Transactional(readOnly = true)
    public boolean isAllowed(UUID workspaceId, UUID projectId, String origin) {
        String domain = normalizeOrigin(origin);
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM project_allowed_domains
                            WHERE workspace_id = :workspaceId AND project_id = :projectId AND domain = :domain)
                        """)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("domain", domain)
                .query(Boolean.class)
                .single();
    }

    static String normalizeOrigin(String candidate) {
        try {
            URI origin = URI.create(candidate);
            if (!("http".equalsIgnoreCase(origin.getScheme()) || "https".equalsIgnoreCase(origin.getScheme()))
                    || origin.getRawAuthority() == null || origin.getUserInfo() != null
                    || origin.getPath() != null && !origin.getPath().isEmpty()
                    || origin.getQuery() != null || origin.getFragment() != null) {
                throw new IllegalArgumentException("Origin must contain only an HTTP(S) scheme, host, and optional port");
            }
            String authority = origin.getRawAuthority();
            String host = authority.startsWith("[")
                    ? authority.substring(0, authority.indexOf(']') + 1)
                    : authority.replaceFirst(":\\d+$", "");
            return normalize(host);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Origin is invalid", invalid);
        }
    }

    static String normalize(String candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("Allowed domain is required");
        }
        String domain = candidate.strip().toLowerCase(Locale.ROOT);
        if (domain.endsWith(".")) {
            domain = domain.substring(0, domain.length() - 1);
        }
        if (domain.isEmpty() || domain.contains(":") || domain.contains("/") || domain.contains("\\")) {
            throw new IllegalArgumentException("Allowed domain must be a host without a scheme, port, or path");
        }
        try {
            domain = IDN.toASCII(domain, IDN.USE_STD3_ASCII_RULES);
        } catch (IllegalArgumentException invalidDomain) {
            throw new IllegalArgumentException("Allowed domain is invalid", invalidDomain);
        }
        if (domain.length() > 253) {
            throw new IllegalArgumentException("Allowed domain exceeds 253 characters");
        }
        return domain;
    }

    public record AllowedDomain(UUID id, UUID workspaceId, UUID projectId, String domain) {}
}
