package com.mrrorigin.workspace;

public enum WorkspaceRole {
    OWNER,
    ADMIN,
    MEMBER,
    VIEWER;

    boolean canManage() {
        return this == OWNER || this == ADMIN;
    }
}
