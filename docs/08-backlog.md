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
**Status:** DONE

Implement the Organization tenant boundary.

### FZ-012 — User Authentication
**Status:** DONE

Implement human authentication according to `06-security.md`.

### FZ-013 — Teams
**Status:** DONE

Implement tenant-isolated team management.

### FZ-014 — Applications
**Status:** DONE

Implement tenant-isolated application management and team association.

### FZ-015 — Environments
**Status:** DONE

Implement tenant-isolated environment management.

### FZ-016 — Invite User
**Status:** DONE

**Known gap:** the real Cognito `AdminCreateUser` call is not implemented. `IdentityProvider` (`com.freezhub.shared.security`) is a port; only `LocalIdentityProvider` (fake, `local` profile) exists, mirroring `06-security.md`'s local/test JWT strategy — no real Cognito user pool exists yet (that's infrastructure work, `FZ-063`). A real `CognitoIdentityProvider` implementation must be added before this endpoint is used against a deployed environment; until then, running without the `local` profile active will fail to start (no `IdentityProvider` bean), the same fail-fast behavior as the JWT decoder.

Added during `FZ-012` (see `06-security.md`, Human Authentication): an organization's first user is admin-provisioned out-of-band; every subsequent user must be added in-product. Runs immediately after `FZ-012` in execution order, ahead of `FZ-013`.

Implement an invite endpoint restricted to `ADMINISTRATOR` users:

- Caller must be authenticated and have `role = ADMINISTRATOR` in their organization; otherwise `403`.
- Request: target email (and initial `role`, defaulting to `MEMBER`).
- Backend creates the Cognito identity (`AdminCreateUser` — Cognito emails a temporary password) and, using the returned Cognito `sub`, creates the matching `users` row scoped to the caller's `organization_id`.
- Duplicate invite (existing `users` row for that org + email) is rejected, not silently duplicated.
- Invited user's `organization_id` is always the caller's own organization — never client-supplied beyond that.

## Milestone 2 — Freeze Core

### FZ-020 — Create Change Restriction
**Status:** DONE

**Known gap (deliberately deferred out of this story; CLOSED by `FZ-036`):** the scope association tables reference `team`/`application`/`environment` with non-cascading foreign keys, so the database *refuses* to delete a catalog resource that a restriction references. That refusal is not yet translated into an HTTP response, so `DELETE /api/teams/{id}` (and the application/environment equivalents) on a **referenced** resource returns `500` instead of `409`. Data stays correct — the delete is genuinely refused — only the status code is wrong. Translating it to `409` needs its own story.

Scope matching semantics (OR within a dimension, AND across dimensions, empty dimension = wildcard) were specified by this story and are recorded in `01-domain.md` § Scope matching semantics. They are the contract `FZ-051` implements; no evaluation logic exists yet.

Implement creation of a scheduled deployment restriction.

Required rules:

- `startsAt < endsAt`.
- restriction cannot be entirely in the past.
- at least one scope target is required.
- scope targets belong to the authenticated organization.
- MVP type is `DEPLOYMENT_FREEZE`.
- initial status is `SCHEDULED`.

### FZ-021 — List Change Restrictions
**Status:** DONE

List restrictions for the authenticated organization with useful status filtering.

Implemented as `GET /api/restrictions`, ordered soonest-start-first (tie-broken by id, so ordering is total and results are deterministic). `?status=` may be repeated to select several states at once — `?status=SCHEDULED&status=ACTIVE` answers the product's "what is active or upcoming?" question directly. Omitting it returns every status; an unrecognised value is a `400`.

The list returns a **summary without scope**: `FZ-022` owns "retrieve a restriction and its scope". Keeping scope out of the list also keeps it to a single query regardless of row count. Adding fields later is additive and non-breaking, so `FZ-031` (dashboard) can revisit this if it needs scope inline.

The three scope collections on `ChangeRestriction` were switched from `EAGER` to `LAZY` as part of this story: `EAGER` would have made every list call issue 3N+1 queries. Verified — listing three restrictions issues one query and none against the scope tables.

### FZ-022 — Restriction Details
**Status:** DONE

Retrieve a restriction and its scope.

Implemented as `GET /api/restrictions/{id}`, returning the full representation including all three scope dimensions (unused dimensions come back as empty arrays, not null). Unknown ids and ids owned by another organization are both `404`, so cross-tenant existence is never revealed.

The scope collections are `LAZY` (see `FZ-021`) and `spring.jpa.open-in-view` is disabled, so the service initialises them explicitly inside its read-only transaction — otherwise mapping the response in the controller would fail with `LazyInitializationException`. The detail test covers this: removing the initialisation makes it fail, so the guard is real rather than incidental.

### FZ-023 — Update Scheduled Restriction
**Status:** DONE

Allow supported changes while a restriction is still scheduled.

Exact mutable fields, specified by this story:

| Editable while `SCHEDULED` | Never editable |
|---|---|
| `name`, `description`, `reason` | `id`, `organizationId`, `createdBy`, `createdAt` |
| `level` | `type` (server-controlled) |
| `startsAt`, `endsAt` | `status` (see `FZ-024`, `FZ-025`) |
| all three scope dimensions | |

Implemented as `PUT /api/restrictions/{id}` — a **full replacement**, not a partial patch. PUT avoids the null-versus-absent ambiguity a PATCH would hit on the nullable `description`: in a Java record there is no way to distinguish "field omitted" from "field explicitly set to null", so a PATCH could not express clearing a field.

