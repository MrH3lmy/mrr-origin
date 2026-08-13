package com.mrrorigin.tracking;

/**
 * Project tracking-data deletion phases (#8), always run in this order so every later phase's "is
 * this row still referenced" check only has to look at tables already fully swept by an earlier
 * phase. See {@link ProjectDataDeletionService} for what each phase deletes and why.
 */
enum ProjectDataDeletionPhase {
    EVENTS,
    BATCHES,
    FAILURE_DIAGNOSTICS,
    VERIFICATION,
    IDENTITY,
    TOUCHPOINTS,
    SESSIONS,
    VISITORS,
    DONE;

    ProjectDataDeletionPhase next() {
        return switch (this) {
            case EVENTS -> BATCHES;
            case BATCHES -> FAILURE_DIAGNOSTICS;
            case FAILURE_DIAGNOSTICS -> VERIFICATION;
            case VERIFICATION -> IDENTITY;
            case IDENTITY -> TOUCHPOINTS;
            case TOUCHPOINTS -> SESSIONS;
            case SESSIONS -> VISITORS;
            case VISITORS, DONE -> DONE;
        };
    }
}
