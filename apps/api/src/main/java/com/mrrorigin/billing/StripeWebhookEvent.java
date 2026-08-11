package com.mrrorigin.billing;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An immutable raw Stripe webhook event, per ADR-0002 and ARCHITECTURE.md's reliability rules.
 * The verified payload and its identity fields are never mutated after insert; only the
 * processing/retry/replay bookkeeping columns are reserved for the future normalization worker
 * (#13), which this module (#11) does not implement.
 */
@Entity
@Table(name = "stripe_webhook_events")
public class StripeWebhookEvent {

    @Id
    private UUID id;

    @Column(name = "stripe_event_id", nullable = false, unique = true)
    private String stripeEventId;

    @Column(name = "stripe_account_id", nullable = false)
    private String stripeAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private StripeConnectionMode mode;

    /** Null iff no live connection matched {@link #stripeAccountId} at receipt time (see {@link #processingState}). */
    @Column(name = "connection_id")
    private UUID connectionId;

    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "api_version")
    private String apiVersion;

    @Column(name = "stripe_created_at", nullable = false)
    private OffsetDateTime stripeCreatedAt;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_state", nullable = false, length = 16)
    private StripeWebhookProcessingState processingState;

    /** The exact verified request body, stored as JSONB; never re-derived or edited after insert. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_attempted_at")
    private OffsetDateTime lastAttemptedAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "replay_count", nullable = false)
    private int replayCount;

    @Column(name = "last_replayed_at")
    private OffsetDateTime lastReplayedAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected StripeWebhookEvent() {}

    StripeWebhookEvent(
            UUID id,
            String stripeEventId,
            String stripeAccountId,
            StripeConnectionMode mode,
            UUID connectionId,
            UUID workspaceId,
            String eventType,
            String apiVersion,
            OffsetDateTime stripeCreatedAt,
            StripeWebhookProcessingState processingState,
            String payload) {
        this.id = id;
        this.stripeEventId = stripeEventId;
        this.stripeAccountId = stripeAccountId;
        this.mode = mode;
        this.connectionId = connectionId;
        this.workspaceId = workspaceId;
        this.eventType = eventType;
        this.apiVersion = apiVersion;
        this.stripeCreatedAt = stripeCreatedAt;
        this.processingState = processingState;
        this.payload = payload;
        this.attemptCount = 0;
        this.replayCount = 0;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        this.receivedAt = now;
        this.updatedAt = now;
    }

    UUID id() {
        return id;
    }

    String stripeEventId() {
        return stripeEventId;
    }

    String stripeAccountId() {
        return stripeAccountId;
    }

    StripeConnectionMode mode() {
        return mode;
    }

    UUID connectionId() {
        return connectionId;
    }

    UUID workspaceId() {
        return workspaceId;
    }

    String eventType() {
        return eventType;
    }

    String apiVersion() {
        return apiVersion;
    }

    OffsetDateTime stripeCreatedAt() {
        return stripeCreatedAt;
    }

    OffsetDateTime receivedAt() {
        return receivedAt;
    }

    StripeWebhookProcessingState processingState() {
        return processingState;
    }

    String payload() {
        return payload;
    }
}
