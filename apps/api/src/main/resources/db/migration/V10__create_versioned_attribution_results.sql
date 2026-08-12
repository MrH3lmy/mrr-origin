ALTER TABLE customer_mrr_movements
    ADD CONSTRAINT uq_customer_mrr_movements_owner UNIQUE (workspace_id, id);

CREATE TABLE customer_attribution_results (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    movement_id UUID NOT NULL,
    acquisition_movement_id UUID NOT NULL,
    model_version VARCHAR(64) NOT NULL,
    first_touchpoint_id UUID,
    last_touchpoint_id UUID,
    customer_link_evidence_id UUID,
    first_source VARCHAR(255),
    first_campaign VARCHAR(255),
    first_landing_page TEXT,
    last_source VARCHAR(255),
    last_campaign VARCHAR(255),
    last_landing_page TEXT,
    confidence VARCHAR(16) NOT NULL,
    unattributed_reason VARCHAR(64),
    source_references TEXT[] NOT NULL,
    calculated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_customer_attribution_result UNIQUE (workspace_id, project_id, movement_id, model_version),
    CONSTRAINT fk_attribution_project FOREIGN KEY (project_id, workspace_id)
        REFERENCES projects (id, workspace_id) ON DELETE CASCADE,
    CONSTRAINT fk_attribution_movement FOREIGN KEY (workspace_id, movement_id)
        REFERENCES customer_mrr_movements (workspace_id, id) ON DELETE CASCADE,
    -- Revenue movements are derived and V9 replay replaces them. Attribution is also derived,
    -- so stale results must cascade instead of blocking the upstream revenue replay.
    CONSTRAINT fk_attribution_acquisition FOREIGN KEY (workspace_id, acquisition_movement_id)
        REFERENCES customer_mrr_movements (workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_attribution_first_touch FOREIGN KEY (first_touchpoint_id, project_id, workspace_id)
        REFERENCES touchpoints (id, project_id, workspace_id) ON DELETE RESTRICT,
    CONSTRAINT fk_attribution_last_touch FOREIGN KEY (last_touchpoint_id, project_id, workspace_id)
        REFERENCES touchpoints (id, project_id, workspace_id) ON DELETE RESTRICT,
    CONSTRAINT fk_attribution_customer_link FOREIGN KEY (customer_link_evidence_id, project_id, workspace_id)
        REFERENCES stripe_customer_links (id, project_id, workspace_id) ON DELETE RESTRICT,
    CONSTRAINT chk_attribution_confidence CHECK (confidence IN ('STRONG', 'UNATTRIBUTED')),
    CONSTRAINT chk_attribution_evidence CHECK (
        (confidence = 'STRONG' AND unattributed_reason IS NULL AND first_touchpoint_id IS NOT NULL
            AND last_touchpoint_id IS NOT NULL AND customer_link_evidence_id IS NOT NULL)
        OR
        (confidence = 'UNATTRIBUTED'
            AND first_touchpoint_id IS NULL AND last_touchpoint_id IS NULL
            AND (
                (unattributed_reason = 'NO_ACTIVE_LINK' AND customer_link_evidence_id IS NULL)
                OR
                (unattributed_reason = 'NO_ELIGIBLE_TOUCHPOINT' AND customer_link_evidence_id IS NOT NULL)
            ))
    )
);

CREATE INDEX idx_customer_attribution_explanation
    ON customer_attribution_results (workspace_id, project_id, acquisition_movement_id, model_version);
