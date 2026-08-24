-- #93 post-run tenant-isolation/routing check and reconciliation for the ingestion load test. Run
-- after ingestion-load-test.js completes, against the same database.
--
-- Every table here is workspace/project-scoped by schema (see V2__create_tracking_roots.sql's
-- fk_*_project foreign keys) -- but that alone only proves each row is *internally consistent*, not
-- that it was actually *routed* correctly: a bug that consistently routes key/origin A's requests into
-- project/workspace B would still produce rows that are perfectly FK-consistent with B, and would pass
-- an FK-only check. To prove routing itself, ingestion-load-test.js tags every client-generated
-- identifier (visitorId/sessionId/eventId, stored verbatim as external_visitor_id/external_session_id/
-- external_event_id) with `_w<N>_`, the index of the ingestion key it was actually sent under. This
-- script decodes that tag from each stored external id and hard-asserts it matches the workspace the
-- row actually landed in.
--
-- This script FAILS THE RUN (non-zero exit, via RAISE EXCEPTION under psql's -v ON_ERROR_STOP=1) on any
-- violation, rather than printing a number for a human to eyeball.
--
-- Usage:
--   psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -v baseline_events=<n> -v expected_events=<n> \
--       -f verify-ingestion-tenant-isolation.sql
--
--   baseline_events must be the `tracking_event_envelopes` row count for load-test workspaces,
--   captured immediately after seeding and *before* the k6 run -- so a rerun against an
--   already-seeded, previously-exercised database reconciles only this run's delta, not every prior
--   run's cumulative total. expected_events must be the exact value of this run's k6
--   mrr_ingestion_accepted_events_total counter. run-ingestion-load-test.sh captures and passes both
--   automatically.

\if :{?baseline_events}
\else
\warn 'baseline_events psql variable not set -- run via run-ingestion-load-test.sh, or pass -v baseline_events=<n> -v expected_events=<n> manually. Aborting.'
\endif
\if :{?expected_events}
\else
\warn 'expected_events psql variable not set -- run via run-ingestion-load-test.sh, or pass -v baseline_events=<n> -v expected_events=<n> manually. Aborting.'
\endif

-- Review fix: psql's :name variable interpolation is not performed inside a dollar-quoted ($$...$$)
-- PL/pgSQL body, so `expected_events BIGINT := :expected_events;` written directly inside a DO block
-- would send the literal, unparseable text ":expected_events" to the server. A plain top-level INSERT
-- is not dollar-quoted, so interpolation here is unambiguous -- and if either variable above was left
-- unset (substituted as an empty string by psql), this INSERT itself fails with a clear bigint-syntax
-- error under -v ON_ERROR_STOP=1, which is this script's own cheap proof that the -v values actually
-- reached the server: every check below only runs at all once this line has succeeded.
CREATE TEMP TABLE _load_test_reconciliation_params (baseline_events BIGINT, expected_events BIGINT);
INSERT INTO _load_test_reconciliation_params (baseline_events, expected_events)
VALUES (:baseline_events, :expected_events);
DO $$
BEGIN
    RAISE NOTICE 'reconciliation parameters received: baseline_events=%, expected_events=%',
        (SELECT baseline_events FROM _load_test_reconciliation_params),
        (SELECT expected_events FROM _load_test_reconciliation_params);
END $$;

-- 1. Tenant-routing proof: decode the `_w<N>_` tag embedded in every load-test-generated external id
--    and assert it matches the numeric suffix of the workspace the row actually landed in. Catches a
--    "consistently misrouted" bug that FK-consistency alone cannot (see header comment).
DO $$
DECLARE
    mismatched RECORD;
    total_mismatches BIGINT := 0;
BEGIN
    FOR mismatched IN
        SELECT 'visitors' AS table_name, count(*) AS mismatched_rows
        FROM visitors v
        JOIN workspaces w ON w.id = v.workspace_id
        WHERE w.slug LIKE 'loadtest-ws-%'
          AND substring(v.external_visitor_id FROM '_w([0-9]+)_')
              IS DISTINCT FROM substring(w.slug FROM 'loadtest-ws-([0-9]+)$')
        UNION ALL
        SELECT 'tracking_sessions', count(*)
        FROM tracking_sessions s
        JOIN workspaces w ON w.id = s.workspace_id
        WHERE w.slug LIKE 'loadtest-ws-%'
          AND substring(s.external_session_id FROM '_w([0-9]+)_')
              IS DISTINCT FROM substring(w.slug FROM 'loadtest-ws-([0-9]+)$')
        UNION ALL
        SELECT 'tracking_event_envelopes', count(*)
        FROM tracking_event_envelopes e
        JOIN workspaces w ON w.id = e.workspace_id
        WHERE w.slug LIKE 'loadtest-ws-%'
          AND substring(e.external_event_id FROM '_w([0-9]+)_')
              IS DISTINCT FROM substring(w.slug FROM 'loadtest-ws-([0-9]+)$')
    LOOP
        IF mismatched.mismatched_rows > 0 THEN
            RAISE WARNING 'tenant routing violation: % row(s) in % carry a tenant tag that does not match the workspace they are stored under',
                mismatched.mismatched_rows, mismatched.table_name;
            total_mismatches := total_mismatches + mismatched.mismatched_rows;
        END IF;
    END LOOP;

    IF total_mismatches > 0 THEN
        RAISE EXCEPTION 'tenant routing check FAILED: % row(s) whose embedded tenant tag does not match their stored workspace', total_mismatches;
    END IF;

    RAISE NOTICE 'tenant routing check passed: every tagged external id matches the workspace it is stored under';
END $$;

-- 2. Explicit application-level proof of the schema's own fk_*_project relationship (redundant with
--    the constraint, but explicit) -- catches a different bug class: a row's workspace_id disagreeing
--    with its own project_id, independent of which tenant actually sent the request.
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
            RAISE WARNING 'workspace/project mismatch: % row(s) in % belong to a workspace other than their project''s workspace',
                mismatched.mismatched_rows, mismatched.table_name;
            total_mismatches := total_mismatches + mismatched.mismatched_rows;
        END IF;
    END LOOP;

    IF total_mismatches > 0 THEN
        RAISE EXCEPTION 'workspace/project consistency check FAILED: % cross-workspace row(s) found across load-test workspaces', total_mismatches;
    END IF;

    RAISE NOTICE 'workspace/project consistency check passed: 0 cross-workspace rows across load-test workspaces';
