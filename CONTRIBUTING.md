# Contributing

## Start here

Read these files before changing code:

1. `PRODUCT.md`
2. `ARCHITECTURE.md`
3. `ROADMAP.md`
4. `AGENTS.md` when using an AI coding agent

Every change needs one primary GitHub issue. If the issue changes product scope or architecture, update the relevant document or add an ADR in the same PR.

## Branch and pull-request workflow

- Never commit directly to `main`.
- Use `feature/<issue>-short-description`, `fix/<issue>-short-description`, or an agent-specific equivalent such as `claude/<issue>-short-description`.
- Keep one primary issue per PR.
- Open a draft PR early for work that may overlap another contributor.
- State dependencies and blocked issues in the PR description.
- Prefer small vertical slices over large layer-only changes.

## Local commands

```bash
# Infrastructure
docker compose up -d postgres
docker compose down

# Backend
cd apps/api
mvn verify
mvn spring-boot:run

# JavaScript workspace
cd ../..
pnpm install
pnpm check
pnpm dev
```

## Definition of done

- Acceptance criteria from the issue are met.
- Tests cover success, duplicate/retry, authorization, and tenant-isolation behavior where applicable.
- No secret, raw payment detail, or unnecessary personal data appears in logs or fixtures.
- Database changes are delivered as a new migration.
- API or SDK behavior changes include documentation.
- `mvn verify` and `pnpm check` pass.
- The PR explains any remaining risk or deliberately deferred work.

## Database migrations

- Never edit a migration already merged into `main`.
- Use `V<sequence>__<description>.sql` under `apps/api/src/main/resources/db/migration`.
- Include tenant ownership and idempotency constraints in the initial schema design, not as a later cleanup.
- Store monetary values as integer minor units with an explicit currency.
- Use UTC timestamps at rest.

## AI-assisted contributions

Codex, Claude, and human contributors share the same rules:

- Claim an issue before starting and comment with the intended file/module scope.
- Do not work on an issue already claimed unless the current owner coordinates the split.
- Read the latest remote branch and open PRs before editing shared files.
- Do not introduce a new framework, infrastructure service, or cross-module dependency without an ADR.
- Treat generated output as untrusted until tests and a human-readable diff have been reviewed.
- Do not include prompts, credentials, local machine paths, or private customer data in commits.
