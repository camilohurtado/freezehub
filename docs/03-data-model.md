# FreezeHub — Data Model

## Purpose

This document specifies the logical persistence model required before implementing tenant/catalog persistence (`FZ-011`–`FZ-015`), per `FZ-005`.

It covers `Organization` and the `Catalog` domain module (`Team`, `Application`, `Environment`) as defined in `01-domain.md`.

## Scope

Covered now:

- `organization`
- `users`
- `team`
- `application`
- `team_application`
- `environment`

Deliberately deferred, added just-in-time before their own backlog items:

- `ChangeRestriction` / `RestrictionScope` schema — before `FZ-020`.
- `Integration` / `Notification` / outbox schema — before `FZ-040`.
- `ApiKey` schema — before `FZ-052`.
- `AuditEvent` schema — before `FZ-060`.

These are already conceptually defined in `01-domain.md`, but committing to their physical schema now would be speculative ahead of the stories that actually implement them.

**Open item — `users` table:** `FZ-010` (Security Specification) has not run yet and is the next item after this one in the execution order. Authentication-related columns (identity provider subject, credential/session fields, etc.) are intentionally **not** specified here to avoid guessing ahead of that decision. Only the tenant-membership shape of `users` is defined below; expect a follow-up migration before `FZ-012`.

## Conventions

- Primary keys: `UUID`, generated with Postgres' built-in `gen_random_uuid()` (no extension required on Postgres 16). Chosen over sequential IDs so tenant-scoped resource identifiers are not guessable/enumerable across organizations — relevant given `organizationId` from a client is never trusted as authorization (`CLAUDE.md` §5).
- Timestamps: `TIMESTAMPTZ`, written/compared in UTC, per domain invariant "time must be persisted and compared using an unambiguous UTC representation" (`01-domain.md`).
- Every tenant-owned table has a non-null `organization_id` foreign key to `organization.id`.
- Liquibase changesets for these tables are added per backlog item (e.g. `FZ-011` adds `organization`), not all at once here — this document specifies the target shape, migrations land incrementally.

## Tables

### `organization`

The tenant boundary. Not itself tenant-owned.

```text
id             UUID PRIMARY KEY DEFAULT gen_random_uuid()
name           VARCHAR(255) NOT NULL
created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
```

### `users`

Tenant-owned. Minimal shape — see "Open item" above.

```text
id               UUID PRIMARY KEY DEFAULT gen_random_uuid()
organization_id  UUID NOT NULL REFERENCES organization(id)
email            VARCHAR(320) NOT NULL
created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()

UNIQUE (organization_id, email)
```

### `team`

Tenant-owned.

```text
id               UUID PRIMARY KEY DEFAULT gen_random_uuid()
organization_id  UUID NOT NULL REFERENCES organization(id)
name             VARCHAR(255) NOT NULL
created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()

UNIQUE (organization_id, name)
```

### `application`

Tenant-owned. Examples per `01-domain.md`: `payments-api`, `checkout-web`, `identity-service`.

```text
id               UUID PRIMARY KEY DEFAULT gen_random_uuid()
organization_id  UUID NOT NULL REFERENCES organization(id)
name             VARCHAR(255) NOT NULL
created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()

UNIQUE (organization_id, name)
```

### `team_application`

Join table. "An application may be associated with one or more teams" (`01-domain.md`) — modeled many-to-many.

```text
team_id          UUID NOT NULL REFERENCES team(id)
application_id   UUID NOT NULL REFERENCES application(id)

PRIMARY KEY (team_id, application_id)
```

Invariant (application-layer, not a database constraint): `team_id` and `application_id` must belong to the same `organization_id`. Postgres cannot express "same tenant across two FK targets" as a single constraint without a trigger; this is enforced in the service layer, consistent with `01-domain.md`'s restriction-scope tenant-isolation invariant.

### `environment`

Tenant-owned. Names are organization-owned — no fixed taxonomy is assumed (`01-domain.md`: "FreezeHub must not assume every organization uses the same environment taxonomy").

```text
id               UUID PRIMARY KEY DEFAULT gen_random_uuid()
organization_id  UUID NOT NULL REFERENCES organization(id)
name             VARCHAR(255) NOT NULL
created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()

UNIQUE (organization_id, name)
```

## Relationships

```text
organization ──1───* users
organization ──1───* team
organization ──1───* application
organization ──1───* environment

team *───* application   (via team_application)
```

## Tenant Isolation

Applies to every table above except `organization` itself, per `01-domain.md`'s Core Invariants:

1. Every row belongs to exactly one `organization_id`.
2. A row from one organization must never be visible or usable by another.
3. `organization_id` is derived from authenticated context, never trusted from client-supplied request input.
4. Uniqueness constraints (`name`, `email`) are scoped per-organization, not global.
