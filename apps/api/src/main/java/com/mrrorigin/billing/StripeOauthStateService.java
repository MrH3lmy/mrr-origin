package com.mrrorigin.billing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and consumes cryptographically random, single-use, expiring OAuth CSRF state tokens bound
 * to the workspace and actor who initiated the connection. Only the SHA-256 hash of the state value
 * is ever stored, mirroring {@code tracking.IngestionKeyService}'s hashed-secret pattern.
 */
@Service
class StripeOauthStateService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private final JdbcClient jdbc;
    private final StripeConnectProperties properties;

    StripeOauthStateService(JdbcClient jdbc, StripeConnectProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Transactional
    String issue(UUID workspaceId, String subjectId, StripeConnectionMode mode) {
        String rawState = randomState();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO stripe_oauth_states (id, workspace_id, subject_id, mode, state_hash, expires_at)
                        VALUES (:id, :workspaceId, :subjectId, :mode, :stateHash, :expiresAt)
                        """)
                .param("id", UUID.randomUUID())
                .param("workspaceId", workspaceId)
                .param("subjectId", subjectId)
                .param("mode", mode.name())
                .param("stateHash", hash(rawState))
                .param("expiresAt", now.plus(properties.stateTtl()))
                .update();
        return rawState;
    }

    /**
     * Atomically consumes a state token. Empty if it is missing, malformed, expired, or already used.
     * Runs in its own transaction that commits independently of the caller: consumption must stick
     * even if the caller later rejects the callback for an unrelated reason (e.g. a scope mismatch)
     * and rolls back its own transaction, otherwise a rejected attempt could be silently replayed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Optional<ConsumedState> consume(String rawState) {
        if (rawState == null || rawState.isBlank()) {
            return Optional.empty();
        }
        String hash;
        try {
            hash = hash(rawState);
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ConsumedState found;
        try {
            found = jdbc.sql("""
                            SELECT id, workspace_id, subject_id, mode
                            FROM stripe_oauth_states
                            WHERE state_hash = :stateHash AND consumed_at IS NULL AND expires_at > :now
                            FOR UPDATE
                            """)
                    .param("stateHash", hash)
                    .param("now", now)
                    .query((rs, rowNum) -> new ConsumedState(
                            rs.getObject("id", UUID.class),
                            rs.getObject("workspace_id", UUID.class),
                            rs.getString("subject_id"),
                            StripeConnectionMode.valueOf(rs.getString("mode"))))
                    .single();
        } catch (EmptyResultDataAccessException notFoundOrExpiredOrConsumed) {
            return Optional.empty();
        }

        int updated = jdbc.sql(
                        "UPDATE stripe_oauth_states SET consumed_at = :now WHERE id = :id AND consumed_at IS NULL")
                .param("now", now)
                .param("id", found.id())
                .update();
        return updated == 1 ? Optional.of(found) : Optional.empty();
    }

    private static String randomState() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String rawState) {
        try {
            return HEX.formatHex(
                    MessageDigest.getInstance("SHA-256").digest(rawState.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    record ConsumedState(UUID id, UUID workspaceId, String subjectId, StripeConnectionMode mode) {}
}
