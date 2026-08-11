package com.mrrorigin.billing;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface StripeWebhookEventRepository extends JpaRepository<StripeWebhookEvent, UUID> {

    Optional<StripeWebhookEvent> findByStripeEventId(String stripeEventId);
}
