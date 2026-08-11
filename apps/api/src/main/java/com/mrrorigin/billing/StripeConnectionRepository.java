package com.mrrorigin.billing;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface StripeConnectionRepository extends JpaRepository<StripeConnection, UUID> {

    Optional<StripeConnection> findByWorkspaceId(UUID workspaceId);

    /**
     * At most one row can match, for any given {@code stripeAccountId}, since
     * {@code uq_stripe_connections_active_account} enforces uniqueness while a connection is live.
     * Deliberately not scoped by {@link StripeConnectionMode}: this is used to check whether an
     * account is already connected to any other workspace at all (in either mode) before allowing
     * a new OAuth connection, since the same Stripe account id is never validly live in both modes
     * at once.
     */
    Optional<StripeConnection> findByStripeAccountIdAndStatusIn(
            String stripeAccountId, Collection<StripeConnectionStatus> statuses);

    /**
     * Mode-scoped variant used for webhook routing: an account id must only ever resolve to a
     * connection recorded in the same Stripe environment (test/live) as the endpoint that received
     * the event, since test-mode and live-mode are separate Stripe environments with separate
     * signing secrets and object graphs. A TEST webhook must never attach to a LIVE connection (or
     * vice versa) even if they happen to share a {@code stripeAccountId} string.
     */
    Optional<StripeConnection> findByStripeAccountIdAndModeAndStatusIn(
            String stripeAccountId, StripeConnectionMode mode, Collection<StripeConnectionStatus> statuses);
}
