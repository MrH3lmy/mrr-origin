-- #93 post-run tenant-isolation check for the ingestion load test. Run after
-- ingestion-load-test.js completes, against the same database.
--
-- Every table here is workspace/project-scoped by schema (see V2__create_tracking_roots.sql's
-- fk_*_project foreign keys). This script proves that in practice, not just by constraint: each
-- seeded load-test workspace's rows trace only to that workspace's own project, and total counts
-- reconcile exactly with what the workload actually sent -- and it FAILS THE RUN (non-zero exit,
-- via RAISE EXCEPTION under psql's -v ON_ERROR_STOP=1) on any violation, rather than printing a
-- number for a human to eyeball.
--
-- Usage: psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -v expected_events=<n> -f verify-ingestion-tenant-isolation.sql
--   expected_events must be the exact value of the k6 run's mrr_ingestion_accepted_events_total
--   counter (run-ingestion-load-test.sh extracts and passes this automatically).

-- 1. Every visitor/session/touchpoint/event-envelope row belongs to exactly the workspace its
--    project_id says it does (the schema's own fk_*_project already enforces this at the DB level --
--    this is a redundant, explicit application-level proof, not just "trust the constraint"). Any
--    mismatch aborts the run instead of being left for a human to notice in a printed table.
DO $$
DECLARE
    mismatched RECORD;
    total_mismatches BIGINT := 0;
BEGIN
    FOR mismatched IN
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
        )
    LOOP
        IF mismatched.mismatched_rows > 0 THEN
            RAISE WARNING 'tenant isolation violation: % row(s) in % belong to a workspace other than their project''s workspace',
                mismatched.mismatched_rows, mismatched.table_name;
            total_mismatches := total_mismatches + mismatched.mismatched_rows;
        END IF;
    END LOOP;

    IF total_mismatches > 0 THEN
        RAISE EXCEPTION 'tenant isolation check FAILED: % cross-workspace row(s) found across load-test workspaces', total_mismatches;
    END IF;

    RAISE NOTICE 'tenant isolation check passed: 0 cross-workspace rows across load-test workspaces';
END $$;

-- 2. Per-workspace row counts, for eyeballing an even spread across the 10 tenants (the load script
--    round-robins keys evenly).
SELECT
    w.slug,
    (SELECT count(*) FROM visitors WHERE workspace_id = w.id) AS visitors,
    (SELECT count(*) FROM tracking_sessions WHERE workspace_id = w.id) AS sessions,
    (SELECT count(*) FROM tracking_event_envelopes WHERE workspace_id = w.id) AS events
FROM workspaces w
WHERE w.slug LIKE 'loadtest-ws-%'
ORDER BY w.slug;

-- 3. Deterministic reconciliation: the total persisted event-envelope count across all 10 tenants
--    must equal :expected_events EXACTLY -- the exact number of events k6 sent in accepted requests
--    (mrr_ingestion_accepted_events_total), not an "accepted requests x average events/request"
--    estimate. Any drift (dropped or duplicated events) fails the run.
\if :{?expected_events}
DO $$
DECLARE
    actual_events BIGINT;
    expected_events BIGINT := :expected_events;
BEGIN
    SELECT count(*) INTO actual_events
    FROM tracking_event_envelopes
    WHERE workspace_id IN (SELECT id FROM workspaces WHERE slug LIKE 'loadtest-ws-%');

    IF actual_events <> expected_events THEN
        RAISE EXCEPTION 'event reconciliation FAILED: expected exactly % persisted events (k6 mrr_ingestion_accepted_events_total) but found %',
            expected_events, actual_events;
    END IF;

    RAISE NOTICE 'event reconciliation passed: % persisted events match k6''s accepted-event count exactly', actual_events;
END $$;
\else
\warn 'expected_events psql variable not set -- skipping deterministic event-count reconciliation (pass -v expected_events=<n>, e.g. via run-ingestion-load-test.sh)'
\endif