Editing is permitted only while the restriction is still `SCHEDULED`; `ACTIVE`, `COMPLETED` and `CANCELLED` return `409`. Once a restriction has taken effect it is a record of what happened, and editing it would rewrite history. Every creation invariant is re-checked on update, so an update can never leave a restriction in a state creation would have rejected.

`CreateRestrictionRequest` was renamed to `RestrictionRequest` and is shared by create and update: full-replacement semantics mean both carry identical fields and identical validation, and one record cannot drift out of step with itself. The shared invariant checks were likewise extracted into a single private method used by both paths.

**Deferred architectural concern — mutable aggregate vs. immutability + activity history.** Editing in place overwrites the previous state with no record that it changed or who changed it. The alternative (immutable/versioned restrictions, or an append-only change history) was raised during this story and deliberately deferred, not overlooked. `FZ-060` (Audit Events) covers part of it — recording *that* an administrative action happened — but not making the aggregate itself pristine. This deserves its own focused decision before beta; `docs/07-decisions.md` is the place to record the outcome.

### FZ-024 — Cancel Restriction
**Status:** DONE

Cancel a scheduled or active restriction according to domain rules.

Implemented as `POST /api/restrictions/{id}/cancel` — modelled as an action, not `DELETE`. Cancelling **preserves the record**: the restriction stays visible with status `CANCELLED`, because what was communicated to engineers actually happened and deleting it would erase that. `COMPLETED` and already-`CANCELLED` restrictions return `409`.

Cancellation is deliberately **not idempotent**. The backlog scopes this to "a scheduled or active restriction", and a repeat cancel means the caller believed the restriction was still live — worth surfacing rather than silently succeeding. Revisit if a machine client ever needs safe retries.

Terminality (domain invariants 5 and 6) falls out of the existing rules rather than needing new code: `FZ-023` already refuses to update anything that is not `SCHEDULED`, so a cancelled restriction can neither be edited nor moved back to `ACTIVE`. There is a test asserting exactly that.

### FZ-025 — Restriction Lifecycle
**Status:** DONE

Implement reliable transitions:

```text
SCHEDULED → ACTIVE → COMPLETED
```

and ensure cancellation prevents future activation.

Lifecycle correctness must survive application restarts.

Implemented as **reconciliation**, not scheduling: `RestrictionLifecycleService.reconcile(now)` compares persisted timestamps against a supplied instant and corrects the stored status with two set-based `UPDATE`s. It holds no timers and no in-memory state, so it is idempotent and recovers by itself after an outage of any length — matching `02-architecture.md`'s "transitions must not depend exclusively on an in-memory timer; the persisted timestamps/status are authoritative".

Two triggers, in `RestrictionLifecycleScheduler`:

- **on `ApplicationReadyEvent`** — a restart may leave statuses stale by however long the process was down; reconciling at boot closes that gap immediately instead of leaving it open until the first tick;
- **periodically** — `freezehub.lifecycle.interval` (default `PT1M`), for restrictions coming due while running.

Both disabled by `freezehub.lifecycle.enabled=false`, which the test suite sets so it never races a background job mutating rows underneath it.

Decisions made in this story:

- **`SCHEDULED → COMPLETED` directly is allowed.** If the process was down for a restriction's entire window, it still elapsed; leaving it `SCHEDULED` for ever would be wrong. Nothing in `01-domain.md` forbids skipping `ACTIVE`, and invariant 4 only forbids `COMPLETED → ACTIVE`.
- **Cancellation is honoured implicitly.** Both queries filter on status and `CANCELLED` matches neither, so a cancelled restriction can never be activated or completed. No extra guard was needed — the requirement falls out of the query predicates, and there are tests for both directions.
- **`updatedAt` is stamped explicitly**, because a bulk JPQL update bypasses `@PreUpdate` and the row would otherwise silently keep a stale timestamp.

**Contract for `FZ-051`:** the stored `status` is a materialised convenience and can lag by up to one interval. Policy evaluation must decide from the persisted `startsAt`/`endsAt` (plus "not `CANCELLED`"), *not* from the `status` column alone, or a deployment could be allowed during a freeze whose activation tick had not yet run.

## Milestone 3 — Frontend Product Slice

### FZ-030 — Frontend Specification
**Status:** DONE

Create `docs/05-frontend.md` with MVP routes, page responsibilities, shared UI conventions, and API interaction conventions.

Two decisions were required that no existing document answered, and both shape every later frontend story:

- **Styling: CSS Modules with native form controls**, no UI framework and no styling dependency (`CLAUDE.md`: no dependencies without a concrete need). `<input type="datetime-local">` and `<select multiple>` cover the create-restriction form; multi-select UX is basic, and that is an accepted trade-off.
- **Development authentication: a dev-only token endpoint** (see `FZ-035`), because no Cognito user pool exists until `FZ-063` and a browser therefore has no way to obtain a token at all.

Gaps this specification surfaced are tracked as items rather than prose — `FZ-035` (dev sign-in token, blocks `FZ-031`), `FZ-036` (catalog management UI, blocks `FZ-033`), a UTC-conversion acceptance criterion on `FZ-033`, and a note on `FZ-061` about revisiting the frontend's error handling. `05-frontend.md` carries the resulting Milestone 3 order:

