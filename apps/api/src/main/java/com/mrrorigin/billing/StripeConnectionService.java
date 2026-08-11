package com.mrrorigin.billing;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import com.mrrorigin.workspace.WorkspaceContext;

/**
 * Orchestrates the Stripe connection lifecycle per ADR-0003: OAuth is the consent flow only, no
 * per-workspace access/refresh token is ever stored, and Stripe API calls use the centrally
 * configured platform key. Webhook ingestion and normalization (#11) are out of scope here.
 */
@Service
@Transactional(readOnly = true)
public class StripeConnectionService {

    private static final String REQUIRED_SCOPE = "read_only";
    private static final List<StripeConnectionStatus> LIVE_STATUSES =
            List.of(StripeConnectionStatus.PENDING, StripeConnectionStatus.ACTIVE);

    /** Namespaces this service's Postgres advisory locks from any other feature that might add its own. */
    private static final int ADVISORY_LOCK_NAMESPACE = 914_002;

    private final StripeConnectionRepository connections;
    private final StripeOauthStateService oauthStates;
    private final StripeConnectClient stripeClient;
    private final StripeConnectProperties properties;
    private final WorkspaceContext workspaceContext;
    private final JdbcClient jdbc;

    public StripeConnectionService(
            StripeConnectionRepository connections,
            StripeOauthStateService oauthStates,
            StripeConnectClient stripeClient,
            StripeConnectProperties properties,
            WorkspaceContext workspaceContext,
            JdbcClient jdbc) {
        this.connections = connections;
        this.oauthStates = oauthStates;
        this.stripeClient = stripeClient;
        this.properties = properties;
        this.workspaceContext = workspaceContext;
        this.jdbc = jdbc;
    }

    @Transactional
    public String initiateConnection(UUID workspaceId, StripeConnectionMode mode) {
        workspaceContext.requireManager(workspaceId);
        if (!properties.isConfigured(mode)) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Stripe Connect is not configured for this mode");
        }
        connections
                .findByWorkspaceId(workspaceId)
                .filter(StripeConnection::isLive)
                .ifPresent(existing -> {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Workspace already has an active Stripe connection; disconnect it before authorizing again");
                });

        String state = oauthStates.issue(workspaceId, workspaceContext.subjectId(), mode);

