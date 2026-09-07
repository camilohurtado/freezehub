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

### FZ-037 — Frontend Test Stability
**Status:** DONE

**Fixes `OI-10`.** `npm run test` passed 64 tests and simultaneously reported "Vitest caught 3 unhandled errors", which Vitest itself flags as a possible source of false positives.

Cause: `renderRoute` builds a memory router containing only the route under test and `/signin`. `CreateRestrictionPage` navigates to `/restrictions/{id}` on success, nothing matches it, and React Router dereferences the missing match while the test is unmounting.

Fixed by giving `renderRoute`'s memory router a catch-all route, so anywhere a component navigates lands somewhere. Generic rather than specific to this page: every future test gets it, and tests assert where they ended up through the returned `router`, so navigation is observable rather than swallowed.

The suite now reports **zero** unhandled errors, and two tests were added for behaviour that had never been asserted — the redirect to the new restriction (which proves the server-assigned id is read from the response rather than the form state reused), and staying put on a failed creation so a filled-in form is not lost. Changing the redirect target fails the first of them.

Worth noting how it hid: the suite passed all 64 tests *and* reported the errors, so nothing failed. That is exactly the state in which a real unhandled rejection goes unnoticed.

### FZ-038 — Administrator Settings UI
**Status:** DONE

**Closes most of `OI-11`.** Two things an administrator needed existed only in the API: issuing an API key — the primary integration step for the whole product — and setting how much warning a freeze gives. Requiring a hand-written HTTP call for either is not an onboarding path.

**The one-time key reveal is the part that had to be right.** The raw key exists in exactly one HTTP response and nowhere afterwards, so it is shown on its own with a warning and **stays until dismissed**. Not a toast: a message that disappears by itself is the wrong shape for something unrecoverable, and the only remedy for missing it is issuing another key. There is a test for the persistence, not just the display.

Revoking asks first, because there is no un-revoke and a pipeline stops working the moment it happens. A revoked key shows as revoked with no button, and a double revoke surfaces the backend's `409` rather than appearing to succeed.

The lead time is a fixed set of choices — an hour up to a week — rather than a minutes field. The API accepts anything from 1 minute to 30 days, but "how much warning does the team want" has about six sensible answers, and a number box invites someone to type 90000 and collect a validation error. A value set outside the UI is kept and shown rather than silently replaced by the nearest option.

`SettingsPage` now composes three sections (`IntegrationsSection`, `ApiKeysSection`, `OrganizationSection`), following the existing `CatalogSection` pattern — three inline would have made one component nobody wants to read. Each loads and fails independently, so a member sees three explanations rather than one blank page.

Lists gained accessible names, which the existing test needed anyway once there were two of them.

**Verified against a running backend**, comparing every response shape to the TypeScript types: `GET /api/api-keys` carries no `key` field, `POST` does, revoke returns the revoked row, and a member gets `403` on keys and on saving settings but `200` reading the organization — which is why this screen only reports forbidden on save.

### FZ-039 — Audit Screen
**Status:** DONE

**Closes `OI-11`.** `/audit`, Administrator only, matching the API — the trail names who did what.

The companion to the deployment console: that one shows a pipeline being refused, this one shows why the rules it was judged against look the way they do. After `FZ-072` the two connect — a wall of refusals in the console and, in the trail, the rename a minute earlier that caused it.

**The work here is making the record readable**, not fetching it:

- `details` arrives in two shapes and both had to render: a before-and-after diff from an edit (`{"name": {"from": …, "to": …}}`) and a flat object from everything else. Showing raw JSON would push the parsing onto whoever is reading the screen during an incident.
- A cleared field reads as *"description: Peak trading → nothing"* rather than as a blank half of an arrow.
- Wording is composed from the action **and** `resourceType`, so `CATALOG_RENAMED` + `APPLICATION` reads "Renamed application". That is why `FZ-072` stored three catalog actions rather than nine.
- An action this build has never heard of degrades to readable words instead of rendering as a blank row.
- **The system's own work is not attributed to a person.** A restriction takes effect because time passed; `RESTRICTION_ACTIVATED` shows as *automatic*, and naming someone would be a fiction.

Supporting backend change: `GET /api/audit` gained a `?resourceType=` filter. Once the trail holds catalog changes, key issuance and policy refusals together, "what happened to our restrictions" is a different question from "who has been issued a key", and scrolling past the other is not an answer. The UI ignores a resource type it does not offer, so a hand-edited URL cannot send the backend something it will reject.

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
**Status:** DONE

**Fixes `OI-3`.** `00-product.md` lists a "restriction starting soon" notification; nothing implements it.

**Blocked on a product decision:** how soon is "soon", and is the lead time fixed, per organization, or per restriction? No document says.

It also differs structurally from every other lifecycle event — it is triggered by the passage of time rather than by a state transition, so a scheduled check writes the outbox rather than a domain change doing so. That check must be idempotent in the same way the lifecycle reconciler is, or a restriction would be announced as "starting soon" on every pass.

**Decision: the lead time is per organization, defaulting to 24 hours.** Release rhythms differ — a weekly train wants more notice than a shop deploying continuously — and defaulting rather than asking at sign-up means nobody answers a question they have no opinion about yet. Bounded between 1 minute and 30 days, in the database and in the API, so a bad value is a `400` rather than a `500`.

**Idempotence was the whole difficulty**, and it needed no new machinery: a restriction stays inside its warning window for the entire lead time, so the sweep sees it again on every pass, and the outbox's existing unique constraint on (restriction, integration, event) is what makes it announce exactly once. Verified live at a three-second sweep interval — roughly five passes produced exactly one announcement.

`StartingSoonNotifier` runs on the same tick as lifecycle reconciliation, and deliberately **after** it: reconciling status first is what stops a restriction that has just begun from also being announced as about to.

The sweep is bounded by the largest lead time any organization has configured, so it examines a small window rather than every future restriction across every tenant.