```text
FZ-030 → FZ-035 → FZ-031 → FZ-032 → FZ-036 → FZ-033 → FZ-034
```

`FZ-036` still needs a product decision: build it, or explicitly defer it and accept catalog setup as an API-only onboarding operation.

### FZ-035 — Development Sign-In Token
**Status:** DONE

Implemented as `POST /api/dev/token` (`{"email": …}` → a signed token plus the resolved user), fenced off by **three independent guards**: the controller is `@Profile("local")`; the `JwtEncoder` it depends on exists only under that profile; and `DevSignInSecurityConfig` — the profile-scoped filter chain that makes the path reachable without a token — is likewise absent outside it. Removing the profile annotation does not quietly expose the endpoint, it makes a deployed-shaped context fail to start.

Ambiguity is refused rather than guessed: email is unique *per organization*, not globally, so an email present in two organizations returns `409` instead of signing the developer into an arbitrary tenant.

Token minting lives in `LocalTokenIssuer`, which the test helper `TestTokens` also delegates to — one implementation, so the claim shape cannot drift between what tests assert and what development actually runs against.

Added during `FZ-030`. Runs immediately after it and **before `FZ-031`**, which cannot render an authenticated page without it.

No Cognito user pool exists until `FZ-063`, so the browser currently has no way to obtain a JWT. Provide a dev-only endpoint that mints the same locally-signed token the tests already use, mirroring the local/real split `06-security.md` established for JWT validation.

Acceptance:

- Exposed **only** under the `local` Spring profile — `@Profile("local")`, like `LocalJwtConfig`. No deployed environment activates that profile, so the endpoint cannot exist there.
- Accepts an identifier for an existing `users` row and returns a signed token whose `sub` matches that user's `cognito_subject`.
- Returns 404/400 for an unknown user rather than minting a token for an identity that does not exist.
- A test asserts the endpoint is **absent** when the `local` profile is not active — the security property, not just the happy path.
- Replaced by the Cognito Hosted UI redirect at `FZ-063`.

### FZ-031 — Dashboard
**Status:** DONE

Show active, upcoming, and recently completed restrictions.

First real UI story, so it also brings the frontend plumbing `05-frontend.md` anticipated: the single `fetch` wrapper with `ApiError`, TanStack Query with a central `401` handler that clears the token and drops the user at sign-in, the `RequireAuth` guard, the development sign-in screen (backed by `FZ-035`), the app layout, CSS Module tokens, and the UTC-aware date formatting.

Two requests, not three — `status` is repeatable, so active and upcoming arrive together (`?status=ACTIVE&status=SCHEDULED`) and are split client-side. "Recently completed" is capped at five in the UI, since the backend applies no recency window.

**CORS was added to the backend as part of this story.** The frontend runs on a different origin from the API — a Vite dev server locally, S3/CloudFront when deployed (`02-architecture.md`) — and preflight `OPTIONS` requests carry no `Authorization` header, so they were being rejected as `401` and *every* browser request failed before it was sent. Nothing caught this earlier because the frontend's own tests stub `fetch`; it only appeared under live verification. `freezehub.cors.allowed-origins` is **empty by default**, so a deployed environment has to name its frontend origin explicitly rather than inherit something permissive; the `local` profile fills in the Vite dev server. Covered by `CorsTest`, including that an unconfigured origin is refused.

Also fixed while building this: Testing Library's automatic DOM cleanup never registered, because it only self-registers when Vitest runs with `globals: true`. Every test was leaking its DOM into the next, which surfaced as phantom "found multiple elements" failures. `src/test/setup.ts` now calls `cleanup()` explicitly.

### FZ-032 — Restriction List
**Status:** DONE

Provide usable browsing/filtering of restrictions.

`GET /restrictions` with checkbox filtering across all four statuses. **Filter state lives in the URL**, not component state, so a filtered view can be linked to, bookmarked and survives a reload — and unknown values typed into the query string are discarded rather than forwarded to the API. Results are rendered in the order the backend returns them and are never re-sorted client-side: soonest-start-first is the API's contract, not this page's.

Empty results distinguish "nothing matches this filter" from "no restrictions yet" — very different things to tell someone.

The level badge was extracted to a shared `components/Badges.tsx` (with a new status badge) now that the dashboard and the list both render one, so how a level or status reads is defined once instead of drifting between pages. `RestrictionCard` was updated to use it.

### FZ-036 — Catalog Management UI
**Status:** DONE

`/catalog` — list, create, rename and delete teams, applications and environments, plus team↔application assignment. One shared `CatalogSection` component drives all three types so they cannot drift into three near-identical implementations.

**Two backend gaps had to be closed for this UI to be honest:**

1. **Deleting a referenced catalog entry now returns `409`, not `500`** — the gap deliberately deferred out of `FZ-020`. The database always refused the delete; the refusal simply was not translated. This story puts delete buttons in front of users, so a raw `500` was no longer acceptable. Enforcement stays in the database (`CatalogDeletion` catches the violation after an explicit `flush`), which keeps the dependency direction intact — `restriction` may depend on `catalog`, not the reverse.

