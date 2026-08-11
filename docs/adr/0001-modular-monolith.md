# ADR-0001: Start with a modular monolith

- Status: Accepted
- Date: 2026-08-11

## Context

MRROrigin requires strong consistency across identity, Stripe state, MRR movements, and attribution while the product and team are small. Independent services would add network failure modes, duplicated contracts, deployment overhead, and distributed transactions before module ownership or scale requires them.

## Decision

Build one Spring Boot deployable organized into explicit business modules. Modules communicate through application services and internal events and own their persistence details. The tracker and Next.js dashboard remain separate clients.

## Consequences

- Cross-domain transactions and recalculation are simpler.
- Local development and deployment need fewer moving parts.
- Module boundaries must be enforced in code review and architecture tests.
- A module may be extracted later only after profiling or team ownership provides evidence for the split.
