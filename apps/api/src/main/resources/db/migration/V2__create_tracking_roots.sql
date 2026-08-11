-- Tracking data is tenant-owned at both workspace and project level. The repeated
-- ownership columns allow PostgreSQL to reject cross-tenant relationships.
ALTER TABLE projects
    ADD CONSTRAINT uq_projects_id_workspace UNIQUE (id, workspace_id);

CREATE TABLE project_ingestion_keys (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    key_prefix VARCHAR(24) NOT NULL UNIQUE,
    secret_hash CHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT fk_ingestion_keys_project
        FOREIGN KEY (project_id, workspace_id)
        REFERENCES projects (id, workspace_id) ON DELETE CASCADE,
    CONSTRAINT chk_ingestion_keys_hash CHECK (secret_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_ingestion_keys_revocation
        CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

CREATE UNIQUE INDEX uq_project_ingestion_keys_active_project
    ON project_ingestion_keys (project_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_project_ingestion_keys_workspace_project
    ON project_ingestion_keys (workspace_id, project_id);

CREATE TABLE project_allowed_domains (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    domain VARCHAR(253) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_allowed_domains_project
        FOREIGN KEY (project_id, workspace_id)
        REFERENCES projects (id, workspace_id) ON DELETE CASCADE,
    CONSTRAINT uq_allowed_domains_project_domain UNIQUE (project_id, domain),
    CONSTRAINT chk_allowed_domains_lowercase CHECK (domain = LOWER(domain)),
    CONSTRAINT chk_allowed_domains_no_scheme
        CHECK (domain !~ '[/\\:]' AND domain <> '')
);
CREATE INDEX idx_allowed_domains_workspace_project
    ON project_allowed_domains (workspace_id, project_id);

CREATE TABLE visitors (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    external_visitor_id VARCHAR(160) NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_visitors_project
        FOREIGN KEY (project_id, workspace_id)
        REFERENCES projects (id, workspace_id) ON DELETE CASCADE,
    CONSTRAINT uq_visitors_project_external UNIQUE (project_id, external_visitor_id),
    CONSTRAINT uq_visitors_identity_owner UNIQUE (id, project_id, workspace_id),
    CONSTRAINT chk_visitors_seen_order CHECK (last_seen_at >= first_seen_at)
);
CREATE INDEX idx_visitors_workspace_project ON visitors (workspace_id, project_id);

CREATE TABLE tracking_sessions (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    visitor_id UUID NOT NULL,
    external_session_id VARCHAR(160) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_sessions_visitor
        FOREIGN KEY (visitor_id, project_id, workspace_id)
        REFERENCES visitors (id, project_id, workspace_id) ON DELETE CASCADE,
    CONSTRAINT uq_sessions_project_external UNIQUE (project_id, external_session_id),
    CONSTRAINT uq_sessions_identity_owner
        UNIQUE (id, visitor_id, project_id, workspace_id),
    CONSTRAINT chk_sessions_time_order CHECK (ended_at IS NULL OR ended_at >= started_at)
);
CREATE INDEX idx_sessions_workspace_project_visitor
    ON tracking_sessions (workspace_id, project_id, visitor_id);

CREATE TABLE touchpoints (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    visitor_id UUID NOT NULL,
    session_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    landing_url TEXT NOT NULL,
    referrer_url TEXT,
    utm_source VARCHAR(255),
    utm_medium VARCHAR(255),
    utm_campaign VARCHAR(255),
    utm_term VARCHAR(255),
    utm_content VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_touchpoints_visitor
        FOREIGN KEY (visitor_id, project_id, workspace_id)
        REFERENCES visitors (id, project_id, workspace_id) ON DELETE CASCADE,
    CONSTRAINT fk_touchpoints_session
        FOREIGN KEY (session_id, visitor_id, project_id, workspace_id)
        REFERENCES tracking_sessions (id, visitor_id, project_id, workspace_id) ON DELETE CASCADE,
    CONSTRAINT uq_touchpoints_identity_owner UNIQUE (id, project_id, workspace_id)
);
CREATE INDEX idx_touchpoints_workspace_project_occurred
    ON touchpoints (workspace_id, project_id, occurred_at);

CREATE TABLE tracking_event_envelopes (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    visitor_id UUID NOT NULL,
    session_id UUID,
    external_event_id VARCHAR(160) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    payload JSONB NOT NULL,
    CONSTRAINT fk_event_envelopes_visitor
        FOREIGN KEY (visitor_id, project_id, workspace_id)
        REFERENCES visitors (id, project_id, workspace_id) ON DELETE RESTRICT,
    CONSTRAINT fk_event_envelopes_session
        FOREIGN KEY (session_id, visitor_id, project_id, workspace_id)
        REFERENCES tracking_sessions (id, visitor_id, project_id, workspace_id) ON DELETE RESTRICT,
    CONSTRAINT uq_event_envelopes_project_external
        UNIQUE (project_id, external_event_id),
    CONSTRAINT chk_event_envelopes_payload_object
        CHECK (jsonb_typeof(payload) = 'object')
);
CREATE INDEX idx_event_envelopes_workspace_project_received
    ON tracking_event_envelopes (workspace_id, project_id, received_at);