2. **Error messages were never reaching clients at all.** Spring omits `message` from its error body by default, so *every* deliberate `ResponseStatusException` reason written anywhere in this API — "a team with this name already exists", "referenced by one or more change restrictions" — arrived as a bare status code. A narrow `ApiExceptionHandler` now returns the reason for deliberately-thrown `ResponseStatusException`s only; unexpected exceptions still fall through to Spring's default with no message, so enabling `server.error.include-message` (which would leak internals from a 500) was avoided. This affected the whole API, not just the catalog — it only became visible here because this is the first UI that has to *explain* a conflict. A full error contract remains `FZ-061`.

Added during `FZ-030`. Runs **before `FZ-033`**, which cannot offer scope pickers for teams, applications and environments that no one can create.

`00-product.md` names an Organization Administrator who "configures the organization, users, catalog, integrations", and `FZ-013`–`FZ-015` built the tenant-isolated catalog API — but no story ever exposed it in the UI. Without this, creating a restriction through the product requires first creating catalog entries with `curl`.

Scope is deliberately minimal — enough to make `FZ-033` usable, not full administration:

- List and create teams, applications and environments.
- Rename and delete where the API already supports it.
- Associate/disassociate a team with an application (`FZ-014`), since restriction scope is evaluated per dimension and the team dimension is meaningless without membership.
- Surface `409` on a duplicate name, and the tenant rules already enforced by the backend.

**Known rough edge:** deleting a team/application/environment referenced by a restriction currently returns `500` rather than `409` (see the known gap under `FZ-020`). This UI will make that reachable by a user rather than only by an API client, which raises its priority.

**Manual entry is one input path, not the only one.** Raised when this was scoped: an organization's catalog largely mirrors systems it already has — applications map onto repositories, teams onto GitLab/GitHub groups — so a realistic product ingests it rather than asking someone to retype it. Native GitHub/GitLab integration is explicitly out of MVP scope (`00-product.md`, Out of Scope), so this story builds typed entry only.

The consequence for the design is that this UI must stay a **thin CRUD over the existing catalog API** and must not become the place where anything about the catalog is decided. Specifically:

- No "source" or "origin" concept is modelled — that would be speculative ahead of an ingestion story.
- The UI adds no rules the API does not already enforce; a future sync writing to the same endpoints must produce the same result as a human typing.
- Nothing here assumes a human is the only writer, so an ingestion path can be added later without unpicking this.

An eventual sync will raise questions this story does not answer — what happens to a manually created application when a sync later claims the same name, and whether synced entries become read-only. Those belong to the integration story that introduces them.

### FZ-033 — Create Restriction UI
**Status:** DONE

Create the end-to-end form for FZ-020.

`/restrictions/new`, reachable from the restriction list. Posts name, description, reason, level, window and all three scope dimensions; `type` and `status` are deliberately absent from the body because both are the server's to decide.

**The UTC conversion is guarded by the test suite's timezone.** `vite.config.ts` runs the whole suite at `TZ=America/Bogota` (UTC-5), because on a UTC machine a missing conversion is indistinguishable from a correct one — the bug would pass every test and ship. `localInputToUtcIso` converts the zoneless `datetime-local` value; a test asserts 09:00 local posts as `14:00:00.000Z`. Verified by deliberately removing the conversion: the test fails with `expected '2026-11-27T09:00' to be '2026-11-27T14:00:00.000Z'`, so the guard is real rather than incidental. Confirmed end-to-end too — Postgres stores `2026-11-27 14:00:00+00`.

Client-side validation lives in `restrictionFormRules.ts` and mirrors the backend rules **for fast feedback only**; the file says so explicitly, and a backend rejection is surfaced rather than swallowed (there is a test where the backend rejects a payload the client considered valid). If the two ever disagree, the backend is right and that file is the bug.

If the catalog is empty the form says so and links to it, rather than presenting three empty pickers and a rule the user cannot satisfy.

**Not used:** React Hook Form and Zod, both listed in `02-architecture.md`'s target stack. The form's rules are a handful of field checks plus two cross-field ones, and the existing catalog forms are hand-rolled — adding two dependencies and a second form pattern for this was not justified (`CLAUDE.md`: reuse existing patterns; no dependencies without a concrete need). Worth revisiting if forms grow substantially.

Acceptance:

- Submits `name`, `description` (optional), `reason` (**required, non-blank**), `level`, `startsAt`, `endsAt` and the three scope dimensions to `POST /api/restrictions`.
- **Datetime values are converted to UTC before submission.** `<input type="datetime-local">` produces a *zoneless local* string; sending it unconverted silently shifts every freeze window by the user's UTC offset, violating `01-domain.md` invariants 9 and 10. This must be covered by a test that would fail under a non-UTC timezone — it is the single most likely correctness bug in this story.
- Instants are displayed in the viewer's local zone **with the zone shown**, so a stated start time is never ambiguous.
- Client-side validation mirrors the backend rules for fast feedback only and is never the source of truth: the backend re-validates, and its `400`/`404`/`409` responses are surfaced rather than swallowed (`CLAUDE.md` §5 — the frontend must not duplicate domain rules as an independent source of truth).
- Depends on `FZ-036` (or on the explicit decision to keep catalog setup API-only) for the scope pickers to have anything to select.

### FZ-034 — Restriction Detail UI
**Status:** DONE

Show status, dates, reason, level, scope, and lifecycle information.

`/restrictions/:id` shows the full representation, with scope **ids resolved to names** — a reader learns nothing from `teamIds: [3]` — and an unconstrained dimension shown as *Any*, which is what the wildcard rule actually means (`01-domain.md`).

