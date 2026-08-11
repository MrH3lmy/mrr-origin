package com.mrrorigin.billing;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StripeConnectionController {

    private final StripeConnectionService service;

    public StripeConnectionController(StripeConnectionService service) {
        this.service = service;
    }

    @PostMapping("/api/workspaces/{workspaceId}/stripe-connection/oauth/start")
    public AuthorizationUrlResponse start(
            @PathVariable UUID workspaceId, @Valid @RequestBody StartOauthRequest request) {
        return new AuthorizationUrlResponse(service.initiateConnection(workspaceId, request.mode()));
    }

    /**
     * Public: this is the redirect target the founder's browser lands on after Stripe's consent
     * screen, so it cannot carry our own bearer token. Its security comes entirely from the
     * single-use, expiring, workspace/actor-bound {@code state} value minted by {@link #start}.
     */
    @GetMapping("/api/stripe/connections/oauth/callback")
    public ConnectionResponse callback(
            @RequestParam String state,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String error) {
        return ConnectionResponse.from(service.completeConnection(state, code, scope, error));
    }

    @GetMapping("/api/workspaces/{workspaceId}/stripe-connection")
    public ConnectionResponse inspect(@PathVariable UUID workspaceId) {
        return ConnectionResponse.from(service.getConnection(workspaceId));
    }

    @DeleteMapping("/api/workspaces/{workspaceId}/stripe-connection")
    public ConnectionResponse disconnect(@PathVariable UUID workspaceId) {
        return ConnectionResponse.from(service.disconnect(workspaceId));
    }

    public record StartOauthRequest(@NotNull StripeConnectionMode mode) {}

    public record AuthorizationUrlResponse(String authorizationUrl) {}

    public record ConnectionResponse(
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

        static ConnectionResponse from(StripeConnectionService.ConnectionOutcome outcome) {
            return new ConnectionResponse(
                    outcome.id(),
                    outcome.workspaceId(),
                    outcome.stripeAccountId(),
                    outcome.mode(),
                    outcome.grantedScope(),
                    outcome.status(),
                    outcome.verificationStatus(),
                    outcome.connectedAt(),
                    outcome.disconnectedAt(),
                    outcome.lastVerifiedAt(),
                    outcome.lastVerificationFailedAt());
        }
    }
}
