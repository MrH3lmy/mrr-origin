package com.mrrorigin.tracking;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Live, bounded, project-scoped installation verification (#8), suitable for the future #21
 * onboarding UI's "check my install" flow.
 *
 * <p>A verification attempt is proven only by an event actually accepted through the existing public
 * ingestion contract ({@code /api/public/v1/events}) -- see V13's migration comment for why this
 * cannot weaken that contract's own key/origin/payload checks. The founder's dashboard (or their
 * tracker's own custom-event API, {@code tracker.track(name, properties)}) sends a {@code custom}
 * event named {@link #VERIFICATION_EVENT_NAME} carrying {@code properties.verificationToken}; {@link
 * EventIngestionService} recognizes it inline (mirroring how it already special-cases {@code
 * identify}) and calls {@link #tryMarkSucceeded} in the very same transaction as the event insert, so
 * success is durable exactly when -- and only when -- the underlying event actually is.
 *
 * <p>{@code EXPIRED} is never stored; it is derived at read time from {@code expires_at}, and {@link
 * #tryMarkSucceeded} only ever matches a row that is still {@code PENDING} and unexpired. That makes
 * both expiry and replay deterministic: a token that arrives after its attempt expired can never
 * retroactively succeed, and a second event for an already-{@code SUCCEEDED} token is accepted as an
 * ordinary (harmless) duplicate custom event but never changes verification state.
 */
@Service
public class TrackingVerificationService {

    /** Sent as the custom event's {@code name} to trigger verification matching; no tracker SDK change needed. */
    public static final String VERIFICATION_EVENT_NAME = "mrr_origin_verification_ping";

    static final Duration TOKEN_TTL = Duration.ofMinutes(15);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final JdbcClient jdbc;
    private final Clock clock;

    TrackingVerificationService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Returns this project's current live attempt, reusing an existing unexpired PENDING one so a
     * founder repeatedly clicking "verify" is idempotent rather than accumulating attempts. Starts a
     * fresh attempt (new token) if none exists, the prior one expired, or the prior one already
     * succeeded (verifying again -- e.g. after reconfiguring -- is always allowed).
     */
    @Transactional
    public VerificationAttempt start(UUID workspaceId, UUID projectId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Optional<VerificationAttempt> reusable = latest(workspaceId, projectId)
                .filter(attempt -> attempt.status() == VerificationStatus.PENDING && attempt.expiresAt().isAfter(now));
        if (reusable.isPresent()) {
            return reusable.get();
        }
        UUID id = UUID.randomUUID();
        OffsetDateTime expiresAt = now.plus(TOKEN_TTL);
        String token = newToken();
        jdbc.sql("""
                        INSERT INTO tracking_verification_attempts
                            (id, workspace_id, project_id, token, status, created_at, expires_at)
                        VALUES (:id, :workspaceId, :projectId, :token, 'PENDING', :createdAt, :expiresAt)
                        """)
                .param("id", id)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("token", token)
                .param("createdAt", now)
                .param("expiresAt", expiresAt)
                .update();
        return new VerificationAttempt(id, token, VerificationStatus.PENDING, now, expiresAt, null, null);
    }

    /** The project's most recent attempt, with EXPIRED derived from wall-clock time, if one has ever started. */
    @Transactional(readOnly = true)
    public Optional<VerificationAttempt> status(UUID workspaceId, UUID projectId) {
        return latest(workspaceId, projectId);
    }

    /**
     * Called from within {@link EventIngestionService}'s own transaction when an accepted event
     * carries a verification token. A no-op (returns {@code false}) unless the token belongs to this
     * exact project and matches a currently-PENDING, unexpired attempt -- so a token guessed for, or
     * replayed from, a different project or a lapsed attempt can never succeed.
     */
    boolean tryMarkSucceeded(UUID workspaceId, UUID projectId, String token, OffsetDateTime occurredAt, String externalEventId) {
        if (token == null || token.isBlank()) {
            return false;
        }
        int updated = jdbc.sql("""
                        UPDATE tracking_verification_attempts
                        SET status = 'SUCCEEDED', succeeded_at = :succeededAt, received_external_event_id = :eventId
                        WHERE workspace_id = :workspaceId AND project_id = :projectId AND token = :token
                          AND status = 'PENDING' AND expires_at >= :succeededAt
                        """)
                .param("succeededAt", occurredAt)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .param("token", token)
                .param("eventId", externalEventId)
                .update();
        return updated == 1;
    }

    private Optional<VerificationAttempt> latest(UUID workspaceId, UUID projectId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return jdbc.sql("""
                        SELECT id, token, status, created_at, expires_at, succeeded_at, received_external_event_id
                        FROM tracking_verification_attempts
                        WHERE workspace_id = :workspaceId AND project_id = :projectId
                        ORDER BY created_at DESC
                        LIMIT 1
                        """)
                .param("workspaceId", workspaceId)
                .param("projectId", projectId)
                .query((rs, rowNum) -> {
                    String storedStatus = rs.getString("status");
                    OffsetDateTime expiresAt = rs.getObject("expires_at", OffsetDateTime.class);
                    VerificationStatus status = "SUCCEEDED".equals(storedStatus)
                            ? VerificationStatus.SUCCEEDED
                            : (expiresAt.isAfter(now) ? VerificationStatus.PENDING : VerificationStatus.EXPIRED);
                    return new VerificationAttempt(
                            rs.getObject("id", UUID.class),
                            rs.getString("token"),
                            status,
                            rs.getObject("created_at", OffsetDateTime.class),
                            expiresAt,
                            rs.getObject("succeeded_at", OffsetDateTime.class),
                            rs.getString("received_external_event_id"));
                })
                .optional();
    }

    /** True for a "custom" event carrying {@link #VERIFICATION_EVENT_NAME} with a string verificationToken property. */
    static boolean isVerificationPing(String eventType, Map<String, Object> payload) {
        return "custom".equals(eventType)
                && VERIFICATION_EVENT_NAME.equals(payload.get("name"))
                && verificationToken(payload) != null;
    }

    static String verificationToken(Map<String, Object> payload) {
        if (!(payload.get("properties") instanceof Map<?, ?> properties)) {
            return null;
        }
        Object token = properties.get("verificationToken");
        return token instanceof String value && !value.isBlank() ? value : null;
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return TOKEN_ENCODER.encodeToString(bytes);
    }

    public enum VerificationStatus {
        PENDING,
        SUCCEEDED,
        EXPIRED
    }

    public record VerificationAttempt(
            UUID id,
            String token,
            VerificationStatus status,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt,
            OffsetDateTime succeededAt,
            String receivedExternalEventId) {}

    static TrackingManagementException notFound() {
        return new TrackingManagementException(
                HttpStatus.NOT_FOUND, "verification_attempt_not_found", "No verification attempt has been started for this project");
    }
}