**Actions follow the backend's rules rather than being offered and refused:** edit appears only while `SCHEDULED` (`FZ-023`), cancel only while `SCHEDULED` or `ACTIVE` (`FZ-024`), and a terminal restriction says so instead of showing dead buttons. A table-driven test covers all four statuses. The backend still enforces everything — a `409` from the realistic race (the restriction activated or completed while the page was open) is surfaced with its message, not swallowed.

Edit is `/restrictions/:id/edit`, reusing a `RestrictionForm` extracted from `FZ-033`: update is a full replacement server-side, so create and edit carry identical fields and identical rules, and one component cannot drift from the other. Stored UTC instants are converted back to local wall-clock time for the inputs via `utcIsoToLocalInput`, the inverse of the conversion `FZ-033` guards.

Verified live through the exact endpoints the UI calls: `GET` → `PUT` (200 while scheduled) → `cancel` (200) → `PUT` after cancellation (**409**, "Only a SCHEDULED restriction can be updated; this one is CANCELLED") → cancel again (**409**).

**This completes Milestone 3.**

## Milestone 4 — Notifications

### FZ-040 — Notification Model + Outbox
**Status:** DONE

Persist notification intent and delivery state without a message broker.

Schema specified just-in-time in `03-data-model.md` (`integration`, `notification`). Two decisions were required that no document answered:

- **One outbox row = one delivery attempt to one destination.** Slack succeeding while a webhook fails is a normal outcome, and a single status per event could not express it; retry (`FZ-044`) also has to be per-destination. Matches `01-domain.md`'s "a delivery attempt" literally.
- **A minimal `integration` table is included**, because fan-out at enqueue time needs to know an organization's destinations, and `03-data-model.md` grouped it with this story. `config` is opaque JSON-as-text: each channel needs different settings and only the owning channel interprets them.

**Rows are written in the same transaction as the domain change.** That is the entire point — a process dying between "restriction activated" and "notification queued" cannot lose the notification, which is what a broker would otherwise have been for (`02-architecture.md`). `NotificationOutbox.enqueue` is `Propagation.MANDATORY`, so calling it outside a transaction fails loudly rather than silently reopening that gap; there is a test for it.

**Enqueueing is idempotent**, enforced both in the service and by a unique constraint on `(restriction, integration, event)`. Reconciliation runs on a timer and is deliberately idempotent, so without this an organization would be notified once a minute for the life of a restriction. Verified live that the database itself refuses a duplicate.

Enqueue points: creation → `SCHEDULED`, cancellation → `CANCELLED`, and the lifecycle reconciler → `ACTIVATED` / `COMPLETED`. The reconciler's set-based updates report a count rather than rows, so the affected restrictions are read with the same predicate immediately before the update.

**Nothing is delivered yet** — `FZ-041`–`FZ-043` add the channel adapters, `FZ-044` the retry policy.

**Known gaps:**

1. **No API or UI configures integrations** — now tracked as `FZ-045`, which runs before `FZ-041`.
2. **The "restriction starting soon" notification is not implemented** — `OI-3`, owned by `FZ-047`.
3. **Destination credentials are stored in plain text** — `OI-4`, needs a decision before beta.

### FZ-045 — Integration Configuration
**Status:** DONE

`/api/integrations` (list, create, enable/disable, replace config, delete) plus a Settings area in the UI. `ADMINISTRATOR`-only, like invite.

**The stored credential is never returned.** A Slack webhook URL is a bearer credential — anyone holding it can post into that channel — so the API stores it and reads back only a `summary` that identifies the destination without being enough to reuse it (`hooks.slack.com`, `2 recipients`). There is no endpoint that returns `config`, which is why changing a credential means replacing it rather than editing it. Verified live: the secret is absent from the response body and still present in the database.

`IntegrationConfigs` owns what each channel's config must contain — the one place that knows, since `integration.config` is deliberately opaque to everything else (`03-data-model.md`). It also rejects non-`https` destinations: these carry credentials and freeze announcements.

Disabling is distinct from deleting: disabling stops announcements while keeping the configuration, so a destination can be switched back on without re-entering its credential. Deleting cascades any queued notifications for it, since a delivery attempt to a destination that no longer exists has nowhere to go.

Verified end to end with `FZ-040`: a destination created through the API receives fan-out on restriction creation, and once disabled receives nothing further.

Added during `FZ-040`. Runs **before `FZ-041`**, which is otherwise implementable but not usable: the outbox fans out to an organization's integrations, and nothing lets a customer create one.

`00-product.md` names an Organization Administrator who "configures the organization, users, catalog, integrations", and `FZ-040` built the schema — but no story exposed it. Without this, enabling Slack means inserting a row directly into the database.

Acceptance:

