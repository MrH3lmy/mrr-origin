-- #93 post-run tenant-isolation check for the ingestion load test. Run after
-- ingestion-load-test.js completes, against the same database.
--
-- Every table here is workspace/project-scoped by schema (see V2__create_tracking_roots.sql's
-- fk_*_project foreign keys). This script proves that in practice, not just by constraint: each
-- seeded load-test workspace's rows trace only to that workspace's own project, and total counts
-- reconcile with what the workload could have produced.
--
-- Usage: psql "$DATABASE_URL" -f verify-ingestion-tenant-isolation.sql

-- 1. Every visitor/session/touchpoint/event-envelope row belongs to exactly the workspace its
--    project_id says it does (the schema's own fk_*_project already enforces this at the DB level --
--    this is a redundant, explicit application-level proof, not just "trust the constraint").
SELECT 'visitors' AS table_name, count(*) AS mismatched_rows
FROM visitors v JOIN projects p ON p.id = v.project_id
WHERE v.workspace_id <> p.workspace_id AND v.workspace_id IN (
    SELECT id FROM workspaces WHERE slug LIKE 'loadtest-ws-%'
)
UNION ALL
SELECT 'tracking_sessions', count(*)
FROM tracking_sessions s JOIN projects p ON p.id = s.project_id
WHERE s.workspace_id <> p.workspace_id AND s.workspace_id IN (
    SELECT id FROM workspaces WHERE slug LIKE 'loadtest-ws-%'
)
UNION ALL
SELECT 'touchpoints', count(*)
FROM touchpoints t JOIN projects p ON p.id = t.project_id
WHERE t.workspace_id <> p.workspace_id AND t.workspace_id IN (
    SELECT id FROM workspaces WHERE slug LIKE 'loadtest-ws-%'
)
UNION ALL
SELECT 'tracking_event_envelopes', count(*)
FROM tracking_event_envelopes e JOIN projects p ON p.id = e.project_id
WHERE e.workspace_id <> p.workspace_id AND e.workspace_id IN (
    SELECT id FROM workspaces WHERE slug LIKE 'loadtest-ws-%'
);
-- ^ every mismatched_rows value above must be 0.

-- 2. Per-workspace row counts, for eyeballing an even spread across the 10 tenants (the load script
--    round-robins keys evenly) and as the baseline to compare against k6's own accepted-request count
--    in its end-of-run summary.
SELECT
    w.slug,
    (SELECT count(*) FROM visitors WHERE workspace_id = w.id) AS visitors,
    (SELECT count(*) FROM tracking_sessions WHERE workspace_id = w.id) AS sessions,
    (SELECT count(*) FROM tracking_event_envelopes WHERE workspace_id = w.id) AS events
FROM workspaces w
WHERE w.slug LIKE 'loadtest-ws-%'
ORDER BY w.slug;

-- 3. Total accepted event-envelope count across all 10 tenants -- compare this against k6's own
--    mrr_ingestion_accepted_total counter (accepted requests, not accepted events -- one request may
--    carry 1-5 events) times the average events/request to sanity-check nothing was silently dropped
--    or, conversely, duplicated beyond what accepted request replays would explain.
SELECT count(*) AS total_events_across_loadtest_workspaces
FROM tracking_event_envelopes
WHERE workspace_id IN (SELECT id FROM workspaces WHERE slug LIKE 'loadtest-ws-%');
