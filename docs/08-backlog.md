# FreezeHub — MVP Implementation Backlog

## Purpose

This file defines implementation order and scope for AI-assisted development.

A backlog ID is a boundary. When Claude Code is asked to implement one item, it must not silently implement later items.

Statuses:

- `TODO`
- `IN_PROGRESS`
- `DONE`
- `BLOCKED`

## Milestone 0 — Foundation

### FZ-001 — Repository Bootstrap
**Status:** DONE

Create the initial monorepo structure and baseline README.

Acceptance:

- `backend/`, `frontend/`, `infra/`, and `scripts/` exist.
- bootstrap documentation remains accessible.
- no unnecessary application framework code is generated.

### FZ-002 — Backend Bootstrap
**Status:** DONE

Initialize the Java/Spring Boot backend.

Acceptance:

- Java 21 project builds.
- Spring Boot application starts.
- health endpoint is available.
- baseline automated test runs.
- backend development commands are documented.

### FZ-003 — Frontend Bootstrap
**Status:** DONE

Initialize React + TypeScript frontend.

Acceptance:

- Vite application starts.
- routing foundation exists.
- baseline test/build/lint commands run.
- frontend development commands are documented.

### FZ-004 — Local PostgreSQL + Liquibase
**Status:** DONE

Introduce PostgreSQL local development and migration infrastructure.

Acceptance:

- PostgreSQL can run locally.
- backend connects through configuration/environment variables.
- Liquibase executes an initial migration.
- Testcontainers strategy is available for integration tests.

### FZ-005 — Domain Persistence Specification
**Status:** DONE

Create `docs/03-data-model.md` before implementing tenant/catalog persistence.

Acceptance:

- logical tables and relationships are documented.
- tenant-owned tables are identified.
- no speculative schema unrelated to MVP is added.

## Milestone 1 — Organization and Catalog

### FZ-010 — Security Specification
**Status:** DONE

Create `docs/06-security.md` and finalize MVP human/machine authentication approach before sensitive implementation.

### FZ-011 — Organization Foundation
**Status:** TODO

Implement the Organization tenant boundary.

### FZ-012 — User Authentication
**Status:** TODO

Implement human authentication according to `06-security.md`.

### FZ-013 — Teams
**Status:** TODO

Implement tenant-isolated team management.

### FZ-014 — Applications
**Status:** TODO

Implement tenant-isolated application management and team association.

### FZ-015 — Environments
**Status:** TODO

Implement tenant-isolated environment management.

## Milestone 2 — Freeze Core

### FZ-020 — Create Change Restriction
**Status:** TODO

Implement creation of a scheduled deployment restriction.

Required rules:

- `startsAt < endsAt`.
- restriction cannot be entirely in the past.
- at least one scope target is required.
- scope targets belong to the authenticated organization.
- MVP type is `DEPLOYMENT_FREEZE`.
- initial status is `SCHEDULED`.

### FZ-021 — List Change Restrictions
**Status:** TODO

List restrictions for the authenticated organization with useful status filtering.

### FZ-022 — Restriction Details
**Status:** TODO

Retrieve a restriction and its scope.

### FZ-023 — Update Scheduled Restriction
**Status:** TODO

Allow supported changes while a restriction is still scheduled.

Exact mutable fields must be specified before implementation.

### FZ-024 — Cancel Restriction
**Status:** TODO

Cancel a scheduled or active restriction according to domain rules.

### FZ-025 — Restriction Lifecycle
**Status:** TODO

Implement reliable transitions:

```text
SCHEDULED → ACTIVE → COMPLETED
```

and ensure cancellation prevents future activation.

Lifecycle correctness must survive application restarts.

## Milestone 3 — Frontend Product Slice

### FZ-030 — Frontend Specification
**Status:** TODO

Create `docs/05-frontend.md` with MVP routes, page responsibilities, shared UI conventions, and API interaction conventions.

### FZ-031 — Dashboard
**Status:** TODO

Show active, upcoming, and recently completed restrictions.

### FZ-032 — Restriction List
**Status:** TODO

Provide usable browsing/filtering of restrictions.

### FZ-033 — Create Restriction UI
**Status:** TODO

Create the end-to-end form for FZ-020.

### FZ-034 — Restriction Detail UI
**Status:** TODO

Show status, dates, reason, level, scope, and lifecycle information.

## Milestone 4 — Notifications

### FZ-040 — Notification Model + Outbox
**Status:** TODO

Persist notification intent and delivery state without a message broker.

### FZ-041 — Slack Notifications
**Status:** TODO

Deliver selected restriction lifecycle notifications to Slack.

### FZ-042 — Email Notifications
**Status:** TODO

Deliver selected lifecycle notifications by email.

### FZ-043 — Generic Webhook
**Status:** TODO

Deliver machine-readable lifecycle events to configured webhook endpoints.

### FZ-044 — Notification Retry
**Status:** TODO

Implement bounded retry and observable failure state.

## Milestone 5 — Policy Enforcement

### FZ-050 — API Contract
**Status:** TODO

Create/finalize `docs/04-api.md` and the machine-facing policy contract.

### FZ-051 — Policy Evaluation
**Status:** TODO

Evaluate a `DEPLOY` action for application/environment context.

Required semantics:

- no matching active restriction → `ALLOW`;
- only matching `ADVISORY` → `ALLOW` with advisory information;
- any matching `HARD_FREEZE` → `BLOCK`;
- cancelled/completed restrictions do not affect the decision.

### FZ-052 — API Keys
**Status:** TODO

Implement secure organization-owned machine credentials according to `06-security.md`.

### FZ-053 — CI/CD Integration Example
**Status:** TODO

Provide at least one simple pipeline example consuming the Policy API.

Do not build a native plugin yet.

## Milestone 6 — Audit and Beta Readiness

### FZ-060 — Audit Events
**Status:** TODO

Record important administrative and restriction lifecycle actions.

### FZ-061 — Error Handling
**Status:** TODO

Standardize API error responses and frontend handling.

### FZ-062 — Observability Baseline
**Status:** TODO

Add production-appropriate logs, health checks, and visibility for notification/policy failures.

### FZ-063 — Production Infrastructure
**Status:** TODO

Implement the minimum AWS/Terraform deployment architecture required for beta.

### FZ-064 — CI/CD
**Status:** TODO

Build/test/deploy automation for backend and frontend.

### FZ-065 — Beta Hardening
**Status:** TODO

Perform focused review of:

- tenant isolation;
- API key security;
- date/time behavior;
- restriction lifecycle;
- notification reliability;
- policy determinism;
- critical UI paths.

## Deferred

Do not implement during MVP unless explicitly promoted into scope:

- exception requests;
- approval workflow;
- native GitHub/GitLab/Jenkins/Argo CD integrations;
- Jira/ServiceNow/PagerDuty;
- advanced RBAC;
- enterprise SSO;
- AI/change intelligence;
- dependency graphs;
- arbitrary policy language;
- infrastructure/database-specific restriction types.

## Immediate Execution Order

Start with:

```text
FZ-001
  ↓
FZ-002 + FZ-003
  ↓
FZ-004
  ↓
FZ-005
  ↓
FZ-010
  ↓
FZ-011...
```

Parallel work is allowed only when dependencies are clear and the changes do not create conflicting architectural decisions.
