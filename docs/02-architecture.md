# FreezeHub — MVP Architecture

## Architecture Goal

Build the smallest architecture that can safely support a multi-tenant FreezeHub beta while remaining easy to understand and modify with AI-assisted development.

## Architecture Style

FreezeHub uses a **modular monolith**.

The backend is one deployable application with explicit logical module boundaries.

The frontend is a separate web application consuming the backend REST API.

```text
┌──────────────────────┐
│ React + TypeScript   │
│ Frontend             │
└──────────┬───────────┘
           │ HTTPS / JSON
           ▼
┌─────────────────────────────────────────────┐
│ Spring Boot Modular Monolith                │
│                                             │
│ organization │ catalog │ restriction        │
│ policy       │ notification │ integration   │
│ audit                                       │
└──────────┬──────────────────────────────────┘
           │
           ▼
┌──────────────────────┐
│ PostgreSQL           │
└──────────────────────┘

External adapters:
Spring Boot → Slack
Spring Boot → Email provider
Spring Boot → Customer Webhooks
CI/CD → Policy REST API
```

## Repository Strategy

Use a monorepo:

```text
freezhub/
├── CLAUDE.md
├── docs/
├── backend/
├── frontend/
├── infra/
└── scripts/
```

This makes end-to-end feature work and AI-assisted repository reasoning simpler during the MVP.

## Backend

Target stack:

- Java 21
- Spring Boot
- Spring Web
- Spring Security
- Spring Data JPA
- Bean Validation
- PostgreSQL
- Liquibase
- OpenAPI/springdoc
- JUnit 5
- Testcontainers

Initial package/module direction:

```text
com.freezhub
├── organization
├── catalog
├── restriction
├── policy
├── notification
├── integration
├── audit
└── shared
```

`shared` must remain small. Domain-specific code belongs in its owning module.

Avoid creating abstraction layers without a concrete use case.

## Frontend

Target stack:

- React
- TypeScript
- Vite
- React Router
- TanStack Query
- React Hook Form
- Zod

Prefer feature-oriented organization:

```text
src/
├── app/
├── features/
│   ├── auth/
│   ├── dashboard/
│   ├── restrictions/
│   ├── applications/
│   ├── teams/
│   ├── environments/
│   ├── integrations/
│   └── audit/
├── components/
├── api/
├── hooks/
├── types/
└── utils/
```

The frontend handles presentation and interaction. Authoritative business decisions remain in the backend.

## Persistence

Use PostgreSQL as the only application database for the MVP.

Use Liquibase for all schema migrations.

Logical persistence areas will include:

- organizations/users;
- catalog;
- change restrictions/scopes;
- integrations;
- API keys;
- notifications/outbox;
- audit events.

The exact physical schema will be specified just-in-time in `03-data-model.md`.

## Restriction Lifecycle

Restriction lifecycle transitions must not depend exclusively on an in-memory timer.

The persisted timestamps/status are authoritative.

A scheduled process may update lifecycle status, but application reads/policy evaluation must remain correct across restarts.

Detailed lifecycle implementation is deferred until `FZ-025`.

## Notifications

Do not introduce Kafka for the MVP.

Use a database-backed outbox/delivery model so notification intent survives process restarts.

Conceptual flow:

```text
Domain change
    ↓
Persist business change + notification/outbox record
    ↓
Worker processes pending delivery
    ↓
Slack / Email / Webhook
    ↓
Record success/failure
```

Retry behavior will be specified when notification work begins.

## Policy API

The Policy API is the main machine integration boundary.

Conceptually:

```text
CI/CD
  │
  │ evaluate DEPLOY
  ▼
Policy API
  │
  ├── resolve organization
  ├── resolve application/environment
  ├── find matching ACTIVE restrictions
  ├── apply documented restriction-level rules
  ▼
ALLOW / BLOCK + explanation
```

Native CI/CD plugins are not required for the MVP.

## Authentication and Authorization

Human authentication and machine authentication are separate concerns.

### Humans

Initial target: managed authentication suitable for the AWS deployment strategy.

### Machines

API keys authenticate CI/CD clients.

Security details will be frozen in `06-security.md` before authentication/tenant-sensitive features are completed.

Critical rule:

**Client-provided organization identifiers are not authorization.**

## Infrastructure

Target production environment:

- AWS
- ECS Fargate for backend
- RDS PostgreSQL
- S3 + CloudFront for frontend
- Cognito for managed user authentication, subject to security-step confirmation
- SES for email
- Secrets Manager for secrets
- CloudWatch for logs/metrics
- Terraform
- GitHub Actions

Local development should use Docker/Docker Compose where useful.

Do not build production infrastructure before the application needs it.

## Observability

MVP baseline:

- structured application logs;
- request correlation identifier where practical;
- health endpoint;
- delivery failure logging;
- policy evaluation error visibility;
- CloudWatch in production.

Avoid a dedicated observability stack for the MVP.

## Architecture Constraints

Do not introduce:

- microservices;
- message brokers;
- Kubernetes;
- service mesh;
- separate databases per module;
- event sourcing;
- distributed transactions;
- custom policy engines.

Reconsider these only after measured product/scale requirements justify them.

## Just-In-Time Documents

Create these when the corresponding implementation starts:

```text
03-data-model.md   → before persistence-heavy domain implementation
04-api.md          → before public API contracts stabilize
05-frontend.md     → before substantial UI implementation
06-security.md     → before authentication/API keys are completed
07-decisions.md    → when meaningful architectural decisions accumulate
10-demo.md         → before the product is demonstrated to anyone outside the team
11-commercial.md   → before there is a way for a company to become a customer
```

Architecture documentation should record decisions and constraints, not duplicate source code.
