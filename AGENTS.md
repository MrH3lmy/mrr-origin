# Agent instructions

These instructions apply to the entire repository.

## Required context

Before editing, read `PRODUCT.md`, `ARCHITECTURE.md`, `ROADMAP.md`, and `CONTRIBUTING.md`. GitHub issues are the executable backlog and override assumptions that are not written in those documents.

This is the canonical required-reading list for the repository; other docs (e.g. `CLAUDE.md`) should reference it rather than repeat it.

## Coordination

- Work from one primary issue and use an isolated branch.
- Claim the issue with a short comment naming the intended modules/files before implementation.
- Check open PRs for overlap. Do not silently modify files owned by another in-progress issue.
- Keep draft PRs current so Claude, Codex, and human contributors can coordinate asynchronously.
- If requirements are ambiguous, record the ambiguity on the issue instead of inventing product behavior.

## Architecture rules

- Preserve the Spring Boot modular monolith until an accepted ADR says otherwise.
- Keep tenant-owned operations scoped by `workspace_id` and, when applicable, `project_id`.
- Keep raw external inputs immutable and derived MRR/attribution results recalculable.
- Make ingress, webhooks, jobs, and backfills idempotent.
- Do not add Kafka, Redis, microservices, a warehouse, or another billing provider before private beta without explicit approval.
- Do not use probabilistic fingerprinting for identity resolution.

## Quality gates

- Add or update tests with behavior changes.
- Include duplicate, retry, failure, and cross-tenant cases for boundary code.
- Never edit a merged Flyway migration.
- Never log credentials, webhook bodies containing sensitive data, raw emails, or payment details.
- Run the smallest relevant checks while working and the complete package check before handoff.

## Commands

```bash
cd apps/api && mvn verify
cd ../.. && pnpm check
```
