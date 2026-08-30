# FreezeHub

FreezeHub is a multi-tenant SaaS that centralizes deployment/code freeze management, communicates restrictions to engineering teams, and exposes a policy API that CI/CD systems can query before deploying.

The MVP is intentionally narrow: create, communicate, inspect, and evaluate deployment freezes.

## Start here

Read, in order:

1. [`CLAUDE.md`](./CLAUDE.md) — source of truth and workflow for AI-assisted development on this repo.
2. [`docs/00-product.md`](./docs/00-product.md) — product definition, actors, MVP capabilities.
3. [`docs/01-domain.md`](./docs/01-domain.md) — domain model and invariants.
4. [`docs/02-architecture.md`](./docs/02-architecture.md) — architecture and technology choices.
5. [`docs/08-backlog.md`](./docs/08-backlog.md) — implementation backlog and execution order.

## Repository layout

```text
freezehub/
├── CLAUDE.md
├── README.md
├── docker-compose.yml   # local PostgreSQL
├── docs/       # product, domain, architecture, and backlog specs
├── backend/    # Java + Spring Boot modular monolith
├── frontend/   # React + TypeScript
├── infra/      # Terraform (AWS)
├── scripts/    # local dev / automation scripts
└── examples/   # CI/CD integration example for the Policy API
```

## Status

Milestones 0–3 and 5 are complete: foundation, tenancy and catalog, restrictions and their lifecycle, the frontend slice, and the Policy API with machine authentication.

Milestone 4 (notifications) delivers Slack, email and webhook announcements with bounded retry, but keeps two open items: `FZ-046` (a real Cognito identity provider, which also blocks running outside the `local` profile) and `FZ-047` (the "starting soon" notification, blocked on a lead-time decision). Milestone 6 — audit and beta readiness — is next.

See [`docs/08-backlog.md`](./docs/08-backlog.md) for per-item status and [`docs/09-open-issues.md`](./docs/09-open-issues.md) for known defects and deferred decisions.

## Commands

See `CLAUDE.md` §8 for backend, frontend, and local Postgres commands.
