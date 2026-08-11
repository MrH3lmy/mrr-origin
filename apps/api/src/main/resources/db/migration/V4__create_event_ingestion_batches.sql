CREATE TABLE tracking_ingestion_batches (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    external_batch_id VARCHAR(160) NOT NULL,
    envelope_version SMALLINT NOT NULL,
    request_hash CHAR(64) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ingestion_batches_project
        FOREIGN KEY (project_id, workspace_id)
        REFERENCES projects (id, workspace_id) ON DELETE RESTRICT,
    CONSTRAINT uq_ingestion_batches_project_external
        UNIQUE (project_id, external_batch_id),
    CONSTRAINT uq_ingestion_batches_identity_owner
        UNIQUE (id, project_id, workspace_id),
    CONSTRAINT chk_ingestion_batches_version CHECK (envelope_version = 1),
    CONSTRAINT chk_ingestion_batches_hash CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

ALTER TABLE tracking_event_envelopes
    ADD COLUMN ingestion_batch_id UUID;

ALTER TABLE tracking_event_envelopes
    ADD CONSTRAINT fk_event_envelopes_ingestion_batch
        FOREIGN KEY (ingestion_batch_id, project_id, workspace_id)
        REFERENCES tracking_ingestion_batches (id, project_id, workspace_id) ON DELETE RESTRICT;

CREATE INDEX idx_ingestion_batches_workspace_project_received
    ON tracking_ingestion_batches (workspace_id, project_id, received_at);
CREATE INDEX idx_event_envelopes_ingestion_batch
    ON tracking_event_envelopes (ingestion_batch_id);
