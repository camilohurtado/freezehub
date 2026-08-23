# FreezeHub — Claude Code Instructions

## 1. Product

FreezeHub is a multi-tenant SaaS that centralizes deployment/code freeze management, communicates restrictions to engineering teams, and exposes a policy API that CI/CD systems can query before deploying.

The MVP is intentionally narrow: create, communicate, inspect, and evaluate deployment freezes.

## 2. Source of Truth

Before implementing any feature:

1. Read this file.
2. Read `docs/00-product.md`.
3. Read `docs/01-domain.md`.
4. Read `docs/02-architecture.md`.
5. Read the requested item in `docs/08-backlog.md`.
6. Inspect the existing implementation before changing it.

Do not invent business rules.

If documentation, tests, API contracts, migrations, and implementation conflict, report the conflict before changing domain behavior.

## 3. Architecture

- Monorepo.
- Modular monolith.
- Backend: Java + Spring Boot.
- Frontend: React + TypeScript.
- Database: PostgreSQL.
- Communication: REST/JSON.
- Database migrations: Liquibase.
- Infrastructure target: AWS.
- Infrastructure as Code: Terraform.

Modules are logical boundaries inside one deployable backend. They are not microservices.

## 4. MVP Constraints

Do not introduce unless explicitly requested:

- microservices;
- Kafka;
- Kubernetes;
- GraphQL;
- event sourcing;
- CQRS frameworks;
- custom policy DSL;
- Elasticsearch;
- distributed caching;
- complex workflow engines;
- AI features;
- speculative infrastructure.

Prefer the simplest implementation that satisfies the documented requirement.

## 5. Development Principles

- Implement features vertically when practical.
- Business rules belong in the backend.
- The frontend must not duplicate domain rules as an independent source of truth.
- Every tenant-owned resource must be isolated by organization.
- Never trust an `organizationId` supplied by a client as authorization.
- Database schema changes require Liquibase migrations.
- API changes must be intentional and reflected in the API contract when it exists.
- New domain behavior requires automated tests.
- Prefer explicit code over premature abstraction.
- Reuse existing project patterns before introducing new ones.
- Avoid unrelated refactors.
- Do not implement future backlog items while implementing the current item.
- Do not add dependencies without a concrete need.

## 6. Feature Implementation Workflow

When asked to implement a backlog item such as `FZ-020`:

### Before coding

1. Read the backlog item.
2. Read the relevant domain and architecture sections.
3. Inspect affected backend/frontend code.
4. Identify database changes.
5. Identify API changes.
6. Identify domain invariants involved.
7. Identify tenant-isolation implications.
8. Report unresolved requirements instead of guessing.

### During implementation

- Implement only the requested feature and necessary supporting work.
- Keep changes small and reviewable.
- Add/update tests as part of the feature.
- Preserve module boundaries and tenant isolation.

### After implementation

1. Run relevant tests.
2. Run configured build/lint/static-analysis commands.
3. Summarize changed files.
4. State any architectural decision introduced.
5. State unresolved questions or limitations.
6. Never claim a command or test passed unless it was actually executed.

## 7. Repository Shape

```text
freezhub/
├── CLAUDE.md
├── README.md
├── docs/
│   ├── 00-product.md
│   ├── 01-domain.md
│   ├── 02-architecture.md
│   └── 08-backlog.md
├── backend/
├── frontend/
├── infra/
└── scripts/
```

Additional documentation is created just-in-time when implementation requires it:

- `docs/03-data-model.md`
- `docs/04-api.md`
- `docs/05-frontend.md`
- `docs/06-security.md`
- `docs/07-decisions.md`
- `backend/CLAUDE.md`
- `frontend/CLAUDE.md`

## 8. Commands

Do not invent project commands during bootstrap.

Once backend/frontend are initialized, document the exact commands here for:

- local dependencies;
- backend run/test/build;
- frontend run/test/build/lint;
- complete local startup.
