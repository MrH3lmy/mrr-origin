-- Live installation-verification attempts (#8): a workspace-authorized, project-scoped challenge
-- that proves a specific event received through the public ingestion path belongs to this exact
-- attempt, rather than merely proving *some* traffic arrived. Verification never bypasses or
-- duplicates the public ingestion contract's own key/origin/payload checks (V2/V4) -- the tracker
-- sends the token as an ordinary "custom" event through /api/public/v1/events using the existing
-- generic track() API, so a verification success is only ever as trustworthy as an accepted event
-- already is, and can never weaken that security model.
--
-- EXPIRED is computed at read time from expires_at rather than stored as a status, so expiry and
-- replay are judged deterministically off wall-clock time without needing a sweeper job: a matching
-- event that arrives after expires_at can never flip a PENDING row to SUCCEEDED
-- (TrackingVerificationService only matches status = 'PENDING' AND expires_at >= now), and an
-- already-SUCCEEDED row can never be re-matched by a replayed event.
CREATE TABLE tracking_verification_attempts (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    token CHAR(43) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    succeeded_at TIMESTAMPTZ,
    received_external_event_id VARCHAR(160),
    CONSTRAINT fk_verification_attempts_project
        FOREIGN KEY (project_id, workspace_id)
        REFERENCES projects (id, workspace_id) ON DELETE CASCADE,
    CONSTRAINT chk_verification_attempts_status CHECK (status IN ('PENDING', 'SUCCEEDED')),
    CONSTRAINT chk_verification_attempts_succeeded CHECK (
        (status = 'SUCCEEDED') = (succeeded_at IS NOT NULL AND received_external_event_id IS NOT NULL)
    ),
    CONSTRAINT chk_verification_attempts_expiry CHECK (expires_at > created_at)
);

-- Supports both "does this project already have a live PENDING attempt to reuse" (TrackingVerificationService.start)
-- and "what is the most recent attempt for this project" (status lookups), most-recent first.
CREATE INDEX idx_verification_attempts_project_created
    ON tracking_verification_attempts (workspace_id, project_id, created_at DESC);
