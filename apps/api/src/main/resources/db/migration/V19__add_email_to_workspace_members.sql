-- Per #59: weekly summary delivery needs a real email address per recipient, and none exists on
-- workspace_members today (subject_id is only an OIDC subject, not necessarily an address). Nullable
-- and best-effort: WorkspaceContext captures it lazily from the caller's own JWT `email` claim on
-- their own next authenticated request (see WorkspaceContext#captureEmailIfPresent), never invented
-- and never required at membership-creation time (addMember creates a row for a different subject
-- than the caller, who has no reliable way to know that subject's address). A member with no
-- captured email yet is simply excluded from weekly-summary recipient resolution -- an operational
-- gap, never a failed delivery attempt.
ALTER TABLE workspace_members ADD COLUMN email VARCHAR(320);
