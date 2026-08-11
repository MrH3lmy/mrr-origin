CREATE TABLE external_identities (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    external_user_id VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_external_identities_project
        FOREIGN KEY (project_id, workspace_id)
        REFERENCES projects (id, workspace_id) ON DELETE RESTRICT,
    CONSTRAINT uq_external_identities_project_user
        UNIQUE (project_id, external_user_id),
    CONSTRAINT uq_external_identities_owner
        UNIQUE (id, project_id, workspace_id)
);

CREATE TABLE visitor_aliases (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    visitor_id UUID NOT NULL,
    external_identity_id UUID NOT NULL,
    identified_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_visitor_aliases_project
        FOREIGN KEY (project_id, workspace_id)
        REFERENCES projects (id, workspace_id) ON DELETE RESTRICT,
    CONSTRAINT fk_visitor_aliases_visitor
        FOREIGN KEY (visitor_id, project_id, workspace_id)
        REFERENCES visitors (id, project_id, workspace_id) ON DELETE RESTRICT,
    CONSTRAINT fk_visitor_aliases_identity
        FOREIGN KEY (external_identity_id, project_id, workspace_id)
        REFERENCES external_identities (id, project_id, workspace_id) ON DELETE RESTRICT,
    CONSTRAINT uq_visitor_aliases_project_visitor
        UNIQUE (project_id, visitor_id),
    CONSTRAINT uq_visitor_aliases_owner
        UNIQUE (id, project_id, workspace_id)
);

CREATE INDEX idx_external_identities_workspace_project
    ON external_identities (workspace_id, project_id);
CREATE INDEX idx_visitor_aliases_workspace_project_identity
    ON visitor_aliases (workspace_id, project_id, external_identity_id);
