package com.mrrorigin.billing;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
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

    private final StripeConnectionRepository connections;
    private final StripeOauthStateService oauthStates;
    private final StripeConnectClient stripeClient;
    private final StripeConnectProperties properties;
    private final WorkspaceContext workspaceContext;

    public StripeConnectionService(
            StripeConnectionRepository connections,
            StripeOauthStateService oauthStates,
            StripeConnectClient stripeClient,
            StripeConnectProperties properties,
            WorkspaceContext workspaceContext) {
        this.connections = connections;
        this.oauthStates = oauthStates;
        this.stripeClient = stripeClient;
        this.properties = properties;
        this.workspaceContext = workspaceContext;
    }

    @Transactional
    public String initiateConnection(UUID workspaceId, StripeConnectionMode mode) {
        workspaceContext.requireManager(workspaceId);
        if (!properties.isConfigured(mode)) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Stripe Connect is not configured for this mode");
        }

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

        StripeConnection connection = connections
                .findByWorkspaceId(initiator.workspaceId())
                .orElse(null);
        if (connection == null) {
            connection = new StripeConnection(
                    UUID.randomUUID(),
                    initiator.workspaceId(),
                    exchanged.stripeAccountId(),
                    initiator.mode(),
                    exchanged.scope());
        } else {
            connection.applyNewGrant(exchanged.stripeAccountId(), initiator.mode(), exchanged.scope());
        }

        verifyAndUpdateStatus(connection);
        connections.saveAndFlush(connection);
        return ConnectionOutcome.from(connection);
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

        stripeClient.deauthorize(connection.mode(), connection.stripeAccountId());
        connection.markDisconnected();
        connections.saveAndFlush(connection);
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
