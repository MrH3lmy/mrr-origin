package com.mrrorigin.workspace;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class WorkspaceMemberId implements Serializable {

    private UUID workspaceId;
    private String subjectId;

    public WorkspaceMemberId() {}

    public WorkspaceMemberId(UUID workspaceId, String subjectId) {
        this.workspaceId = workspaceId;
        this.subjectId = subjectId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WorkspaceMemberId that)) {
            return false;
        }
        return Objects.equals(workspaceId, that.workspaceId) && Objects.equals(subjectId, that.subjectId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workspaceId, subjectId);
    }
}