END $$;

-- 3. Per-workspace row counts, for eyeballing an even spread across the 10 tenants (the load script
--    round-robins keys evenly).
SELECT
    w.slug,
    (SELECT count(*) FROM visitors WHERE workspace_id = w.id) AS visitors,
    (SELECT count(*) FROM tracking_sessions WHERE workspace_id = w.id) AS sessions,
    (SELECT count(*) FROM tracking_event_envelopes WHERE workspace_id = w.id) AS events
FROM workspaces w
WHERE w.slug LIKE 'loadtest-ws-%'
ORDER BY w.slug;

-- 4. Deterministic delta reconciliation: this run's own contribution (current total minus the
--    pre-run baseline) must equal :expected_events EXACTLY -- the exact number of events k6 sent in
--    accepted requests this run (mrr_ingestion_accepted_events_total), not an "accepted requests x
--    average events/request" estimate, and not the database's cumulative total across every prior run
--    (which would make the harness fail on any second run against the same database). Any drift
--    (dropped or duplicated events) fails the run.
DO $$
DECLARE
    params RECORD;
    actual_events BIGINT;
    delta BIGINT;
BEGIN
    SELECT baseline_events, expected_events INTO params FROM _load_test_reconciliation_params;

    SELECT count(*) INTO actual_events
    FROM tracking_event_envelopes
    WHERE workspace_id IN (SELECT id FROM workspaces WHERE slug LIKE 'loadtest-ws-%');

    delta := actual_events - params.baseline_events;

    RAISE NOTICE 'event reconciliation: baseline=%, current=%, this run''s delta=%, k6 expected=%',
        params.baseline_events, actual_events, delta, params.expected_events;

    IF delta <> params.expected_events THEN
        RAISE EXCEPTION 'event reconciliation FAILED: this run persisted % events (current % - baseline %) but k6 reported % accepted',
            delta, actual_events, params.baseline_events, params.expected_events;
    END IF;

    RAISE NOTICE 'event reconciliation passed: this run persisted exactly % events, matching k6''s accepted-event count exactly', delta;
END $$;

DROP TABLE _load_test_reconciliation_params;
