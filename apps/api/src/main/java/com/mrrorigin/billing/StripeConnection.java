package com.mrrorigin.billing;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Per ADR-0003, this row holds only non-secret connection metadata. There is no OAuth access or
 * refresh token field anywhere on this entity, by design: all Stripe API calls are made with the
 * centrally configured platform secret key and the {@code Stripe-Account} header.
 */
@Entity
@Table(name = "stripe_connections")
public class StripeConnection {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false, unique = true)
    private UUID workspaceId;

    @Column(name = "stripe_account_id", nullable = false)
    private String stripeAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private StripeConnectionMode mode;

    @Column(name = "granted_scope", nullable = false, length = 32)
    private String grantedScope;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private StripeConnectionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 16)
    private StripeVerificationStatus verificationStatus;

    /** Reserved for the resumable backfill cursor implemented in #12; unused until then. */
    @Column(name = "sync_checkpoint")
    private String syncCheckpoint;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "connected_at", nullable = false)
    private OffsetDateTime connectedAt;

    @Column(name = "disconnected_at")
    private OffsetDateTime disconnectedAt;

    @Column(name = "last_verified_at")
    private OffsetDateTime lastVerifiedAt;

    @Column(name = "last_verification_failed_at")
    private OffsetDateTime lastVerificationFailedAt;

    protected StripeConnection() {}

    StripeConnection(
            UUID id, UUID workspaceId, String stripeAccountId, StripeConnectionMode mode, String grantedScope) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        applyNewGrant(stripeAccountId, mode, grantedScope);
    }

    /** Reapplies a fresh OAuth grant onto this workspace's one connection row (connect or reconnect). */
    void applyNewGrant(String stripeAccountId, StripeConnectionMode mode, String grantedScope) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        this.stripeAccountId = stripeAccountId;
        this.mode = mode;
        this.grantedScope = grantedScope;
        this.status = StripeConnectionStatus.PENDING;
        this.verificationStatus = StripeVerificationStatus.UNVERIFIED;
        this.connectedAt = now;
        this.disconnectedAt = null;
        this.lastVerifiedAt = null;
        this.lastVerificationFailedAt = null;
        this.updatedAt = now;
    }

    void markVerified() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        this.status = StripeConnectionStatus.ACTIVE;
        this.verificationStatus = StripeVerificationStatus.VERIFIED;
        this.lastVerifiedAt = now;
        this.updatedAt = now;
    }

    /** A transient/ambiguous verification failure (e.g. network error). Status is left unchanged. */
    void markVerificationFailedTransiently() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        this.verificationStatus = StripeVerificationStatus.FAILED;
        this.lastVerificationFailedAt = now;
        this.updatedAt = now;
    }

    /** An authorization error from Stripe: the account no longer grants us access. */
    void markRevoked() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        this.status = StripeConnectionStatus.REVOKED;
        this.verificationStatus = StripeVerificationStatus.FAILED;
        this.lastVerificationFailedAt = now;
        this.updatedAt = now;
    }

    void markDisconnected() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        this.status = StripeConnectionStatus.DISCONNECTED;
        this.disconnectedAt = now;
        this.updatedAt = now;
    }

    /** Called when a replacement account/mode is about to overwrite this row, so a future backfill (#12) never reuses another account's cursor. */
    void clearSyncCheckpoint() {
        this.syncCheckpoint = null;
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /** Persists the resumable backfill cursor (#12); see {@link StripeBackfillCheckpoint}. */
    void applySyncCheckpoint(String syncCheckpoint) {
        this.syncCheckpoint = syncCheckpoint;
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    String syncCheckpoint() {
        return syncCheckpoint;
    }

    boolean isLive() {
        return status == StripeConnectionStatus.ACTIVE || status == StripeConnectionStatus.PENDING;
    }

    UUID id() {
        return id;
    }

    UUID workspaceId() {
        return workspaceId;
    }

    String stripeAccountId() {
        return stripeAccountId;
    }

    StripeConnectionMode mode() {
        return mode;
    }

    String grantedScope() {
        return grantedScope;
    }

    StripeConnectionStatus status() {
        return status;
    }

    StripeVerificationStatus verificationStatus() {
        return verificationStatus;
    }

    OffsetDateTime createdAt() {
        return createdAt;
    }

    OffsetDateTime updatedAt() {
        return updatedAt;
    }

    OffsetDateTime connectedAt() {
        return connectedAt;
    }

    OffsetDateTime disconnectedAt() {
        return disconnectedAt;
    }

    OffsetDateTime lastVerifiedAt() {
        return lastVerifiedAt;
    }

    OffsetDateTime lastVerificationFailedAt() {
        return lastVerificationFailedAt;
    }
}