- List, create, enable/disable and delete integrations for the authenticated organization, tenant-isolated on the same rules as every other resource (another organization's integration is `404`, never `403`).
- Restricted to `ADMINISTRATOR`, like invite (`06-security.md`) — a destination is where freeze announcements go, and its config may hold a credential.
- Each type's `config` is validated by the code that owns that type, since `integration.config` is deliberately opaque to everything else (`03-data-model.md`).
- **A stored credential is never returned.** A Slack webhook URL is a bearer credential; the API must not read it back out in a list response. Show enough to identify the destination, not enough to reuse it.
- Deleting an integration cascades its queued notifications (`ON DELETE CASCADE`, already in the schema) — document that this discards undelivered ones.
- A Settings area in the UI covering the same operations.

### FZ-041 — Slack Notifications
**Status:** DONE

Deliver selected restriction lifecycle notifications to Slack.

A `NotificationSender` port (the same shape as `IdentityProvider`) plus `SlackNotificationSender`, drained by `NotificationDispatcher` on a timer (`freezehub.notifications.interval`, default `PT30S`, disabled in tests). Email (`FZ-042`) and webhook (`FZ-043`) arrive as new senders without touching the dispatcher; a channel with no adapter yet leaves its notification `PENDING` rather than marking it delivered or discarding it.

**Delivery is one notification per transaction**, in a bean of its own (`NotificationDelivery`). That separation is load-bearing rather than stylistic: Spring's transactions are proxy-based, so a `@Transactional` method called from another method of the *same* bean is not intercepted at all — the first draft had exactly that bug, and it would have run the whole batch with no per-notification transaction and nothing to show for it. Per-notification transactions are also what stop one failing destination rolling back deliveries that succeeded alongside it.

**A destination credential never reaches `last_error`.** That column is read by whoever is diagnosing a missing announcement, and most HTTP client exceptions include the request URI by default — which for Slack *is* the credential. The sender raises its own exception type carrying a description instead. Verified live: a genuine connection failure recorded `Slack rejected or could not be reached: ResourceAccessException`, with no URL in it.

Announcement wording lives in `NotificationMessage`, separate from any channel so every channel says the same thing and the wording can be asserted without sending anything. Times are rendered **in UTC and labelled**: a freeze announcement reaches a distributed audience with no shared local zone.

Verified end to end against a real HTTP receiver, not only mocks — creating a restriction produced an actual POST carrying
`{"text":"Deployment freeze scheduled: Black Friday Freeze\nReason: …\nWindow: 27 Nov 2027 14:00 to 2 Dec 2027 09:30 UTC"}`,
and the notification moved to `SENT` with `sent_at` set.

**Known gap — retry is unbounded.** Tracked as `OI-1` in `09-open-issues.md`, owned by `FZ-044`. A failed delivery stays `PENDING` and is retried on every dispatch pass, for ever; measured live at `attempts=3` within about twelve seconds at a five-second interval.

### FZ-042 — Email Notifications
**Status:** DONE

Deliver selected lifecycle notifications by email.

**Plain SMTP via `JavaMailSender`, not the AWS SES SDK.** SES exposes an SMTP endpoint, so the same code runs against a local mail catcher in development and against SES when deployed (`02-architecture.md`) — one implementation, and none of the local/real split Cognito needed (`OI-2`). It also keeps the AWS SDK out of the dependency list for now.

The sender is `@ConditionalOnProperty` on `freezehub.notifications.email.from`. Without it the dispatcher finds no EMAIL sender and those notifications **defer without consuming a retry attempt** — an unconfigured mail server is a configuration gap, not a delivery failure, and it must not exhaust a notification's budget (`FZ-044`). The property is deliberately **not declared** in `application.yml`: `@ConditionalOnProperty` counts an empty value as present, which would have registered a sender with a blank from-address.

Subject is the shared headline and body the shared text from `NotificationMessage`, so email says exactly what Slack says — there is a test asserting the body is character-for-character what the shared builder produces. Recipients are kept out of failure messages, since `last_error` is read by support and should not become an address list.

`docker-compose.yml` gains **Mailpit** behind a `dev` compose profile (`docker compose --profile dev up -d mailpit`), so email can be exercised for real without sending anything. Verified end to end: a destination created through the API received an actual SMTP message at both recipients — subject `Deployment freeze scheduled: Black Friday Freeze`, body carrying the reason and the UTC window — and the notification moved to `SENT`.

### FZ-043 — Generic Webhook
**Status:** DONE

Deliver machine-readable lifecycle events to configured webhook endpoints.

Unlike Slack and email, this body is a **contract** someone else's software parses, so it is treated as public surface: `WebhookPayload` carries a `version` (cheap now, and the only thing that makes a later change safe to roll out), instants are ISO-8601 rather than epoch numbers, and an `X-FreezeHub-Event` header lets a receiver route without parsing the body first.

**Scope is deliberately omitted from the payload.** A consumer given the affected teams, applications and environments would have to re-implement the matching rules (OR within a dimension, AND across them, empty meaning any) to decide whether a particular deployment is affected — a second implementation of domain logic, outside FreezeHub, that would inevitably drift and be wrong. The Policy API (`FZ-051`) answers "may I deploy"; this event says only that something changed and is worth re-checking. There is a test asserting scope is absent, so it cannot be added casually.

The endpoint URL is kept out of failure messages, as with Slack: a webhook URL commonly carries a token in its path or query, and `last_error` is read by support.

Verified live against a real receiver — creating then cancelling a restriction produced two POSTs with `X-FreezeHub-Event: SCHEDULED` and `CANCELLED`, each carrying the versioned payload with `status` reflecting the state at that moment.

**Milestone 4's delivery channels are now complete.** The "no sender configured" behaviour that previously used WEBHOOK as its example moved to `UnconfiguredEmailTest`, which exercises the realistic version: email with no from-address, deferring indefinitely without consuming retry attempts.

**Known gap:** webhook deliveries are not authenticated — a receiver cannot verify a request came from FreezeHub. Tracked as `OI-7`; needs a decision, since no document specifies a signing scheme.

### FZ-044 — Notification Retry
**Status:** DONE

**Fixed `OI-1`.** Verified by re-running the exact scenario that exposed it, with a *harsher* dispatch interval: previously a dead destination reached `attempts=3` in ~12 s at a 5 s interval; now it reaches `attempts=1` in 20 s at a **2 s** interval, with the next attempt scheduled 41 s out.

- `notification.next_attempt_at` (migration `012`) is what makes "not yet" expressible — without it every `PENDING` row was eligible on every pass, which *was* the defect. The dispatcher now selects on `(status, next_attempt_at)`, and the index was changed to match.
- `RetryPolicy` is standalone and static so the schedule is asserted directly rather than by waiting: 30 s doubling to a 15 min cap, six attempts, then terminal `FAILED`. The cap exists because unbounded doubling eventually means "never"; the limit exists so an undeliverable announcement is visibly given up on — nobody learns an announcement never arrived if it retries for ever.
- **Failures are separated by kind**, which the original code conflated:
  - *delivery failed* — consumes an attempt, backs off, eventually `FAILED`;
  - *destination or restriction deleted* — `abandon()`, terminal immediately, since retrying cannot fix it;
  - *destination disabled*, or *channel adapter not built yet* (`FZ-042`/`FZ-043`) — `deferUntil()`, **no attempt consumed**. A webhook notification must not exhaust its retry budget waiting for an adapter that does not exist yet, and being switched off is not a delivery failure.
- A transient failure followed by success ends `SENT` with `lastError` cleared, so a stale error is never presented as the current state.

Implement bounded retry and observable failure state.

**Fixes `OI-1`** (`09-open-issues.md`): today a failed delivery stays `PENDING` and is retried on every dispatch pass for ever. Measured during `FZ-041` — an unreachable destination reached `attempts=3` in about twelve seconds at a five-second interval.

Acceptance:

- A notification is attempted at most a bounded number of times, then becomes `FAILED` and is not attempted again.
- Attempts are spaced by a backoff, so a dead destination is not hit on every pass. This needs a `next_attempt_at` column — the current schema has no way to say "not yet", so the dispatcher cannot skip a row without either losing it or spinning on it.
- `FAILED` is observable: the reason is retained in `last_error`, and it is possible to see that an announcement was never delivered. A silently undelivered freeze notice is the failure mode that matters — engineers deploy believing nothing is frozen.
- Retry state is per notification, not per event, since the outbox is already per destination.
- A transient failure followed by a success still ends `SENT`, with the earlier error no longer presented as current.

### FZ-046 — Cognito Identity Provider
**Status:** TODO

**Fixes `OI-2`.** Implements the real `AdminCreateUser` path behind the existing `IdentityProvider` port, so inviting a user works outside the `local` profile.

Until this exists the backend **cannot start at all** without the `local` profile, because no `IdentityProvider` bean is defined — deliberate fail-fast (`FZ-016`), but it blocks any deployed environment.

Depends on a Cognito user pool existing, so sequence with `FZ-063`. Acceptance: an implementation selected outside the `local` profile, configured per environment rather than hardcoded, that creates the identity and returns its `sub`; failures surface as a clear error rather than a half-created user.

### FZ-047 — "Starting Soon" Notification
**Status:** TODO

**Fixes `OI-3`.** `00-product.md` lists a "restriction starting soon" notification; nothing implements it.

**Blocked on a product decision:** how soon is "soon", and is the lead time fixed, per organization, or per restriction? No document says.

It also differs structurally from every other lifecycle event — it is triggered by the passage of time rather than by a state transition, so a scheduled check writes the outbox rather than a domain change doing so. That check must be idempotent in the same way the lifecycle reconciler is, or a restriction would be announced as "starting soon" on every pass.

## Milestone 5 — Policy Enforcement

### FZ-050 — API Contract
**Status:** DONE

Create/finalize `docs/04-api.md` and the machine-facing policy contract.

`04-api.md` documents the conventions (auth, tenant isolation, status codes, UTC), summarises the human API, and specifies the Policy Evaluation contract that `FZ-051` implements.

Decisions made by this story:

- **Identify application and environment by name, not id.** A pipeline knows `payments-api` and `production`; making it discover FreezeHub's internal ids contradicts `00-product.md`'s "simple integration". Accepted consequence, recorded in the contract: renaming a catalog entry is a breaking change for any pipeline referencing the old name.
- **`POST`, not `GET`, despite being a read.** A `GET` is cacheable and a cached `ALLOW` is exactly the wrong thing to serve during a freeze; no proxy should be able to answer this.
- **"In force" is derived from `startsAt`/`endsAt`, never from `status`.** The status column is maintained by a reconciler on an interval (`FZ-025`) and can lag, so trusting it would allow a deployment during a freeze whose activation tick had not yet run — a hole that would surface only under load or just after a restart. `FZ-025` flagged this; the contract now formalises it.
- The response lists **every** matching restriction rather than only the deciding one, including on `ALLOW` with advisories — a caller told only "BLOCK" cannot act on it.

Two things `FZ-051` must not decide on its own:

- **`OI-8` — unrecognised names can bypass a freeze.** Blocking; needs an explicit decision. Written up in `04-api.md` § Open decision.
- **`OI-9` — the Policy API has no machine credential until `FZ-052`.** The backlog orders `FZ-051` before API keys; the endpoint that decides whether deployments are blocked should not ship without machine authentication. Simplest fix is to do `FZ-052` first or alongside — which is what happened; closed by `FZ-052`.

### FZ-051 — Policy Evaluation
**Status:** TODO

Evaluate a `DEPLOY` action for application/environment context.

Required semantics:

- no matching active restriction → `ALLOW`;
- only matching `ADVISORY` → `ALLOW` with advisory information;
- any matching `HARD_FREEZE` → `BLOCK`;
- cancelled/completed restrictions do not affect the decision.

**Sequencing note added by `FZ-052`:** machine authentication now exists ahead of this story, so the endpoint only has to be mapped under `/api/policy/**` and read its organization from the `ApiKeyPrincipal` on the security context. `OI-9` is closed.

**Still blocked by `OI-8`** — what happens when a policy request names an application or environment FreezeHub does not recognise. `04-api.md` § Open decision sets out the options; it must not be settled in code.

### FZ-052 — API Keys
**Status:** DONE

**Resolves `OI-9`, and was pulled ahead of `FZ-051` to do it.** The backlog ordered the Policy API first, which would have meant the endpoint that decides whether deployments are blocked existing with no machine credential to call it. This story delivers both the credential and the machine-facing chain that consumes it, so `FZ-051` adds a handler behind a door that is already locked.

- **Key format** `fzh_` + 256 bits of `SecureRandom` in URL-safe Base64. The prefix is for leak scanners and support, not decoration; URL-safe so nothing between CI and here mangles it.
- **The raw key is stored nowhere.** Only its SHA-256 hash, and the assertion is written against the persisted row rather than the API response, so no future response shape can quietly reintroduce it. `key_prefix` (the first ten characters) is what a listing shows instead — enough to say which credential a row is, nowhere near enough to use it.
- **Revocation is one-way.** `revoked_at` is set once and never cleared, unlike `Integration.enabled`. A key is withdrawn precisely because it may already be in someone else's hands; restoring it would revive that copy. Re-revoking is `409`, matching restriction cancellation.
- **A key opens exactly one door.** The machine chain is scoped to `/api/policy/**`, so a credential leaked from a CI variable cannot read restrictions, edit the catalog, or mint further keys. The reverse also holds: a human JWT is refused on the machine chain. Both mismatches are `401`. Widening the matcher to `/api/**` makes those tests fail, which is how the scoping is known to be load-bearing.
- **`ApiKeyPrincipal` is deliberately not an `AuthenticatedUser`**, and carries no authorities. No person is behind a pipeline call, so there is no user id, email or role to invent — and an empty authority list means a machine credential can never satisfy `hasRole(...)`.

**Decision — the hash is not salted**, contradicting the literal wording of `06-security.md` ("salted hash (e.g. SHA-256)"), which has been updated with the reasoning. A salt defeats rainbow tables and offline brute force against *low-entropy* secrets; against 256 bits of randomness there is nothing to guess. A per-key salt would also stop the hash of an incoming key from identifying its row, forcing either a second lookup handle inside the token or hashing every stored row on every call — on the endpoint asked once per deployment. What the specification actually requires is untouched: the raw key is never stored, and lookup is by hash.

**Defect found and fixed — errors behind the machine chain came back as `401`.** A failed request is forwarded to `/error`, that forward is re-filtered by Spring Security, and `/error` does not match `/api/policy/**` — so it fell through to the human chain, found no JWT, and answered `401`. A pipeline receiving `401` for a malformed body would go looking at its credential, which is the wrong place entirely. Fixed by permitting the `ERROR` dispatch on the main chain: an error is already the outcome of a request that was authorized on its way in, and re-authorizing the forward only replaces that outcome with a worse one.

Worth recording *how* it was found: **only by running the application.** MockMvc resolves handler exceptions in place and never performs the forward, so no controller test could have shown it. `ApiKeyErrorDispatchTest` therefore runs against a real servlet container on a random port — the first test in the codebase that does — and fails without the fix.

Not built, deliberately: **no frontend.** `05-frontend.md` specifies no API keys screen and no backlog item asks for one, so keys are issued through the API for now. **No `last_used_at`**, which would mean a write on the hottest path in the product for a question nobody has asked yet; revisit if "is this key still in use?" comes up before a revocation.

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

Note added during `FZ-030`: this milestone lands **after** the frontend is built, so Milestone 3 ships against unstandardised error bodies. `05-frontend.md` therefore requires the fetch wrapper to tolerate a body it cannot parse and fall back to a status-derived message. When this item is implemented, revisit that wrapper and the per-page error states rather than assuming they still match — the status codes the UI branches on (`400`/`401`/`403`/`404`/`409`) are already established by the endpoints and should not change, but the body shape will.

The backend currently returns errors via `ResponseStatusException` and Spring's defaults; no custom error contract exists yet.

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
FZ-011
  ↓
FZ-012
  ↓
FZ-016   (added during FZ-012; out of numeric order, runs here — see FZ-016)
  ↓
FZ-013...
```

Parallel work is allowed only when dependencies are clear and the changes do not create conflicting architectural decisions.
