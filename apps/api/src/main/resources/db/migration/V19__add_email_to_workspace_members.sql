-- Per #59: weekly summary delivery needs a real email address per recipient, and none exists on
-- workspace_members today (subject_id is only an OIDC subject, not necessarily an address). Nullable:
-- WorkspaceContext captures/refreshes it lazily from the caller's own verified JWT `email` claim
-- (email_verified=true only) on their own next authenticated request (see
-- WorkspaceContext#captureEmailIfPresent), never invented and never required at membership-creation
-- time (addMember creates a row for a different subject than the caller, who has no reliable way to
-- know that subject's address). Accepted B3 correction: a member with no verified email yet is NOT
-- excluded from weekly-summary recipient resolution -- it is recorded as an auditable
-- BLOCKED_MISSING_EMAIL delivery (see V21), manually replayable once a verified email exists.
ALTER TABLE workspace_members ADD COLUMN email VARCHAR(320);
