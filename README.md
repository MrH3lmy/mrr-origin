# MRROrigin

**Know where your MRR came from — and whether it stayed.**

MRROrigin connects a SaaS customer's acquisition journey to their subscription lifecycle. It combines first-party traffic attribution with Stripe billing events so founders can compare new, retained, expanded, contracted, churned, and reactivated MRR by source, campaign, and landing page.

## Current status

Phase 1 establishes the product contract, architecture, repository layout, core tenancy schema, build tooling, and CI. Product tracking, Stripe synchronization, attribution calculations, and dashboard features are intentionally tracked as later backlog work.

## Repository layout

```text
apps/
  api/       Spring Boot modular monolith
  web/       Next.js dashboard
  tracker/   Browser tracking SDK
docs/
  adr/       Architecture decision records
```

## Technology baseline

- Java 21 and Spring Boot 4.1
- Next.js 16 Active LTS, React 19, and TypeScript
- PostgreSQL 17 with Flyway migrations
- pnpm workspaces
- Testcontainers for backend integration tests
- Vitest for TypeScript unit tests

## Local development

Prerequisites: Java 21, Maven 3.9+, Node.js 24+, pnpm 11.20+, Docker, and Docker Compose.

```bash
docker compose up -d postgres

cd apps/api
mvn spring-boot:run

cd ../..
pnpm install
pnpm dev
```

Run the validation suite:

```bash
cd apps/api && mvn verify
cd ../.. && pnpm check
```

## Product and engineering references

- [Product contract](PRODUCT.md)
- [Architecture](ARCHITECTURE.md)
- [Roadmap](ROADMAP.md)
- [Contributing guide](CONTRIBUTING.md)
- [Security policy](SECURITY.md)

GitHub issues are the executable backlog. Every implementation PR should reference exactly one primary issue and update documentation when it changes a product or architecture decision.

## Claude Code plugins

This repo registers the [UI UX Pro Max](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill) skill via `.claude/settings.json`. Team members who trust this folder in Claude Code will be prompted to install the `ui-ux-pro-max-skill` marketplace and the `ui-ux-pro-max` plugin (design intelligence: UI styles, color palettes, font pairings, chart types, and UX guidelines).
