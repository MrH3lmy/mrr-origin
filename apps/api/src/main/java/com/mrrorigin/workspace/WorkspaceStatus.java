package com.mrrorigin.workspace;

/**
 * Workspace lifecycle status (#62). Every workspace is born ACTIVE and stays that way forever unless
 * a confirmed, owner-only deletion request moves it to DELETING -- a one-way transition no API ever
 * reverses. There is deliberately no DELETED status: the workspace row itself is hard-deleted as the
 * deletion run's final phase, so "the row no longer exists" is what DELETED means.
 */
public enum WorkspaceStatus {
    ACTIVE,
    DELETING
}
