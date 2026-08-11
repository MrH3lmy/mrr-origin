package com.mrrorigin.billing;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface StripeConnectionRepository extends JpaRepository<StripeConnection, UUID> {

    Optional<StripeConnection> findByWorkspaceId(UUID workspaceId);
}