New: `GET /api/organization` (any member — knowing how much warning the team gets is not sensitive) and `PATCH /api/organization/settings` (Administrator only, like every other setting decided on the whole organization's behalf). Neither takes an organization id, because the organization comes from the credential.

**No frontend.** `05-frontend.md` has no settings screen, and adding one is a larger change than this story. The lead time is set through the API for now — recorded as `OI-11`.

### FZ-048 — Webhook Signing
**Status:** DONE

**Fixes `OI-7`.** A receiver currently has no way to verify that a webhook request came from FreezeHub, so anyone who learns a customer's endpoint can post a forged event to it — and a forged `CANCELLED`, telling an automated consumer that a freeze has been lifted, is exactly the event worth forging.

**Decision (recorded in `07-decisions.md`): HMAC-SHA256 over timestamp and body**, with a per-integration secret FreezeHub generates and shows once.

Acceptance:

- A webhook integration is issued a signing secret at creation, returned once and never readable again.
- Every delivery carries `X-FreezeHub-Timestamp` and `X-FreezeHub-Signature: sha256=<hex>`, computed over `timestamp + "." + body` so a captured request cannot be replayed with a new body or a stale one accepted for ever.
- The secret can be rotated without recreating the integration.
- The secret is never returned by any listing and never appears in a log or in `notification.last_error`.

**Verified against a real receiver**, not only in tests: a Node endpoint doing standard HMAC verification accepted two genuine deliveries (`SCHEDULED`, `CANCELLED`), then rejected an unsigned forgery, a forgery with a guessed signature, and a genuine body replayed with a stale timestamp. After rotating the secret, the receiver — still holding the old one — rejected the next delivery, which is rotation taking effect with no overlap window.

The signed string format is pinned by a test vector computed **outside this codebase** with `openssl dgst -sha256 -hmac`. That is the assertion that matters: change the `.` separator or drop the timestamp from the signed string and every receiver in the world silently starts rejecting deliveries, with nothing in Java to catch it.

`WebhookSigning` deliberately does not reuse `ApiKeySecret` despite near-identical generation. The storage lifecycle is the opposite — an API key is stored hashed and verified by hashing what arrives; this secret must stay recoverable because signing needs the key. Sharing a type would invite someone to store this one hashed, which would break every delivery at once.

A webhook created before this story has no secret and is delivered **unsigned with a warning logged**, rather than not at all: refusing would silently stop announcements a customer relies on. Rotating fixes it.

**Cost, recorded in `D-2` and against `OI-4`:** this is a second credential that cannot be hashed at rest.

### FZ-049 — Credential Encryption at Rest
**Status:** DONE

**Fixes `OI-4`.** `integration.config` (which holds a Slack webhook URL — itself a bearer credential) and `integration.signing_secret` were readable to anyone with a database connection, a dump, or a backup. Neither can be hashed instead: the channel needs the URL, and HMAC needs the key.

**Decision `D-3`: AES-256-GCM in the application, behind a `SecretProtector` port.** The port is the point — the key already comes from configuration (Secrets Manager in a deployed environment), and delegating storage to a provider outright is a second implementation of the same interface rather than a rewrite of its callers.

- **A JPA `AttributeConverter`, not explicit calls in services.** The entity field stays plaintext in Java, so nothing that reads or validates a config needed changing, and no code path can forget to encrypt. It is a Spring bean as well as a converter, which Hibernate resolves through Boot's `SpringBeanContainer`.
- **GCM rather than CBC**: it authenticates as well as encrypts, so a row edited directly in the database fails loudly instead of quietly yielding different plaintext. A tampering test asserts that.
- **No default key outside `local`.** An environment that forgets it fails to start, matching the existing `JwtDecoder` fail-fast. The committed local key protects a developer's own database and is worth nothing.
- **Stored as `fzenc1:` + Base64.** The scheme prefix is what makes a later change — a new algorithm, key rotation, or delegation to a secrets manager — rolloutable rather than a flag day. A value without it is pre-`FZ-049` plaintext, read as-is and encrypted on its next write, so no bulk migration is needed.

**Verified against the raw column through JDBC, deliberately bypassing JPA** — going through the repository would only prove the converter is symmetric and would pass just as happily if nothing were encrypted. Removing the `@Convert` annotations fails those tests.

Also verified live: a new Slack destination's credential is `fzenc1:…` in the table with **zero** rows containing the plaintext, while the API still summarises its host correctly; a webhook created afterwards still produced deliveries that an independent receiver verified, proving decryption works on the delivery path; and a pre-existing plaintext row was readable and came back encrypted once it was rewritten.

Known limits, recorded in `D-3`: no protection against a compromised application, which holds the key; and no key rotation yet.

One test needed changing rather than the design: `OrganizationRepositoryTest` is a `@DataJpaTest` slice, which does not scan `@Component`, so it now imports the protector explicitly and supplies a key — the same thing a deployed environment must do.

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
**Status:** DONE

`POST /api/policy/evaluate`, the machine boundary and the reason FreezeHub is a service rather than a wiki page. Authenticated by API key alone (`FZ-052`), so the organization comes from the credential and a caller cannot ask about a tenant that is not its own.

- **"In force" is derived from `startsAt`/`endsAt`, never from `status`** — the contract `FZ-025` flagged and `FZ-050` formalised. Verified live rather than only in tests: with four restrictions whose windows had opened but whose stored status was still `SCHEDULED` because the reconciler had not run, evaluation correctly returned `BLOCK`. Making the service trust the status column instead makes five tests fail.
- **The matching rule lives on `ChangeRestriction.covers(...)`**, not in a query or a service — it is the rule the whole product exists to enforce, so it is expressed once, in the domain object, in a form that reads like `01-domain.md` and is provable without a database. `RestrictionScopeMatchingTest` walks the worked examples from the docs, including the one that is easy to get backwards: naming a team *and* an application **narrows** rather than widens.
- The response lists **every** matching restriction, advisories included, and carries each one's `reason`: someone staring at a blocked pipeline needs to know why the freeze exists, not merely that it does.

**`OI-8` is decided and closed: an unregistered application or environment name blocks**, and the response names which one. An unrecognised name matches no scope list, so evaluating it normally tended toward `ALLOW` — misspelling the environment was a deliberate route to deploying straight through a freeze with a legitimate-looking permission in the log.

Returned as a `200` carrying `BLOCK` rather than a `4xx`, deliberately. An error status lands in the pipeline's error branch, which is exactly where `04-api.md` tells clients to choose fail-open or fail-closed for themselves — so a rejection expressed as an error could be configured back into a deployment, while a decision cannot. Applied to the environment dimension as well as the application: the bypass originally raised was literally `prod` versus `production`.

Names match **exactly, including case**, because catalog uniqueness is case-sensitive and a looser match here would disagree with the registry being read. The accepted cost, taken knowingly: FreezeHub becomes a gate on catalog completeness — an unregistered application cannot deploy at all, even with no freeze anywhere.

**Performance.** Scope collections gained `@BatchSize`, so evaluation costs a constant number of queries rather than three per candidate restriction — this is asked once per deployment. Measured live: **20 in-force restrictions, three scope queries**, where the unbatched form would have issued 60.

Still open and belonging to `FZ-060`: whether evaluations naming an unregistered resource should be **recorded**, so a repeated bypass attempt is visible afterwards rather than only refused in the moment.

Evaluate a `DEPLOY` action for application/environment context.

Required semantics:

- no matching active restriction → `ALLOW`;
- only matching `ADVISORY` → `ALLOW` with advisory information;
- any matching `HARD_FREEZE` → `BLOCK`;
- cancelled/completed restrictions do not affect the decision.
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
**Status:** DONE

`examples/` holds a worked deployment gate: `freeze-check.sh` (POSIX shell, `curl` + `jq`) and `gitlab-ci.yml` wiring it into a pipeline. No native plugin, per the story.

**Moved by `FZ-091`:** the script now lives at `connectors/freeze-check.sh` — it stopped being an example the moment connectors started wrapping it. `examples/` keeps the hand-rolled walkthrough for CI systems with no connector.

**The logic lives in a script, not in pipeline YAML.** It is then identical on every CI system, runnable on a laptop while debugging, and readable by whoever is looking at it during an actual freeze. `gitlab-ci.yml` is wiring; the README carries a six-line GitHub Actions equivalent calling the same script rather than a second copy of the logic.

**It answers the question `04-api.md` deliberately left to the client** — what FreezeHub's silence means. `FREEZEHUB_ON_ERROR` is `block` by default, because a gate that opens when it breaks is not a gate, and the trade-off is stated in the script itself rather than buried in prose.

Two cases are deliberately **not** subject to that setting, and this is the part worth keeping:

- **`HTTP 401` fails the pipeline even under `allow`.** A bad or revoked credential is not an outage. If it failed open, revoking a key — or fat-fingering a CI variable — would silently switch enforcement off for every pipeline still using it, and nothing would look broken.
- **A missing required variable fails.** Enforcement must not be disableable by breaking the configuration.

Exit `1` (blocked) and exit `2` (not evaluated) are distinct, because "you may not deploy" and "I could not find out" are different facts and only the second is the platform team's problem. A request timeout is set, since without one "fail closed" quietly becomes "hang until the job times out" — worse than either choice on offer.

**Verified by running it against a live backend**, not by inspection: ten paths — allow; allow with an advisory printed; hard freeze blocking; unregistered environment blocking with the corrective hint; bad key under `ON_ERROR=allow` still exiting `2`; unreachable FreezeHub under both `block` and `allow`; missing variable; invalid `ON_ERROR` value rejected rather than silently treated as permissive; and no temp file left behind.

The root `README.md` status section was five milestones stale ("Milestone 0 in progress"); corrected while adding `examples/` to the layout it documents.

Provide at least one simple pipeline example consuming the Policy API.

Do not build a native plugin yet.
## Milestone 6 — Audit and Beta Readiness

### FZ-060 — Audit Events
**Status:** DONE

Record important administrative and restriction lifecycle actions.

**Shaped by `D-1` (`07-decisions.md`), which resolves `OI-5`.** An audit event is how a change to a restriction is remembered; restrictions stay mutable and are *not* versioned. That makes this story responsible for more than "an action happened":

- Each event records who, when, the action, and the resource.
- For an update, it carries a **snapshot of the fields that changed, before and after**. Without that, "someone edited this freeze" is not an answer to anything.
- Events are immutable once written.

`FZ-023` already refuses edits once a restriction is `ACTIVE`, `COMPLETED` or `CANCELLED`, so this covers the window before a freeze takes effect — which is the only window in which a restriction can change at all.

Not in scope, and the accepted cost of `D-1`: point-in-time reconstruction. If that becomes a real requirement, an append-only revision table is the upgrade and these events are not wasted.

**Recorded:** restriction created / updated / cancelled (by a user) and activated / completed (by the system); API key issued and revoked; organization settings changed; and a policy evaluation refused because it named an unregistered application or environment.

That last one closes the thread `D-14` left open. Only *refusals* are recorded — an ordinary evaluation happens on every deployment and would bury the trail it belongs to — and it is the one entry attributed to an `API_KEY` actor rather than a person. One occurrence is a typo; twenty is a pattern, and the pattern is only visible if each one is written down.

Design points worth keeping:

- **`AuditTrail` uses `Propagation.MANDATORY`**, exactly like `NotificationOutbox`. The entry commits in the same transaction as the change it describes, so no entry can claim a change that rolled back and none is missing for one that did not. Calling it outside a transaction fails loudly rather than silently reintroducing the gap.
- **The actor's label is denormalised and there is no foreign key to `users`.** An audit trail that breaks — or is rewritten — when a user is renamed or removed is not an audit trail.
- **Only changed fields are recorded**, with before and after. A diff listing every field buries the one thing the reader came for. An update that changed nothing succeeds and records nothing.
- **`AuditDetails` exists so nothing builds that JSON by concatenation.** The first version of the API-key entry did, and an API key named `ci "quoted" name` would have produced a row no reader could parse. Verified live with exactly that name.
- **Keyset pagination on `id`, not an offset.** The trail is append-only, so with an offset every entry written between two page requests shifts the window and the reader silently skips some.

**Corrected during the story:** a comment claimed the scope collections had to be copied because `replaceEditableState` refills them in place. Removing the copy and re-running the tests showed they still passed — `FieldChanges.compare` evaluates eagerly, so it is the *ordering* that makes the diff correct, not the copy. The comment now says that; the copies are kept as cheap insurance, not presented as the thing that saves it.

**Not recorded by this story:** catalog changes, left out to keep it to what `00-product.md` asks for and tracked as `OI-12`. Added by `FZ-072`.

**No frontend.** `05-frontend.md` specifies no audit screen; the trail is readable through the API. Folded into `OI-11`.

### FZ-061 — Error Handling
**Status:** DONE

Standardize API error responses and frontend handling.

Note added during `FZ-030`: this milestone lands **after** the frontend is built, so Milestone 3 ships against unstandardised error bodies. `05-frontend.md` therefore requires the fetch wrapper to tolerate a body it cannot parse and fall back to a status-derived message. When this item is implemented, revisit that wrapper and the per-page error states rather than assuming they still match — the status codes the UI branches on (`400`/`401`/`403`/`404`/`409`) are already established by the endpoints and should not change, but the body shape will.

**Decision `D-17`: RFC 9457 Problem Details**, `application/problem+json`, one shape for the whole API. Status codes are unchanged, as this entry required.

What actually improved, beyond consistency:

- **A validation failure now names the fields.** Previously a `400` said only "400" — a form could not highlight what it was never told about. The `errors` extension lists each field with its message, and where there is exactly one, `detail` names it directly so a client ignoring extensions still gets something actionable.
- **The invariant is preserved and now stated:** a deliberate rejection explains itself, an unexpected failure never does. Every `500` reads "The request could not be completed."; the real exception is logged. There is a test that throws a deliberately identifiable message and asserts it appears nowhere in the response.
- Jackson's own message is not returned for a malformed body, because it quotes the offending JSON and names the Java types it tried to bind.

**A regression this story introduced and its own test caught:** adding a catch-all `@ExceptionHandler(Exception.class)` swallowed Spring's `NoResourceFoundException`, turning an unmapped path from `404` into `500`. `ApiKeyErrorDispatchTest` — written in `FZ-052` against a real servlet container for a completely different reason — failed immediately. The fix checks for the `ErrorResponse` interface rather than a list of exception types, so a Spring exception this code has never heard of is still answered with its own status.

Frontend: the wrapper reads `detail`, exposes `fieldErrors`, and composes them into the message. It still tolerates an unparseable body — deliberately, since a `401` from the security chain has no body and a proxy in front of the API is outside the backend's control.

### FZ-062 — Observability Baseline
**Status:** DONE

Add production-appropriate logs, health checks, and visibility for notification/policy failures.

**Request correlation.** Every request gets an id — an incoming `X-Request-Id` is honoured so a load balancer's or a caller's own tracing survives, otherwise one is generated. It goes into the MDC, onto every log line, into the response header, and into the error body (`FZ-061`). A user quoting the `requestId` from a failed response is now an exact log lookup rather than "it failed at about three".

The supplied header is **sanitised before it reaches a log file**: an unbounded value is somebody else's newline injected into the logs, and a forged log line is worse than a missing one. The MDC is cleared in a `finally`, because threads are pooled and inheriting the previous request's id is confidently wrong rather than merely absent.

**A gap the live check exposed:** the id had almost nothing to correlate with. Nothing logged a completed request — a validation failure logs nothing at all — so the id appeared only on the rare line the application chose to write. Grepping for a real request id found zero lines. The filter now writes one line per request (method, path, status, duration), excluding health probes, which the load balancer asks for every thirty seconds and which would bury everything else. No headers and no body: one carries credentials, the other carries customer data.

**Health checks.** Liveness and readiness probes are enabled and the load balancer now reads **readiness** rather than the aggregate endpoint — readiness reports false while Liquibase is still migrating, which is exactly when a task must not be sent traffic and exactly when the aggregate endpoint already says UP. The security chain needed `/actuator/health/**`, not an exact match, or the probes would have answered the load balancer with a `401`; there is a test for it.

**Visibility.** Micrometer counters, no new dependency:

- `freezehub.notifications{outcome=sent|failed|abandoned|deferred}`
- `freezehub.policy.evaluations{decision, reason}`

`abandoned` is the one worth alerting on — an announcement that will now never arrive — and it is logged at ERROR rather than WARN for the same reason. Policy blocks are counted separately by *why*: a block by a real freeze is the product working, while a rise in `unregistered` is a pipeline misconfigured or somebody probing for a way through (`D-14`). All series are registered at startup so a dashboard has a line to draw before the first deployment rather than a gap.

**Deliberately not a health indicator** — see `D-18`. Metrics are exposed behind authentication and `health` is public; moving actuator to an internal port is the next step once there is real traffic, and exporting to CloudWatch or Prometheus is a deployment concern for when the infrastructure is actually applied.

### FZ-063 — Production Infrastructure
**Status:** DONE (written and validated; **never applied**)

The minimum AWS/Terraform deployment architecture for beta, matching `02-architecture.md`'s target: ECS Fargate behind an HTTPS ALB, RDS PostgreSQL in private subnets, S3 + CloudFront for the frontend, Cognito for human authentication, ECR for images, and Secrets Manager for the database password and the application encryption key.

Deliberate choices, each with its cost stated in `infra/README.md`:

- **One NAT gateway, not one per AZ**, and **single-AZ RDS**. Both trade availability for roughly half the monthly bill at a scale with no availability commitment. The NAT gateway is a third of the total cost on its own; it earns that by keeping the application and database off the public internet.
- **Security groups reference each other rather than CIDR ranges**, so the chain is explicit and reviewable: internet → ALB → application → database, and nothing skips a step.
- **Immutable ECR tags, never `latest`.** A rollback is a tag change, not a rebuild and hope.
- **ARM64 Fargate**, cheaper per vCPU-hour, which the build must match.
- **`SPRING_PROFILES_ACTIVE` is the environment name, never `local`.** Its absence is what makes the self-signing `JwtDecoder` and the development sign-in endpoint impossible in a deployed environment — the guard `FZ-035` built, honoured here.
- **A 120-second health-check grace period**, because Liquibase runs at startup; without it the ALB marks a migrating task unhealthy and ECS replaces it in a loop that looks like a crash.
- **`prevent_destroy` on the encryption key** and its secret. Losing it makes every stored credential permanently unreadable (`D-3`), and key rotation is not implemented, so `terraform taint` there would destroy customer data rather than refresh a key.
- **State lives in S3 with a DynamoDB lock**, created by `infra/bootstrap` with local state. Not optional: state holds the database password and the encryption key.

**Verified:** `terraform validate` passes and `terraform fmt -check` is clean for both the main configuration and the bootstrap, with providers pinned by a committed `.terraform.lock.hcl` (aws 5.100.0, random 3.9.0).

**Not verified, and this matters:** `terraform plan` and `terraform apply` have never been run. `validate` checks syntax and internal references; it does not check provider-side constraints, so an invalid argument value or an unsupported combination would surface only at plan or apply time. Terraform is also not installed on the development machine — the binary used here was fetched to a scratch directory.

**This does not make the product deployable on its own.** `FZ-046` is still outstanding, so the backend refuses to start outside the `local` profile: the infrastructure can be created, and the service will not come up. That pairing is decision `D-4`, not an oversight.

### FZ-064 — CI/CD
**Status:** DONE (verification proven; **deployment never run**)

The story splits cleanly, and the two halves are in very different states.

**`verify.yml` needs no AWS and is proven.** Four parallel jobs — backend (`mvnw verify`, with Testcontainers starting a real PostgreSQL on the runner's own Docker daemon), frontend (`npm ci`, lint, test, build), infrastructure (`terraform fmt -check` plus `validate` with `-backend=false`, which is what makes it need no credentials), and the example gate (`sh -n`, because a syntax error there breaks a customer's pipeline rather than ours). Parallel so a lint failure does not hide a test failure. Every command was run locally exactly as CI runs it, including `npm ci` from a clean checkout.

**`deploy.yml` has never executed**, because there is no AWS account, no ECR repository and no GitHub remote. It is written and reviewed, not proven.

**A gap this story found: there was no `Dockerfile`.** `FZ-063` built an ECS task definition around a container image, and `infra/README.md` documented `docker buildx build backend/` — with nothing to build. Added here as a multi-stage build so `docker build backend/` works identically on a laptop and in CI, with no "build the jar first" step to forget.

**A bug caught while writing the deploy job:** the first version ran `aws ecs update-service --force-new-deployment`, which redeploys whatever revision the service already has — and that one still names the *previous* image. It would have reported a successful deploy and shipped nothing. It now registers a new task definition revision.

**No AWS credentials are stored in GitHub.** The runner exchanges a short-lived OIDC token for a role (`infra/github-oidc.tf`). The trust policy is scoped to this repository *and* the master branch: left open, a fork's pull request could deploy to production, or another account's repository could assume the role outright. `iam:PassRole` is scoped to the two task roles rather than `*`, since `*` there is escalation to anything either role can do.

**CI deliberately cannot run `terraform apply`.** That would need a role able to change the database, the certificates and the user pool — far more than deploying needs. Terraform stays a human operation, and the ECS service gained `lifecycle.ignore_changes = [task_definition]` so the two do not fight over the image tag. The accepted consequence: the running image is no longer described by the Terraform, but by the deploy that put it there — which is why tags are immutable and named after the commit.

**Verified locally** by building and running the image: healthy in 10 seconds against the real database, running as **uid 999 rather than root**, the JVM as **PID 1** so it receives the SIGTERM ECS sends (`docker stop` returns in 0s rather than being killed after the timeout), 379 MB. A production-shaped run — no `local` profile — fails fast on the missing `IdentityProvider`, which is `OI-2` behaving exactly as `FZ-016` designed and confirms that gap is the only thing between this image and a deployment.

**What remains unproven:** every step that touches AWS. The workflow's correctness rests on reading it, not on running it.

Build/test/deploy automation for backend and frontend.

### FZ-066 — Decision Log Backfill
**Status:** DONE

**Fixes `OI-6`.** `02-architecture.md` asked for `docs/07-decisions.md` "when meaningful architectural decisions accumulate". They had — a dozen of them, each recorded only in the backlog entry of the story that made it, which is not where anyone looks for "why is it like this".

`FZ-048` created the file for `D-1` and `D-2`; this backfills `D-5` to `D-16` and adds `D-4`, recording the decision to sequence the Cognito adapter with `FZ-063` rather than build it against a service nothing can reach (`OI-2`).

**Numbering is by when a decision was written down, not when it was made.** `D-1` to `D-3` are already referenced from `SecretProtector`, `AesGcmSecretProtector` and three documents, so renumbering into chronological order would have broken those references for a cosmetic gain. Each entry carries the story that made it instead.

Every entry states a **cost**. That is the part worth keeping: a decision recorded without what it gave up reads as a justification rather than a decision, and is no help to whoever revisits it.

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

### FZ-072 — Catalog Changes in the Audit Trail
**Status:** DONE

**Fixes `OI-12`.** `FZ-060` recorded restriction, API key, user and settings changes but not catalog ones. That gap grew teeth once `FZ-071` shipped: because an unrecognised name blocks (`D-14`), renaming an application turns every pipeline still sending the old name into a refusal — so the console fills with red and nothing anywhere says when it started or who caused it.

Recorded now: `CATALOG_CREATED`, `CATALOG_RENAMED` and `CATALOG_DELETED` for teams, applications and environments, plus `APPLICATION_TEAM_ASSIGNED` and `APPLICATION_TEAM_UNASSIGNED` — the latter because moving an application between teams silently changes what a team-scoped freeze covers without anybody touching the freeze.

**Three actions rather than nine.** Which kind of thing it was is already `resourceType`, so `CATALOG_RENAMED` + `APPLICATION` says everything `APPLICATION_RENAMED` would, without nine enum values that would only ever be read together.

**No migration.** `FZ-060` put a `CHECK` on `actor_type` and deliberately not on `action` or `resource_type`, so new values cost nothing.

Nothing is recorded when nothing happened: a rename to the same name, a repeated assignment, or a deletion refused because a restriction still references the entry (`409`). Each has a test.

**Verified live by reproducing the scenario that motivated it** — a pipeline deploying happily, an administrator renaming the application, and the same unchanged pipeline refused a second later. The trail now reads as the story it is:

```text
07:12:05  dev@acme.test  CATALOG_RENAMED  APPLICATION  {"name":{"from":"payments-api","to":"payments-service"}}
07:12:05  gitlab-ci      POLICY_BLOCKED_UNREGISTERED   {"application":"payments-api","unregistered":"[APPLICATION]"}
```

### FZ-074 — Demo Data and Walkthrough
**Status:** DONE

`scripts/seed-demo.sh` builds a believable organization to demonstrate against, and `docs/10-demo.md` is the walkthrough it was built for.

**Everything goes through the real API**, so the audit trail and deployment console fill with entries the product genuinely produced rather than rows written straight into tables. The single exception is the first user, which the API deliberately cannot create — there is no self-service signup (`06-security.md`) — so that one row is inserted directly.

**It waits for the lifecycle reconciler before finishing.** For the first minute after seeding, a freeze that is already blocking deployments still reads `SCHEDULED` — correct, because policy decides from the timestamps and not the status column (`D-13`), and impossible to explain on camera. The script polls until the screens agree, and one restriction is deliberately given a two-minute window so the dashboard's recently-completed section is filled by a real transition rather than a pre-completed row.

It refuses to run twice rather than silently doubling the demo.

**It also fixes an ordinary gap:** an empty database has no users at all, so `POST /api/dev/token` returns `404` and nobody can sign in. A fresh checkout was unusable without hand-written SQL; this makes `docker compose down -v` a safe thing to do.

The walkthrough carries a **what not to say** section. The claim to avoid is *"FreezeHub prevents deployments during a freeze"* — it does not and cannot, since enforcement lives in the customer's pipeline. A technical buyer will test that claim, and being caught overclaiming costs the deal. The honest position is stronger anyway: no agent, no credentials into their repositories, and no blast radius.

## Milestone 7 — Deployment Visibility

Turns FreezeHub from "we announced the freeze and recorded the decision" into "here is every attempt to deploy, and what we told each one". The difference matters commercially: today a `BLOCK` vanishes the instant it is returned, and nobody can answer *"did anyone try to ship during Black Friday?"*

### FZ-070 — Deployment Check Record
**Status:** DONE

Record every policy evaluation — not just the refusals `FZ-060` records — with the metadata needed to say who tried what.

**Its own table, not `audit_event`.** `FZ-060` excluded ordinary evaluations deliberately: one happens per deployment and they would bury the administrative trail they sat in. That reasoning is unchanged, so this is a separate table with its own volume profile, retention and reader.

**"Checks", never "deployments".** What FreezeHub observes is a question, not an outcome — a pipeline can be told `ALLOW` and then fail for unrelated reasons, or be told `BLOCK` and deploy anyway. Naming these deployments would be a lie that surfaces during exactly the audit the feature exists to serve. Reporting the outcome afterwards is a possible later addition; it is not this.

The valuable metadata — who, which commit, which pipeline run — reaches FreezeHub only if the caller sends it, so the request gains three **optional** fields (`actor`, `reference`, `source`) and `freeze-check.sh` fills them from whatever the CI system exposes. Optional because not every runner has them, and because an existing pipeline must keep working untouched.

Matched restrictions are stored **denormalised** (id, name, level): the record must show what was true at check time, and a restriction can be renamed afterwards.

**Retention: one year, configurable per organization.** Matches the window most compliance regimes assume and lets a regulated customer keep more. A scheduled purge, following the lifecycle reconciler's pattern.

Acceptance:

- Every evaluation is recorded with its decision, and why it was blocked.
- Optional caller metadata is stored when supplied and absent when not; a request without it still succeeds.
- Records are readable by any member of the organization, newest first, filterable, keyset-paginated.
- Records older than the organization's retention are purged.
- **Data handling:** `actor` and `reference` are customer PII arriving on every deploy. They are never logged, and the retention setting is what bounds them.

### FZ-071 — Deployment Console
**Status:** DONE

`/deployment-checks`, open to **any member** rather than administrators only — it is where a team looks to see whether their own deployment got through, and making them ask an administrator would defeat the point.

Each row answers who tried what: application → environment, the person (from `actor`, falling back to the credential when the pipeline did not say), a truncated commit reference, when, and a link to the run. A refusal names **what refused it** — the hard freezes only, since an advisory that merely rode along did not stop anything and naming it would be wrong.

**The unregistered case reads differently from a real freeze**, deliberately: one is the product working, the other is a pipeline misconfigured or somebody trying a misspelling to get through (`D-14`).

**The page says in words that these are questions, not deployments**, and there is a test for that sentence. Decision `D-19` has to reach the person reading the screen, not just whoever reads the code — believing these are deployments would mislead in exactly the audit the screen exists to serve.

Filter state lives in the URL, so *"everything we refused"* is a link someone can send. Paging is by cursor, and "load older" appears only when a page came back full — otherwise it would be a button that does nothing.

## Milestone 8 — Commercial Onboarding

Everything before this milestone assumed the customer already existed. There is no way to acquire one without a developer: `06-security.md` provisions the first Administrator out-of-band, and `scripts/seed-demo.sh` inserts that row with raw SQL because the API refuses to.

`docs/11-commercial.md` is the specification for this milestone. It is the source of truth for pricing, plans, signup and billing; entries here say what to build, not why.

**Order.** `FZ-081` first — everything else reads the subscription it creates. `FZ-087` before anything unauthenticated is exposed. `FZ-082` cannot ship before `FZ-046` (`OI-2`): self-serve signup creates a Cognito identity, and the only `IdentityProvider` today is a `@Profile("local")` fake.

```text
FZ-081 ── FZ-087 ── FZ-082 ── FZ-083 ── FZ-084 ── FZ-085 ── FZ-086
              (FZ-046 required before FZ-082)
```

### FZ-080 — Commercial Model
**Status:** DONE

Specification only, no code: `docs/11-commercial.md`, plus `D-20`–`D-23`.

Four decisions were the user's to make and were made: hybrid go-to-market (self-serve trial alongside sales-assisted annual), **per registered application** as the pricing metric, Stripe Checkout for payment, and a 14-day full-feature trial with no card.

The reasoning that shaped the rest: **seats and evaluations are both the wrong metric.** Seats charge the customer for telling people about a freeze, which is the product. Evaluations tax calling the Policy API on every deploy, which is how a freeze is enforced at all — a customer optimising that bill would deploy past a freeze. Applications are what scope is built on, are already visible in the product, and cannot be gamed because `D-14` blocks unregistered applications outright.

### FZ-081 — Plans and Subscription State
**Status:** DONE

One `subscription` row per organization, and the plan limits that read from it.

Plans are **an enum with limits as code constants**, not a table. Limits are product decisions deployed with the code; a table invites per-customer edits that then contradict the pricing page. Enterprise is the exception and gets nullable override columns on the subscription row rather than a second mechanism.

Limits are enforced **on creation only** (`D-22`). A downgrade never deletes anything.

Acceptance:

- Every organization has exactly one subscription; creating an organization creates it.
- Creating a resource beyond the plan's limit is refused with `402` and a Problem Details body naming the plan, the limit, and the current count.
- A downgrade below current usage keeps every existing resource and refuses only the next creation.
- Trial expiry moves the subscription to `SUSPENDED`; a scheduled job does this, following the lifecycle reconciler's pattern.
- **A suspended organization's `/api/policy/evaluate` answers are byte-for-byte what they were before suspension** — same decision, same matched restrictions. There is a test for this, because it is the rule most likely to be broken by a later change (`D-21`).
- Suspension makes the human API read-only and stops notifications.
- Subscription changes are audited.

**Two things this story decided that the entry did not anticipate.**

*A missing subscription grants everything rather than nothing.* The row is unique and not null, every organization predating billing was backfilled, and signup will create one — so absence is a defect, and it is logged as one. But refusing on absence would make a customer's API read-only because of a bug in **our** billing data, which is exactly what `D-21` refuses to do everywhere else. It fails towards not billing, which is a conversation, rather than towards not working, which is an outage.

*The write guard is an interceptor, not a filter.* A filter throws outside the DispatcherServlet, so `@RestControllerAdvice` never sees it and a refusal would arrive as a generic 500 rather than the one error shape the API promises. `FZ-052` paid for that lesson once already with the `/error` forward.

Notifications are suppressed at **enqueue**, not at delivery. Skipping them later would leave rows `PENDING` for ever and flood the customer with stale announcements the moment they pay — "starting soon" about a freeze that ended three weeks ago.

### FZ-087 — Request Rate Limiting
**Status:** DONE · **Resolves:** `OI-11`

There is no rate limiting anywhere in the codebase. Today that is defensible: `/actuator/health` is the only endpoint reachable without a credential. `FZ-082` and `FZ-083` end that, and an unauthenticated endpoint that creates a Cognito user and sends an email is not something to expose without a limit.

Per-IP, in-application, fixed window — not a distributed limiter, which `CLAUDE.md` §4 excludes and which one backend instance does not need.

Acceptance:

- Signup and demo-request endpoints are limited per IP.
- Exceeding the limit returns `429` as Problem Details, with `Retry-After`.
- The limit is configuration, not a constant.
- Authenticated endpoints are unaffected.

**The part the entry missed entirely: who "one client" is.**

Nothing in the codebase configured forwarded headers. Behind the load balancer (`FZ-063`) that means `getRemoteAddr()` returns the balancer, so a per-IP limiter would put every customer in one bucket and refuse them all together — a limiter that is worse than none.

Setting `server.forward-headers-strategy: framework` fixes that, and introduces the opposite hazard: anywhere without a trusted proxy in front, a caller can send their own `X-Forwarded-For` and choose their bucket, or fill somebody else's.

**A YAML detail made that live in local development too.** A profile document *merges* with the default one rather than replacing it, so the deployed value applied under `local` as well. The local profile now sets `none` explicitly. This was found by a test failing, not by reading the file — and both halves are now covered: reading the header in the interceptor, and dropping the `none`, each fail the same test.

The interceptor uses `getRemoteAddr()` and never reads the header itself. That looks like an oversight and is the whole defence.

### FZ-082 — Self-Serve Signup
**Status:** TODO · **Blocked by:** `FZ-046` (`OI-2`)

`POST /api/signup` — the first unauthenticated write endpoint in the product. Creates the organization, its first Administrator, its Cognito identity and its trial subscription in one transaction.

**The response never varies.** `202 Accepted`, same body, whether the organization was created or the email is already in use. Anything else turns signup into a customer-enumeration oracle — the same reasoning that makes a cross-tenant resource `404` rather than `403`.

Acceptance:

- A new company signs up, receives the Cognito temporary password, signs in, and lands in an active 14-day trial.
- A duplicate email produces the identical `202` and creates nothing.
- The organization is `PENDING_VERIFICATION` until first successful sign-in.
- Organizations still unverified after 7 days are purged, including the Cognito identity.
- Free-mail addresses are accepted (`11-commercial.md` §4).
- A failure at any step leaves nothing behind — no orphan organization, no orphan Cognito user.

### FZ-083 — Demo Requests
**Status:** DONE

`POST /api/demo-requests`, unauthenticated, plus the internal notification that a request arrived.

~~**Reuses the notification module** rather than sending mail directly: retries, backoff and a dead-letter state already exist and are tested (`FZ-040`–`FZ-044`). The only new thing is a destination owned by FreezeHub rather than by a customer.~~

**That was wrong, and the code said so.** A `Notification` requires a non-null `organizationId` **and** `restrictionId`, and `NotificationSender.send` takes a `ChangeRestriction`. A demo request has none of the three. Reusing the module would have meant changing the port signature and rippling through the email and webhook senders and their tests — to carry one message that goes to us rather than to a customer.

What *is* reused is the part worth reusing: `RetryPolicy`, a pure function of attempt count with no coupling at all. Same backoff, same give-up point, no second schedule to drift. The rest is a small outbox of its own — four columns on the row.

**The lead is the row, not the message.** The request is stored before anything is sent, so a Slack outage or an unconfigured webhook cannot lose it. That is also what makes it acceptable to stop retrying after six attempts rather than for ever.

Acceptance:

- A request is stored with name, work email, company, team size and message.
- A Slack notification reaches FreezeHub's own workspace, and a delivery failure is retried rather than lost.
- Requests carry a status (`NEW`, `CONTACTED`, `CONVERTED`, `DECLINED`) and, once converted, the organization they became.
- Storage is not tenant-scoped — a demo request belongs to no organization yet, which makes it the one table outside the tenant boundary. It is read by operators, never by the tenant API.

### FZ-084 — Stripe Checkout and Subscription Lifecycle
**Status:** DONE

Checkout sessions, portal sessions, and the webhook that is the only thing allowed to change entitlement.

`POST /api/webhooks/stripe` gets **its own security chain**, matching `/api/webhooks/stripe/**`, authenticated by `Stripe-Signature` — HMAC over timestamp and body, the same construction as `D-2` pointed inward. No CORS.

Acceptance:

- An administrator reaches Stripe Checkout and returns to an `ACTIVE` subscription on the plan they bought.
- **Entitlement changes only from a signature-verified webhook** — never from the Checkout redirect, which is a browser navigation anyone can forge. There is a test that forges the redirect and proves it grants nothing.
- An invalid or missing signature is `401`.
- A redelivered event is ignored: every processed `event_id` is stored.
- `invoice.payment_failed` moves the subscription to `PAST_DUE` and notifies the administrator.
~~- The Stripe secret key and endpoint signing secret are held the way destination credentials are (`D-3`), never in configuration in plaintext.~~

**That criterion could not be implemented as written, and the difference is not cosmetic.** `D-3` encrypts values that live in database rows and belong to tenants, using a key supplied to the application. These are FreezeHub's own credentials and they *are* what the application is configured with — encrypting them would need a key, which would have to be configured, which is the same problem again.

They come from the environment, populated from Secrets Manager at deploy time. Nothing is committed, nothing is defaulted, nothing is logged, and absent configuration disables billing rather than starting with a blank key that would fail on the first customer instead of on startup.

**Four defects found by the tests, all in code that looked right:**

- A null `Stripe-Signature` header made the SDK throw `NullPointerException`, surfacing as `500` — telling a caller the server broke when their delivery was simply unsigned.
- `received_at` was populated in `@PrePersist`, which does not fire dependably: the id is assigned rather than generated, so Spring Data treats `save()` as a merge.
- Idempotency by catching the constraint violation does not work inside a transaction — the violation has already marked it rollback-only, so the request fails anyway as an `UnexpectedRollbackException`. It is now a check first, with the primary key as the real guarantee: a genuine race fails one request, and Stripe's redelivery is absorbed by the check.
- `getDataObjectDeserializer().getObject()` returns empty whenever the event's API version differs from the SDK's — **which happens in production every time an account's version and the library drift apart**, not only in tests. Silently doing nothing there means a customer pays and is never activated. The documented escape hatch is used, and only a genuinely unreadable payload is skipped.

### FZ-085 — Billing and Plan UI
**Status:** DONE · **Resolves:** `OI-14`

A Billing section in Settings, Administrator-only: current plan, usage against each limit, trial days remaining, and the buttons that open Stripe.

The trial banner is shown to **every member** in the last 5 days, not only administrators. The person who notices a trial ending is rarely the person who signs.

Acceptance:

- Usage against limits is visible before a limit is hit, not only when a creation is refused.
- A `402` refusal renders as the limit it hit, with the upgrade path, never as a generic error.
- Nothing in the UI decides entitlement — every limit shown comes from the backend.

**Nothing exposed subscription state**, so this story started in the backend: `GET /api/billing/subscription` returns the plan, its limits, the counts, and the trial countdown. Deliberately **not** administrator-only, unlike the rest of `/api/billing` — the banner has to reach everyone, and a member who cannot see why a creation was refused files a bug instead of asking their administrator to upgrade.

The counts are the same ones the limit checks use. A usage bar that disagrees with the code that actually refuses is worse than no usage bar.

**A `402` is composed into a sentence where the error is built**, not at each call site, so every screen that already renders `error.message` gets "Your STARTER plan allows 10 applications, and you are using 10. Upgrade under Settings → Billing" — and no future screen can forget to.

**Two mistakes of mine, both caught by tests:**

- Making `deploymentCheckRetentionDays` a required field turned `PATCH` into a `PUT` and broke three existing callers with a `400`. It is optional and applied only when sent, which is what PATCH means.
- `percentUsed` and `atLimit` were plain accessors on a record. Jackson serialises components, so they never reached the JSON and the usage bar had no percentage in it. They are components now.

**`OI-14` is closed.** Retention is settable and capped by plan, so the priced lever is no longer fiction: Starter is refused at 365 days with a `402` naming the cap, and Enterprise may keep seven years.

### FZ-086 — Operator Provisioning
**Status:** DONE

`scripts/provision-organization.sh`, following `seed-demo.sh`: create an organization on an agreed plan, invite its first Administrator, mark the originating demo request converted.

**Deliberately a script and not an admin console** (`D-23`). An in-product principal that can act across tenants negates the invariant the whole security model rests on.

Acceptance:

- One command provisions a named organization on a named plan and invites its administrator.
- It refuses to run twice for the same company.
- It goes through the API wherever the API allows it, matching `seed-demo.sh`.
- The operational prerequisites — which credentials, which access — are written down, because whoever runs this is not necessarily whoever wrote it.

**Two guards, because there are two ways to duplicate a customer.** A name already in use, and an email that already belongs to somebody. The second matters more: a person exists once, and inviting them into a second organization is a different feature this product does not have.

**One SQL statement, in a transaction.** An organization with no administrator is unreachable and one with no subscription is unbilled, so a part-way failure has to leave nothing rather than either of those.

**It reads back through the API before claiming success**, rather than trusting its own inserts — that is the only check proving the organization resolves from a credential, which every later request depends on.

**`--applications` is refused on anything but ENTERPRISE.** Every other plan's limit is a published number; overriding one here would mean a customer paying for Starter with a limit nobody can look up, and the pricing page quietly becoming untrue. `TRIAL` is not provisionable either — a trial is something an organization starts for itself at signup, not something sales hands out.

**A bug found by running it, not by reading it:** the closing summary used an unquoted heredoc, so a backtick-quoted `local` was executed as a command. The word vanished from the operator's instructions and `local: can only be used in a function` leaked into them.

**What it honestly cannot do.** Outside the `local` profile there is no real `IdentityProvider` (`OI-2`), so no Cognito user is created and no invitation is sent — the customer cannot sign in. The script says so in its closing summary rather than reporting a success that is only half true.

### FZ-088 — Organization Domain Claiming
**Status:** DEFERRED · **Owns:** `OI-12`

Two colleagues signing up separately create two unrelated organizations, and nothing merges them. The honest MVP answer is that support fixes it by hand.

Deferred rather than scheduled: the fix worth building depends on whether the common case is "join the existing organization automatically" (fast, and wrong for a contractor at a client's domain) or "request access from an administrator" (correct, and more machinery). One real occurrence answers that; guessing first does not.

## Milestone 9 — Repository and CI Connectors

`FZ-053` shipped a gate that works and that every customer has to vendor into their own repository by hand. This milestone removes that friction without changing what FreezeHub can reach.

`docs/12-connectors.md` is the specification. `00-product.md`'s deferred list excludes "native GitHub/GitLab/Jenkins/Argo CD integrations" from the MVP; **this milestone promotes the non-native half of that into scope** — packaging around the existing call — and leaves the native half (an installed app with a write credential) deferred as `FZ-096`.

**Runs before Milestone 8**, by decision: adoption friction blocks usage today, whereas billing blocks revenue from customers who are not yet using the product.

```text
FZ-091 ── FZ-092 ── FZ-093 ── FZ-094 ── FZ-095 ── FZ-097
   │
   └── everything below depends on the canonical script and image it produces
```

### FZ-090 — Connector Strategy
**Status:** DONE

Specification only, no code: `docs/12-connectors.md`, plus `D-24`.

Four connectors were chosen and one was deliberately not. The rule that shapes all of them: **one implementation, four wrappers.** A customer running GitLab in one team and Jenkins in another must get the same answer from the same freeze, and four native implementations would drift until one team deployed during a freeze that stopped another.

### FZ-091 — Connector Runtime and Image
**Status:** DONE

The foundation the other four sit on.

`examples/freeze-check.sh` moves to `connectors/freeze-check.sh` — it stopped being an example the moment it became a shipped artifact. `examples/` stays as the hand-rolled walkthrough for CI systems with no connector, and points at the new location.

`connectors/Dockerfile` builds `ghcr.io/freezehubio/freeze-check` — Alpine, `curl`, `jq`, script on `PATH`, non-root, following `backend/Dockerfile`'s shape. **Required regardless of preference**: an Argo CD PreSync hook is a Kubernetes Job and cannot run without an image.

Acceptance:

- The script's behaviour is unchanged by the move; the existing exit codes and fail-closed rules hold.
- `examples/README.md` and `examples/gitlab-ci.yml` reference the new path and still work.
- The image runs the check with no arguments beyond environment variables, as non-root.
- A test proves the image exits `1` on a blocked decision and `2` on an unreachable server — the two outcomes a broken image would most plausibly turn into `0`.
- The image is built in CI. Publishing it is `FZ-097`.

### FZ-092 — GitHub Actions Connector
**Status:** DONE

A composite action at `connectors/action.yml`, usable as `uses: freezehubio/freeze-check@v1`.

**Moved to the `connectors/` root by `FZ-097`**, together with the GitLab template, so that this directory *is* the published repository's layout. Marketplace requires `action.yml` at a repository root; publishing now copies rather than rewrites the script path, so what a customer runs is what the tests ran.

Composite rather than a Docker action: it runs on the runner's own `curl`/`jq`, so it costs no image pull on the platform where most usage will be, and it works on self-hosted runners with no Docker.

Acceptance:

- Inputs `url`, `api-key`, `application`, `environment`, `on-error`, `timeout`, mapping onto the script's variables and adding nothing.
- Blocked fails the step; not-evaluated fails the step; the two are distinguishable in the log.
- **No input downgrades a freeze to a warning** (`12-connectors.md` §4).
- `actor`, `reference` and `source` are populated from the GitHub context, as the script already does.
- Exercised by a workflow in `.github/workflows/` that runs the action against a stubbed endpoint — the action must be proven to fail, not only to pass.

### FZ-093 — GitLab CI Connector
**Status:** DONE

A CI/CD component at `connectors/templates/freeze-check.yml` (moved there by `FZ-097`), running the `FZ-091` image so no pipeline installs `curl` and `jq` on every run.

**Kept as a tested reference implementation, not published** (`D-26`): `component:` resolves against a repository the customer can read. The GitLab guideline in `connectors/README.md` is what customers actually use, and it is the same job with the image inlined.

Acceptance:

- A customer adds the check with an `include:` and one variable block.
- Defaults to running only on the deploying branch; asking on every feature branch would fail merge-request pipelines during a freeze, which is not the point.
- The documented wiring uses `needs:` so a failed check prevents the deploy job from being created, and says explicitly why `allow_failure: true` must not be added.

### FZ-094 — Jenkins Connector
**Status:** SUPERSEDED by `FZ-097`

A Jenkins shared library needs its own repository for Jenkins to load it, the same constraint that stopped the Action and the component being installable (`D-26`). The Jenkins guideline in `connectors/README.md` delivers the outcome — `withCredentials` around a `docker run` — without a second repository to maintain. Reopen if a customer needs a library rather than a snippet.

The original plan below is kept for the reasoning it carries, not as work to do.


A shared library at `connectors/jenkins/`: `vars/freezeCheck.groovy` plus the script as a library resource.

**This is the one connector that carries a copy of the script**, because Jenkins loads library resources from within the library itself. `12-connectors.md` §2 requires the copy to be byte-identical to the canonical file, and a check enforces it — a drifted copy is exactly the failure the one-implementation rule exists to prevent.

Acceptance:

- `freezeCheck(application: 'payments-api', environment: 'production')` gates a stage.
- A blocked check fails the build; `catchError` is not used anywhere in the step.
- A test fails if the resource copy and `connectors/freeze-check.sh` differ by one byte.
- The API key is read from Jenkins credentials, never from a pipeline literal, and does not appear in the build log.

### FZ-095 — Argo CD Connector
**Status:** SUPERSEDED by `FZ-097`

An Argo CD PreSync hook is a manifest a customer copies, not something installed, so it is a guideline rather than an artifact. `connectors/README.md` carries it, including the two things that surprise people: `backoffLimit: 0`, because a freeze is not a transient error, and the fact that Argo retries the sync on its own schedule. Reopen if a maintained, tested manifest is wanted rather than a documented one.

The original plan below is kept for the reasoning it carries, not as work to do.


A PreSync hook manifest at `connectors/argocd/`, running the `FZ-091` image.

**Different in kind from the other three.** They gate a step in a pipeline; this gates a *sync*, and by the time Argo syncs, the change is already committed and merged. With auto-sync on, the freeze is the only thing between a merged commit and production — the strongest form of the product, and the most surprising.

Acceptance:

- Application and environment are read from annotations on the Argo `Application`; a missing annotation fails the hook rather than defaulting to something.
- A blocked check fails the sync and leaves the Application `OutOfSync`.
- The API key comes from a Kubernetes `Secret`, never from the manifest.
- **The documentation states that Argo will retry the failed sync on its own schedule**, so repeated failures during a freeze are expected and are not an incident. Nobody should learn this from an alert at 3am.

### FZ-097 — Integration Guidelines
**Status:** DONE · **Resolves the usable half of** `OI-13`

Taken out of order, ahead of `FZ-094` and `FZ-095`, because two connectors existed and **neither could be installed by anyone**.

The approach changed during this story, and the change came from asking what customers actually run. Most CI systems run containers, so **the image is the connector** and one published artifact covers nearly the whole market (`D-26`). What was nearly built instead — a second public repository, an assemble step, a cross-repo token and a force-pushed release — was machinery to maintain before there is a customer, bought with about two lines of YAML per pipeline.

`connectors/README.md` is now the integration guide: GitHub Actions, GitLab CI, Jenkins, Argo CD, and a generic form for everything else. Each is the same program with the same environment variables, which is the point.

**`connectors/` was restructured** so `action.yml` and `freeze-check.sh` are siblings — done for a publishing layout that is no longer needed, kept because the action's script path is now identical wherever it runs.

Acceptance:

- A guideline per CI system, each one runnable, each naming what it deliberately does not do.
- Argo CD's guideline states that **Argo retries a failed sync on its own schedule**, so repeated failures during a freeze are expected and are not an incident. Nobody should learn that from an alert at 3am.
- The Jenkins guideline uses `withCredentials`, never a pipeline literal, so the key stays out of the build log.
- The reference implementations stay tested, and the documentation **does not offer them as installable** — they are not, while the repository is private.

### FZ-099 — Publish the Connector Image
**Status:** TODO · **Owner of** `OI-13` · **Deliberately last**

`.github/workflows/publish-connectors.yml` builds `linux/amd64` and `linux/arm64`, runs the connector tests first, tags the exact version and moves `v1`, and has a dry-run mode. It refuses a version that is not `vN.N.N`, and there is no `latest` tag — a moving `latest` in a deploy gate is how a pipeline changes behaviour on a day nobody touched it.

**Not executed, and cannot be.** Two prerequisites, both human decisions rather than a workflow's to make:

1. A GitHub organization **`freezehubio`**. The image is named for it because a personal username in a customer's deploy pipeline undercuts a product sold to companies — and renaming later breaks every pipeline using it.
2. A secret **`CONNECTOR_PUBLISH_TOKEN`** with `packages: write` on that organization. `GITHUB_TOKEN` cannot write to another owner's package namespace.

Then one thing that is easy to miss: **GHCR package visibility is set on the package, not inherited from the repository.** It must be set to public after the first push or customers get `denied` on pull. That property is also what lets a public image ship from a private source tree at all.

Until this runs, every guideline in `connectors/README.md` names an image that does not exist, and the README says so.

### FZ-096 — GitHub App and Required Checks
**Status:** DEFERRED · **Decision required before scheduling**

A GitHub App that posts a commit status, so branch protection can make FreezeHub a **required check** — the one thing that turns a `HARD_FREEZE` from voluntary into enforced, and the strongest thing the product could offer.

Deferred deliberately, not for effort. It **inverts the trust direction**: FreezeHub would hold an installation token that can write to the customer's repository metadata, ingest their webhooks, and appear in their audit log. Every sentence in `10-demo.md` about having no credentials into their repositories and no blast radius stops being true, and the answer to the first question a security review asks changes.

That trade may well be worth making — it is the natural Scale/Enterprise differentiator, and `11-commercial.md` has nowhere else to put an upsell of that weight. It is not something to arrive at as a side effect of "we added GitHub support", which is why it is a separate decision with its own security review rather than a task inside `FZ-092`.

## Milestone 10 — Defects Found After Milestone 9

### FZ-098 — Timestamp Precision in the Audit Trail
**Status:** DONE · **Found by:** the first CI run, `FZ-092`

`AuditTrailTest.recordsNothingWhenAnUpdateChangedNothing` failed on Linux and passed on macOS. The cause was not the test.

PostgreSQL `TIMESTAMPTZ` stores **microseconds**. `Instant` carries **nanoseconds**, and on Linux `Instant.now()` actually populates them. A client sending `2026-09-06T07:03:40.000000123Z` therefore has its value silently truncated on the way to the database — and on the next update, the incoming nanosecond value is compared against the stored microsecond one, they differ, and an audit event is written saying the freeze window moved:

```json
{"startsAt":{"from":"2026-09-06T07:03:40Z","to":"2026-09-06T07:03:40.000000123Z"}}
```

Three things wrong with that entry, in increasing order of seriousness:

1. Nothing changed. The client sent back exactly what it sent before.
2. It appears on **every** update, so genuine changes arrive buried in noise.
3. **The `to` value was never persisted.** The database truncates it straight back to `from`. The audit trail — whose entire purpose is answering "who changed this freeze, and to what" — records an after-state that never existed.

The fix truncates user-supplied instants to microseconds as they enter the aggregate, so the entity's state is always what the database will hold and a comparison between them is meaningful (`D-25`).

Acceptance:

- A no-op update with nanosecond-precision timestamps records nothing.
- A real change to the window is still recorded, with both values at storable precision.
- The API returns what was stored, so a client that sends nanoseconds is told plainly what it got.
- The macOS/Linux split is gone: the regression test supplies nanoseconds explicitly rather than depending on the host clock's precision.

## Milestone 11 — Visual Identity

### FZ-100 — Industry Design System
**Status:** DONE

A design hand-off in `design_handoff_industry_theme/` puts the **Industry** system behind the frontend: steel-blue on a light technical ground, Barlow Condensed over Barlow, square corners, hairline frames with "+" registration marks, one solid accent object per screen.

**The architecture is the part worth keeping.** The repo already centralises tokens as `--fh-*`, so Industry's token block is pasted above them and the aliases are redefined on top — every existing CSS Module inherits the new look with no edit at all. Component classes (`.btn`, `.card`, `.tag`, `.blueprint`) arrive as one global sheet; the modules keep only layout. **Look comes from the global layer, layout from the module**, and that split is what the next screen should follow.

**Three defects in the change set, found before applying it:**

1. **`main.tsx` would not compile.** It changed `import App from './app/App.tsx'` to a named import, but `App` is a default export. Only the one `industry.css` import its README describes was taken.
2. **Losing red is bigger than its README weighed.** It presents the mono decision as being about badges. `--fh-danger` carries **22 declarations across nine stylesheets** — every validation error and every `.actionError` in the product, most of them in routes the change set does not touch. Decided: **red stays** for errors and blocking states, as a documented functional exception. It is state, not decoration, and in a tool whose job is refusing deployments a refusal rendered in ordinary chrome is a legibility regression.
3. `.cardBlocking` shipped in the CSS but was never wired. It now keys on `HARD_FREEZE` **and** `ACTIVE` — a scheduled freeze has not stopped anything yet, and framing it the same way cries wolf.

**A claim of mine that was wrong, corrected:** I reported that `--fh-surface: transparent` would break two unported stylesheets. It does not. My grep used `fh-surface\b`, which also matches `--fh-surface-strong` — a real neutral tint. Exactly one rule uses `var(--fh-surface)`, a button that already has a border, where transparent is correct Industry styling. The hand-off's claim was right.

**Dark mode is dropped**, as the hand-off intends: Industry ships no dark ramp, so `color-scheme` is pinned to light. Reinstating it means choosing dark values for every role — design work, not a port (`OI-17`).

Acceptance:

- Tests pass **untouched** — 117 of them, asserting text and roles rather than class names. That was the hand-off's own proposed signal that this is style-only, and it holds.
- Every `styles.X` reference in the six rewritten modules resolves, and no class ships unused.
- Errors remain red everywhere they were red.

**Most of the app is unported.** Restriction list, detail, create, checks, settings, catalog and audit still carry the old layout on the new tokens — they inherit the palette and type but not the Industry layout. Two of those carry product changes rather than restyles and must not be smuggled in as design work: the create form replaces the native `<select multiple>` scope pickers with chip toggles (`05-frontend.md` accepted the native control knowingly), and the checks screen wants a 14-day bar chart needing a per-day aggregate **the API does not expose**.

### FZ-101 — Broadsheet Design System
**Status:** DONE · **Replaces:** `FZ-100`

The look and feel changed direction. Broadsheet is newsprint set for the web — near-black Source Serif 4 on paper, cyan used small like spot colour — and it **inverts** Industry rather than varying it: *"do not structure the page with rules, borders or boxes"* and *"do not introduce a sans-serif for UI chrome; the serif is the chrome"* are both direct contradictions of what `FZ-100` shipped.

`Blueprint.tsx` is deleted with it. Corner registration marks are Industry's signature, and Broadsheet forbids the frames they decorate.

**Unlike `FZ-100` there was no change set** — Broadsheet ships the system and the mockups, not ready-to-commit modules. This is a port, not a copy, so more of it is judgement.

**Fully mono, decided.** Broadsheet carries cyan and magenta but no red, and this port distinguishes a refusal by weight rather than hue. That puts real load on the rest: a hard freeze is a filled tag and an advisory an outlined one; a refused deployment check is the heaviest ink on the sheet reversed out to paper; error text is full-strength ink at heading weight. Wherever that distinction gets flattened, a refusal stops reading as one — the failure to watch for in a product whose job is refusing deployments. Reversing it is one line, noted in `index.css`.

**Three things found that no theme could have fixed:**

1. **Audit and Deployment Checks were never on the token system at all.** Both carried a Tailwind-ish palette inline — `#4b5563`, `#1d4ed8`, `#e5e7eb`. That, not "old layout on new tokens", is why neither theme reached them. Converted by role rather than by nearest colour, so they follow the system from here.
2. **`--color-warning`, `--color-warning-surface` and `--color-warning-text` were never defined.** The one-time API key reveal had always rendered its amber fallbacks, unreachable by any theme. It now carries its loudness in ink.
3. **A raw `#9ca3af`** in a button border — exactly what the system's own adherence config forbids.

Every colour in every module now comes from a token; the only hex left in the frontend is the token definitions themselves.

**Radius was normalised** onto `--radius-md`. The modules carried five different values predating either theme.

Acceptance:

- 117 frontend tests pass untouched; every `styles.X` reference resolves; `tsc`, `oxlint` and `vite build` clean.

**Two things in the mockups deliberately NOT built**, because they are product changes wearing design clothes and belong to their own stories:

- **Create restriction** — the mock replaces the native `<select multiple>` scope pickers with chip toggles and adds a live "this will match…" summary. `05-frontend.md` accepted the native control knowingly.
- **Deployment checks** — the mock adds a 14-day allowed/refused bar chart. That needs a per-day aggregate **the API does not expose**.

**Also not built: the landing page.** The mockups include one, but there is no public marketing route — Milestone 8 deliberately deferred the public site (`FZ-080`), so there is nothing for it to live in yet.

**Spacing is still literal.** 120 padding/gap/margin declarations use rem values rather than `--space-*`, so Broadsheet's 1.25× density reaches the app only through the type scale. Converting them is a large mechanical change with real breakage risk and is left for its own story (`OI-18`).

## Developer Tooling

### FZ-102 — Test Accounts Helper
**Status:** DONE

`scripts/test-users.sh` — who can sign in locally, what each account is for, and what cannot be tested with the accounts that exist.

**Reads the database, not a document.** A list of test accounts written down is wrong the first time anybody seeds, provisions or resets, and this question had been asked three times.

**It names the gap, which is the useful half.** With no `MEMBER` account the product cannot be checked at all from a non-administrator's side — the trial banner shown to every member (`FZ-085`), the member-visible billing read, and how a `402` looks to someone who cannot fix it. `--add-member` closes that, and the difference is real: a member gets `200` on `GET /api/billing/subscription` and `403` on `POST /api/api-keys`, verified live.

It also reports whether more than one organization exists, since cross-tenant leakage cannot be tested against a single tenant.

Acceptance:

- Lists every account with its organization, plan, status and trial end.
- States what is untestable with the current set rather than only what is present.
- Needs Postgres but not the backend, so it answers even when the app will not start.
