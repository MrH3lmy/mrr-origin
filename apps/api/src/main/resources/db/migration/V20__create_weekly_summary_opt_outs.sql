-- Per #59 (docs/weekly-summary-delivery-plan.md §3): per-(project, member) weekly-summary opt-out,
-- modeled on project_tracking_retention_settings (V15) -- absent row means the default (subscribed,
-- if otherwise eligible per §2a's role gate). A row here means this member does not want the weekly
-- summary for this specific project; granularity is per-project, not per-workspace, because delivery
-- itself is per-project (§1c) and a member on multiple projects may want to silence only one.
CREATE TABLE weekly_summary_opt_outs (
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    subject_id VARCHAR(255) NOT NULL,
    opted_out_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (project_id, subject_id),
    CONSTRAINT fk_weekly_summary_opt_out_project
        FOREIGN KEY (project_id, workspace_id)
        REFERENCES projects (id, workspace_id) ON DELETE CASCADE
);

CREATE INDEX idx_weekly_summary_opt_out_workspace ON weekly_summary_opt_outs (workspace_id);
