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
└── scripts/    # local dev / automation scripts
```

## Status

Milestone 0 (Foundation) is in progress. See `docs/08-backlog.md` for the current backlog item statuses. `FZ-001`–`FZ-004` are done.

## Commands

See `CLAUDE.md` §8 for backend, frontend, and local Postgres commands.
