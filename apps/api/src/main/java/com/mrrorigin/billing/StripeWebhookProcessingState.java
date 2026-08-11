package com.mrrorigin.billing;

/**
 * Reserved for the future normalization worker (#13). This module (#11) only ever writes PENDING
 * or ORPHANED at insert time and never updates a row afterward.
 */
public enum StripeWebhookProcessingState {
    /** Verified, durably stored, and linked to a live connection; not yet normalized. */
    PENDING,
    /** No live connection matched the event's account; this event is never processed into a workspace. */
    ORPHANED,
    /** Reserved: normalization completed successfully. */
    PROCESSED,
    /** Reserved: normalization attempted and failed; see attempt_count/last_error. */
    FAILED
}
