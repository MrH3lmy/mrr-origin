package com.mrrorigin.tracking;

import java.net.IDN;
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