        UriComponentsBuilder authorizeUri = UriComponentsBuilder.fromUriString(properties.authorizeUri())
                .queryParam("response_type", "code")
                .queryParam("client_id", properties.clientId(mode))
                .queryParam("scope", REQUIRED_SCOPE)
                .queryParam("state", state);
        if (properties.redirectUri() != null && !properties.redirectUri().isBlank()) {
            authorizeUri.queryParam("redirect_uri", properties.redirectUri());
        }
        return authorizeUri.build().toUriString();
    }

    @Transactional
    public ConnectionOutcome completeConnection(String state, String code, String scope, String error) {
        Optional<StripeOauthStateService.ConsumedState> consumed = oauthStates.consume(state);
        if (consumed.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "OAuth state is invalid, expired, mismatched, or already used");
        }
        StripeOauthStateService.ConsumedState initiator = consumed.get();

        if (error != null && !error.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stripe authorization was not granted");
        }
        if (code == null || code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stripe did not return an authorization code");
        }
        if (!REQUIRED_SCOPE.equals(scope)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stripe granted an unexpected scope");
        }

        // Stripe's token exchange itself completes the account connection (see the OAuth reference:
        // re-consuming a code even revokes it), so the "is this workspace already connected" check
        // must happen before that call, not after -- otherwise a rejected 409 could still leave the
        // newly authorized Stripe account connected to us but untracked locally. The advisory lock is
        // transaction-scoped and workspace-keyed: it serializes concurrent callbacks for the same
        // workspace so exactly one of them can exchange the code, and it self-releases on commit or
        // rollback without requiring a pre-existing row to lock.
        lockWorkspaceForConnectionCallback(initiator.workspaceId());
        StripeConnection existing = connections.findByWorkspaceId(initiator.workspaceId()).orElse(null);
        if (existing != null && existing.isLive()) {
            // The state stays consumed either way: this rejection cannot be retried by replaying the
            // same callback, with the same or a different account.
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Workspace already has an active Stripe connection; disconnect it before authorizing again");
        }

        StripeConnectClient.TokenExchangeResult exchanged;
        try {
            exchanged = stripeClient.exchangeAuthorizationCode(initiator.mode(), code);
        } catch (StripeConnectException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Stripe rejected the authorization code");
        }

        if (!REQUIRED_SCOPE.equals(exchanged.scope())) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Stripe granted an unexpected scope");
        }
        boolean expectedLivemode = initiator.mode() == StripeConnectionMode.LIVE;
        if (exchanged.livemode() != expectedLivemode) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Stripe connection mode did not match the requested mode");
        }

        // The account may already be live on a different workspace (its own advisory lock was not
        // held here, only ours). Check proactively for a clear 409 instead of an opaque constraint
        // violation, and never deauthorize the other workspace's connection to "free up" the account.
        Optional<StripeConnection> elsewhere =
                connections.findByStripeAccountIdAndStatusIn(exchanged.stripeAccountId(), LIVE_STATUSES);
        if (elsewhere.isPresent() && !elsewhere.get().workspaceId().equals(initiator.workspaceId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "This Stripe account is already connected to another workspace");
        }

        // existing is guaranteed non-live here (null, DISCONNECTED, or REVOKED); the live case was
        // rejected above before the Stripe call.
        StripeConnection connection;
        if (existing == null) {
            connection = new StripeConnection(
                    UUID.randomUUID(),
                    initiator.workspaceId(),
                    exchanged.stripeAccountId(),
                    initiator.mode(),
                    exchanged.scope());
        } else {
            boolean accountOrModeChanged = existing.mode() != initiator.mode()
                    || !existing.stripeAccountId().equals(exchanged.stripeAccountId());
            if (accountOrModeChanged) {
                // The old backfill cursor belongs to a different Stripe account and must never be reused.
                existing.clearSyncCheckpoint();
            }
            existing.applyNewGrant(exchanged.stripeAccountId(), initiator.mode(), exchanged.scope());
            connection = existing;
        }

        verifyAndUpdateStatus(connection);
        try {
            connections.saveAndFlush(connection);
        } catch (DataIntegrityViolationException raceLostToAnotherWorkspace) {
            // Defense-in-depth backstop for the narrow window between the check above and this save.
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "This Stripe account is already connected to another workspace");
        }
        return ConnectionOutcome.from(connection);
    }

    /**
     * Blocks until this workspace's Postgres transaction-scoped advisory lock is free, then holds it
     * for the rest of the current transaction (it is released automatically on commit or rollback).
     */
    private void lockWorkspaceForConnectionCallback(UUID workspaceId) {
        jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(:namespace, hashtext(:workspaceId))) AS lock_acquired")
                .param("namespace", ADVISORY_LOCK_NAMESPACE)
                .param("workspaceId", workspaceId.toString())
                .query(Integer.class)
                .single();
    }

    public ConnectionOutcome getConnection(UUID workspaceId) {
        workspaceContext.requireManager(workspaceId);
        return connections
                .findByWorkspaceId(workspaceId)
                .map(ConnectionOutcome::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No Stripe connection for this workspace"));
    }

    @Transactional
    public ConnectionOutcome disconnect(UUID workspaceId) {
        workspaceContext.requireManager(workspaceId);
        StripeConnection connection = connections.findByWorkspaceId(workspaceId).orElse(null);
        if (connection == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No Stripe connection for this workspace");
        }
        if (connection.status() == StripeConnectionStatus.DISCONNECTED) {
            return ConnectionOutcome.from(connection);
        }

        StripeDeauthorizationOutcome outcome = stripeClient.deauthorize(connection.mode(), connection.stripeAccountId());
        switch (outcome) {
            case CONFIRMED -> {
                connection.markDisconnected();
                connections.saveAndFlush(connection);
            }
            case REJECTED ->
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, "Stripe rejected the disconnect request; the connection was not changed");
            case UNREACHABLE ->
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Stripe could not be reached to confirm the disconnect; the connection was not changed");
        }
        return ConnectionOutcome.from(connection);
    }

    private void verifyAndUpdateStatus(StripeConnection connection) {
        StripeVerificationOutcome outcome =
                stripeClient.verifyAccountAccess(connection.mode(), connection.stripeAccountId());
        switch (outcome) {
            case VERIFIED -> connection.markVerified();
            case UNAUTHORIZED -> connection.markRevoked();
            case TRANSIENT_FAILURE -> connection.markVerificationFailedTransiently();
        }
    }

    public record ConnectionOutcome(
            UUID id,
            UUID workspaceId,
            String stripeAccountId,
            StripeConnectionMode mode,
            String grantedScope,
            StripeConnectionStatus status,
            StripeVerificationStatus verificationStatus,
            OffsetDateTime connectedAt,
            OffsetDateTime disconnectedAt,
            OffsetDateTime lastVerifiedAt,
            OffsetDateTime lastVerificationFailedAt) {

        static ConnectionOutcome from(StripeConnection connection) {
            return new ConnectionOutcome(
                    connection.id(),
                    connection.workspaceId(),
                    connection.stripeAccountId(),
                    connection.mode(),
                    connection.grantedScope(),
                    connection.status(),
                    connection.verificationStatus(),
                    connection.connectedAt(),
                    connection.disconnectedAt(),
                    connection.lastVerifiedAt(),
                    connection.lastVerificationFailedAt());
        }
    }
}
