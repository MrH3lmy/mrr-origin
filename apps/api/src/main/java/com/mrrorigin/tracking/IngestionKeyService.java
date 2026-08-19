package com.mrrorigin.tracking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Issues write-only project credentials and resolves only active credentials. */
@Service
public class IngestionKeyService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private final JdbcClient jdbc;

    public IngestionKeyService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public IssuedKey issue(UUID workspaceId, UUID projectId) {
        ensureProjectOwnedBy(workspaceId, projectId);
        return insert(workspaceId, projectId);
    }

    @Transactional
    public IssuedKey rotate(UUID workspaceId, UUID projectId) {
        ensureProjectOwnedBy(workspaceId, projectId);
        jdbc.sql("""
                UPDATE project_ingestion_keys
                SET revoked_at = :revokedAt
                WHERE workspace_id = :workspaceId AND project_id = :projectId AND revoked_at IS NULL
                """)
                .param("revokedAt", OffsetDateTime.now(ZoneOffset.UTC))
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .update();
        return insert(workspaceId, projectId);
    }

    /**
     * Revokes every active ingestion key across every project in the workspace, in one statement --
     * the #62 workspace-deletion admission step. Idempotent (only touches {@code revoked_at IS NULL}
     * rows), so replaying it after a crash is harmless.
     */
    @Transactional
    public int revokeAllForWorkspace(UUID workspaceId) {
        return jdbc.sql("""
                UPDATE project_ingestion_keys
                SET revoked_at = :revokedAt
                WHERE workspace_id = :workspaceId AND revoked_at IS NULL
                """)
                .param("revokedAt", OffsetDateTime.now(ZoneOffset.UTC))
                .param("workspaceId", workspaceId)
                .update();
    }

    @Transactional
    public boolean revoke(UUID workspaceId, UUID projectId, UUID keyId) {
        return jdbc.sql("""
                UPDATE project_ingestion_keys
                SET revoked_at = :revokedAt
                WHERE id = :keyId AND workspace_id = :workspaceId
                  AND project_id = :projectId AND revoked_at IS NULL
                """)
                .param("revokedAt", OffsetDateTime.now(ZoneOffset.UTC))
                .param("keyId", keyId)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .update() == 1;
    }

    /** The project's current active (non-revoked) key, if one exists, without its secret. */
    @Transactional(readOnly = true)
    public Optional<ActiveKeySummary> getActive(UUID workspaceId, UUID projectId) {
        try {
            return Optional.of(jdbc.sql("""
                    SELECT id, key_prefix, created_at
                    FROM project_ingestion_keys
                    WHERE workspace_id = :workspaceId AND project_id = :projectId AND revoked_at IS NULL
                    """)
                    .param("workspaceId", workspaceId)
                    .param("projectId", projectId)
                    .query((rs, rowNum) -> new ActiveKeySummary(
                            rs.getObject("id", UUID.class),
                            rs.getString("key_prefix"),
                            rs.getObject("created_at", OffsetDateTime.class)))
                    .single());
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Transactional(readOnly = true)
    public Optional<ResolvedProject> resolve(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(jdbc.sql("""
                    SELECT id, workspace_id, project_id
                    FROM project_ingestion_keys
                    WHERE secret_hash = :secretHash AND revoked_at IS NULL
                    """)
                    .param("secretHash", hash(rawKey))
                    .query((rs, rowNum) -> new ResolvedProject(
                            rs.getObject("id", UUID.class),
                            rs.getObject("workspace_id", UUID.class),
                            rs.getObject("project_id", UUID.class)))
                    .single());
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    /**
     * Best-effort project attribution for an ingestion key that failed {@link #resolve}, using only
     * the key's public, non-secret prefix (never the secret itself or its hash) -- so a diagnostic
     * can be scoped to the right project for a wrong-secret or revoked key without ever comparing,
     * storing, or logging the raw key. A prefix that matches no issued key at all (a guess, or noise)
     * cannot be attributed to any tenant and yields empty.
     */
    @Transactional(readOnly = true)
    public Optional<ResolvedProject> resolveProjectByPrefixForDiagnostics(String rawKey) {
        if (rawKey == null) {
            return Optional.empty();
        }
        int separator = rawKey.lastIndexOf('_');
        if (separator <= 0 || separator == rawKey.length() - 1) {
            return Optional.empty();
        }
        String prefix = rawKey.substring(0, separator);
        try {
            return Optional.of(jdbc.sql("""
                    SELECT id, workspace_id, project_id
                    FROM project_ingestion_keys
                    WHERE key_prefix = :prefix
                    """)
                    .param("prefix", prefix)
                    .query((rs, rowNum) -> new ResolvedProject(
                            rs.getObject("id", UUID.class),
                            rs.getObject("workspace_id", UUID.class),
                            rs.getObject("project_id", UUID.class)))
                    .single());
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private IssuedKey insert(UUID workspaceId, UUID projectId) {
        UUID id = UUID.randomUUID();
        String prefix = "mrr_" + randomHex(6);
        String rawKey = prefix + "_" + randomHex(32);
        jdbc.sql("""
                INSERT INTO project_ingestion_keys
                    (id, workspace_id, project_id, key_prefix, secret_hash)
                VALUES (:id, :workspaceId, :projectId, :prefix, :secretHash)
                """)
                .param("id", id)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("prefix", prefix)
                .param("secretHash", hash(rawKey))
                .update();
        return new IssuedKey(id, rawKey, prefix);
    }

    private void ensureProjectOwnedBy(UUID workspaceId, UUID projectId) {
        if (jdbc.sql("SELECT COUNT(*) FROM projects WHERE id = :projectId AND workspace_id = :workspaceId")
                        .param("projectId", projectId)
                        .param("workspaceId", workspaceId)
                        .query(Integer.class)
                        .single()
                != 1) {
            throw new IllegalArgumentException("Project does not belong to workspace");
        }
    }

    private static String randomHex(int byteCount) {
        byte[] bytes = new byte[byteCount];
        RANDOM.nextBytes(bytes);
        return HEX.formatHex(bytes);
    }

    private static String hash(String rawKey) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(rawKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record IssuedKey(UUID id, String secret, String prefix) {}

    public record ResolvedProject(UUID keyId, UUID workspaceId, UUID projectId) {}

    public record ActiveKeySummary(UUID id, String prefix, OffsetDateTime createdAt) {}
}
