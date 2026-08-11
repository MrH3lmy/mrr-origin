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
     */
    Optional<StripeConnection> findByStripeAccountIdAndStatusIn(
            String stripeAccountId, Collection<StripeConnectionStatus> statuses);
}
