-- #93 public-ingestion k6 load test fixtures: 10 deterministic workspaces/projects, one active
-- ingestion key and one allowed domain each, matching the issue's "10 independent workspaces/
-- projects... one active ingestion key per project" reference workload.
--
-- Idempotent (ON CONFLICT DO NOTHING throughout) -- safe to re-run against an already-seeded database.
--
-- Ingestion keys are SHA-256(rawKey)-hashed at rest with no plaintext recovery path
-- (IngestionKeyService#issue is the only production writer). This script and ingestion-load-test.js
-- independently compute/hardcode the identical deterministic raw key per workspace index, so no
-- round-trip through the application is needed -- Postgres's built-in sha256() (core since PG11, no
-- extension) computes the same hash IngestionKeyService#hash does.
--
-- Usage: psql "$DATABASE_URL" -f seed-ingestion-fixtures.sql
-- (or docker compose exec -T postgres psql -U mrr_origin -d mrr_origin -f - < seed-ingestion-fixtures.sql)

DO $$
DECLARE
    i INT;
    ws_id UUID;
    proj_id UUID;
    raw_key TEXT;
BEGIN
    FOR i IN 0..9 LOOP
        ws_id := ('a0000000-0000-4000-8000-00000000000' || i)::uuid;
        proj_id := ('b0000000-0000-4000-8000-00000000000' || i)::uuid;
        -- Must exactly match LOAD_KEYS[i] in ingestion-load-test.js.
        raw_key := 'mrr_loadtest0' || i || '_deterministicloadtestsecretvalueusedonlyforlocalk6loadtesting';

        INSERT INTO workspaces (id, name, slug, reporting_currency)
        VALUES (ws_id, 'Load Test Workspace ' || i, 'loadtest-ws-' || i, 'USD')
        ON CONFLICT (id) DO NOTHING;

        INSERT INTO projects (id, workspace_id, name, domain, public_key)
        VALUES (proj_id, ws_id, 'Load Test Project ' || i, 'loadtest' || i || '.example.com', 'pk_loadtest_' || i)
        ON CONFLICT (id) DO NOTHING;

        INSERT INTO project_allowed_domains (id, workspace_id, project_id, domain)
        VALUES (gen_random_uuid(), ws_id, proj_id, 'loadtest' || i || '.example.com')
        ON CONFLICT (project_id, domain) DO NOTHING;

        -- Mirrors IngestionKeyService#insert exactly (key_prefix/secret_hash columns, active = no
        -- revoked_at), except the prefix/secret are deterministic instead of SecureRandom-generated.
        INSERT INTO project_ingestion_keys (id, workspace_id, project_id, key_prefix, secret_hash)
        VALUES (
            gen_random_uuid(), ws_id, proj_id, 'mrr_loadtest0' || i,
            encode(sha256(raw_key::bytea), 'hex')
        )
        ON CONFLICT (key_prefix) DO NOTHING;
    END LOOP;
END $$;

-- Sanity check: exactly 10 workspaces, 10 projects, 10 active keys, 10 allowed domains.
SELECT
    (SELECT count(*) FROM workspaces WHERE slug LIKE 'loadtest-ws-%') AS workspaces,
    (SELECT count(*) FROM projects WHERE name LIKE 'Load Test Project %') AS projects,
    (SELECT count(*) FROM project_ingestion_keys WHERE key_prefix LIKE 'mrr_loadtest0%' AND revoked_at IS NULL) AS active_keys,
    (SELECT count(*) FROM project_allowed_domains WHERE domain LIKE 'loadtest%.example.com') AS allowed_domains;
