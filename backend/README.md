# backend

Java 21 + Spring Boot modular monolith.

Bootstrapped in `FZ-002`; PostgreSQL + Liquibase added in `FZ-004`; `organization` module (tenant boundary persistence) added in `FZ-011`; human authentication added in `FZ-012`; invite endpoint added in `FZ-016`; `catalog` module (Teams) added in `FZ-013`; Applications + team association added in `FZ-014`; Environments added in `FZ-015` — completing Milestone 1; `restriction` module (create change restriction) added in `FZ-020`. See `../docs/02-architecture.md` for the target module structure and stack, `../docs/03-data-model.md` for the schema, `../docs/06-security.md` for the auth approach, and `../CLAUDE.md` §8 for commands.

## API

- `POST /api/teams`, `GET /api/teams`, `GET /api/teams/{id}`, `PATCH /api/teams/{id}`, `DELETE /api/teams/{id}` — tenant-scoped team management, any authenticated org member (no role restriction, per `06-security.md`). A team belonging to another organization returns `404`, not `403` — existence isn't revealed cross-tenant. Duplicate name within an org returns `409`.
- `POST /api/applications`, `GET /api/applications`, `GET /api/applications/{id}`, `PATCH /api/applications/{id}`, `DELETE /api/applications/{id}` — same tenant-isolation/authorization rules as Teams. Response includes `teamIds` (currently associated teams).
- `PUT /api/applications/{id}/teams/{teamId}` / `DELETE /api/applications/{id}/teams/{teamId}` — associate/disassociate a team, both idempotent. `404` if either the application or the team doesn't exist in the caller's organization. Deleting a Team or Application cascades the association at the DB level (`ON DELETE CASCADE` on `team_application`) — no manual cleanup needed, and no FK-violation error on delete.
- `POST /api/environments`, `GET /api/environments`, `GET /api/environments/{id}`, `PATCH /api/environments/{id}`, `DELETE /api/environments/{id}` — same tenant-isolation/authorization rules as Teams. No associations (unlike Applications).
- `POST /api/restrictions` — create a deployment restriction (`FZ-020`). Always created as `status=SCHEDULED`, `type=DEPLOYMENT_FREEZE`; both are server-controlled and not accepted from the client. `name`, `reason`, `level`, `startsAt`, `endsAt` are required (`description` optional). Scope is three optional dimensions (`teamIds`, `applicationIds`, `environmentIds`) of which at least one must be non-empty; ids not owned by the caller's organization return `404`. Reading, updating, cancelling and lifecycle transitions are `FZ-021`–`FZ-025`.

```jsonc
POST /api/restrictions
{
  "name": "Black Friday Freeze",
  "description": "No production deploys during peak trading.",  // optional
  "reason": "Revenue-critical period",                          // required
  "level": "HARD_FREEZE",                                       // or ADVISORY
  "startsAt": "2026-11-27T00:00:00Z",
  "endsAt":   "2026-12-02T00:00:00Z",
  "scope": { "teamIds": [1], "applicationIds": [2, 3], "environmentIds": [4] }
}
```

- `POST /api/restrictions/{id}/cancel` — cancel a `SCHEDULED` or `ACTIVE` restriction (`FZ-024`). An action rather than `DELETE`: the restriction is **kept** with status `CANCELLED`, since what was communicated to engineers actually happened. `409` if `COMPLETED` or already `CANCELLED`; not idempotent. Cancellation is terminal — a cancelled restriction can no longer be updated (`FZ-023` already refuses anything not `SCHEDULED`).
- `PUT /api/restrictions/{id}` — full replacement of a restriction's editable state (`FZ-023`), allowed **only while it is `SCHEDULED`** (`409` otherwise — an active or finished restriction is a record of what happened). Editable: `name`, `description`, `reason`, `level`, `startsAt`, `endsAt`, and all three scope dimensions. Not editable: identity/ownership, `type`, and `status` (see `FZ-024`/`FZ-025`). Same body and same validation as create, so an update cannot produce a state creation would have rejected.
- `GET /api/restrictions/{id}` — one restriction with its full scope (`FZ-022`). Unused scope dimensions come back as empty arrays. `404` for both unknown ids and ids owned by another organization.
- `GET /api/restrictions` — list the caller's organization's restrictions (`FZ-021`), soonest-start-first. `?status=` is optional and repeatable: `?status=SCHEDULED&status=ACTIVE` narrows to those states, omitting it returns all, and an unrecognised value is `400`. Returns a **summary without scope** — scope belongs to the detail representation (`FZ-022`), which also keeps listing to a single query.

Scope matching semantics — OR within a dimension, AND across dimensions, an empty dimension acting as a wildcard — are specified in `../docs/01-domain.md`. `FZ-020` only persists scope; evaluation is `FZ-051`.

## Restriction lifecycle (`FZ-025`)

`SCHEDULED → ACTIVE → COMPLETED` is applied by **reconciliation**, not by a timer holding state: `RestrictionLifecycleService.reconcile(now)` corrects stored statuses from the persisted timestamps with two set-based updates, so it is idempotent and self-healing after any outage. `CANCELLED` matches neither query, which is what makes cancellation prevent future activation.

It runs on startup (`ApplicationReadyEvent`, so a restart catches up immediately rather than waiting for a tick) and then every `freezehub.lifecycle.interval` (default `PT1M`). Set `freezehub.lifecycle.enabled=false` to disable — the test suite does, so it never races the job.

A restriction whose whole window elapsed while the process was down goes straight to `COMPLETED`; it did elapse, and leaving it `SCHEDULED` would be wrong.

> **For policy evaluation (`FZ-051`):** the `status` column is a materialised convenience and may lag by up to one interval. Evaluate from `startsAt`/`endsAt` plus "not `CANCELLED`" — trusting `status` alone could allow a deployment during a freeze whose activation tick had not yet run.

The scope tables reference catalog rows with non-cascading foreign keys, so the database refuses to delete a team/application/environment that a restriction references — deliberately, since cascading would silently shrink a restriction's scope and stop blocking deployments it was created to block. That refusal surfaces as **`409`** with an explanatory message (`FZ-036`).

## Notifications (`FZ-040`)

Lifecycle events are recorded in a database-backed **outbox** (`notification`) rather than published to a broker (`02-architecture.md`). One row is one delivery attempt to one destination, so a lifecycle event fans out to every enabled `integration` for the organization — Slack succeeding while a webhook fails is representable, and retry is per-destination.

The row is written **in the same transaction as the domain change**, which is what makes the intent survive a crash between "restriction activated" and "notification queued". `NotificationOutbox.enqueue` is `Propagation.MANDATORY` so it cannot accidentally be called outside one. Enqueueing is idempotent — the service checks, and a unique constraint on `(restriction, integration, event)` guarantees it — because the lifecycle reconciler is itself idempotent and runs on a timer.

Destinations are configured through `/api/integrations` (`FZ-045`, ADMINISTRATOR-only) — a stored credential is never read back, only a summary that identifies the destination.

**Delivery** (`FZ-041`): `NotificationDispatcher` drains pending rows on a timer (`freezehub.notifications.interval`, default `PT30S`; set `freezehub.notifications.enabled=false` to disable, as the tests do). Each notification is delivered in its own transaction by `NotificationDelivery` — a separate bean because Spring's proxy-based transactions do not apply to a self-invoked `@Transactional` method — so one failing destination cannot roll back deliveries that succeeded beside it.

Channels implement `NotificationSender`: Slack (`FZ-041`), email (`FZ-042`) and generic webhook (`FZ-043`). A channel with no usable sender — email with no from-address, say — defers its notifications indefinitely rather than losing them, and without consuming retry attempts.

**Webhook payload** (`FZ-043`) — a versioned contract, since customer software parses it:

```jsonc
// POST <configured url>   header: X-FreezeHub-Event: ACTIVATED
{
  "version": 1,
  "event": "ACTIVATED",
  "occurredAt": "2026-11-27T14:00:00.123Z",
  "restriction": {
    "id": 23, "name": "Black Friday Freeze", "description": "Peak trading",
    "reason": "Revenue-critical period", "type": "DEPLOYMENT_FREEZE",
    "level": "HARD_FREEZE", "status": "ACTIVE",
    "startsAt": "2028-11-27T14:00:00Z", "endsAt": "2028-12-02T09:30:00Z"
  }
}
```

Scope is intentionally not included: deciding whether a given deployment is affected is the Policy API's job (`FZ-051`), and a consumer re-implementing those rules would drift from them. Treat this event as "something changed, re-check", not as an authorization answer.

> **Webhook requests are not signed** — a receiver cannot currently verify a request came from FreezeHub (`OI-7`).

**Email** requires `FREEZEHUB_NOTIFICATIONS_EMAIL_FROM` plus `spring.mail.*`. Without a from-address the sender is not registered at all and EMAIL notifications defer. SES exposes an SMTP endpoint, so the same settings work locally and when deployed. For local development:

```bash
docker compose --profile dev up -d mailpit    # SMTP on 1025, inbox at http://localhost:8025

FREEZEHUB_NOTIFICATIONS_EMAIL_FROM=freezehub@acme.test \
  SPRING_MAIL_HOST=localhost SPRING_MAIL_PORT=1025 \
  ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

> **Diagnosing a missing announcement:** read `notification.status`, `attempts` and `last_error`. Note that `last_error` deliberately never quotes a destination URL — a Slack webhook URL is a bearer credential.

**Retry** (`FZ-044`): a failed delivery backs off (30 s doubling to a 15 min cap) via `next_attempt_at`, and becomes terminal `FAILED` after six attempts. A deleted destination is abandoned immediately; a disabled destination, or a channel whose adapter is not built yet, is deferred **without** consuming an attempt.

## Error responses

Deliberately-thrown rejections carry their reason in a `message` field:

```jsonc
{ "timestamp": "…", "status": 409, "error": "Conflict",
  "message": "A team with this name already exists", "path": "/api/teams" }
```

Spring omits `message` by default, which silently discarded every reason this API produces. `ApiExceptionHandler` restores it for `ResponseStatusException` only — unexpected exceptions still fall through to Spring's default with no message, so a `500` never leaks internals. The full error contract is `FZ-061`.

## Quick start

```bash
docker compose -f ../docker-compose.yml up -d postgres   # from repo root: docker compose up -d postgres

./mvnw clean verify                                       # build + test (Postgres via Testcontainers, no manual step needed)
./mvnw spring-boot:run -Dspring-boot.run.profiles=local   # run locally (port 8080), connects to the compose Postgres
curl http://localhost:8080/actuator/health
```

Requires Java 21 — `.java-version` pins this via [jenv](https://github.com/jenv/jenv); otherwise set `JAVA_HOME` to a Java 21 JDK.

Requires Docker running for `./mvnw clean verify` (Testcontainers) and for `spring-boot:run` against local Postgres.

## Database

- Connection is configured via `spring.datasource.*` in `src/main/resources/application.yml`, overridable with `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD`. Defaults match `../docker-compose.yml`.
- Migrations live in `src/main/resources/db/changelog/`, run by Liquibase on startup (`db.changelog-master.yaml`).
- Integration tests get a real PostgreSQL via Testcontainers (`src/test/java/com/freezhub/ContainersConfig.java`, `@ServiceConnection`) — see `FreezeHubApplicationTests`.

## Authentication

Every endpoint except `/actuator/health` requires a credential, and there are two of them (`../docs/06-security.md`):

| Caller | Credential | Reaches |
|---|---|---|
| Human (browser) | Cognito JWT, `Authorization: Bearer <token>`, resolved to a `users` row and its `organization_id` | everything except `/api/policy/**` |
| Machine (CI/CD) | API key, `X-API-Key: <key>`, resolved to the organization that owns it | `/api/policy/**` only |

Presenting either one where the other is expected is a `401`. A key leaked from CI therefore cannot touch an organization's data or issue further keys.

- **Local dev and tests always run with the `local` Spring profile active** (`-Dspring-boot.run.profiles=local`, or `@ActiveProfiles("local")` in tests). It self-issues/validates JWTs with a locally-generated key — no AWS dependency. Without it, the app has no `JwtDecoder` bean and **will not start**, since `spring.security.oauth2.resourceserver.jwt.issuer-uri` isn't set locally.
- Any real/deployed environment must set `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` to the real Cognito user pool's issuer URI and must **not** activate the `local` profile.
- Minting a test token: use `com.freezhub.shared.security.TestTokens.forSubject(jwtEncoder, subject)` (test-only helper); see `MeControllerTest` for a full example.

### Signing in during development (`FZ-035`)

No Cognito user pool exists until `FZ-063`, so a browser has no way to obtain a token. Under the `local` profile only, an endpoint mints one for an **existing** user:

```bash
curl -X POST http://localhost:8080/api/dev/token \
  -H 'Content-Type: application/json' -d '{"email":"dev@acme.test"}'
# -> {"token":"eyJ...","userId":1,"organizationId":1,"email":"dev@acme.test","role":"ADMINISTRATOR"}

curl http://localhost:8080/api/me -H "Authorization: Bearer <token>"
```

`404` if no such user (it is a sign-in shortcut, not a way to create identities); `409` if the email exists in more than one organization, since email is unique per organization rather than globally.

**It cannot exist in a deployed environment.** The controller, the `JwtEncoder` it needs, and the filter chain that makes the path reachable without a token are all `@Profile("local")`, and no deployed environment activates that profile — there the path falls through to the main chain and is rejected as unauthenticated. Tests assert that absence. Replaced by the Cognito Hosted UI redirect at `FZ-063`.

### Machine credentials (`FZ-052`)

An Administrator issues an API key for CI. The raw key is returned **once** — only its SHA-256 hash is stored, so it cannot be read back afterwards:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/dev/token \
  -H 'Content-Type: application/json' -d '{"email":"dev@acme.test"}' | jq -r .token)

curl -X POST http://localhost:8080/api/api-keys \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"name":"gitlab-ci"}'
# -> {"id":1,"name":"gitlab-ci","keyPrefix":"fzh_...","key":"fzh_...","createdBy":3,...}

curl -X POST http://localhost:8080/api/policy/evaluate -H "X-API-Key: $KEY" ...
```

`POST /api/api-keys/{id}/revoke` withdraws a key permanently; there is no un-revoke, because a withdrawn key may already be in someone else's hands. Issue a new one instead.

`POST /api/policy/evaluate` (`FZ-051`) is the only endpoint a key reaches. It answers with `ALLOW` or `BLOCK` plus every restriction that matched. Note that an application or environment the catalog does not know about is **blocked**, with the unrecognised name reported — see `../docs/04-api.md`.

### Provisioning a user

An organization's first (`ADMINISTRATOR`) user is provisioned out-of-band, manually:

1. Create the Cognito identity: `aws cognito-idp admin-create-user --user-pool-id <pool-id> --username <email>` (Cognito emails a temporary password).
2. Note the returned `sub` (or look it up via `aws cognito-idp admin-get-user`).
3. Insert the matching row: `INSERT INTO users (organization_id, cognito_subject, email, role) VALUES (<org-id>, '<sub>', '<email>', 'ADMINISTRATOR');`

Every subsequent user is added in-product by an Administrator:

```
POST /api/invites
Authorization: Bearer <token for an ADMINISTRATOR>
Content-Type: application/json

{ "email": "teammate@acme.test", "role": "MEMBER" }
```

`role` is optional (defaults to `MEMBER`). Non-administrators get `403`; re-inviting an email already in the org gets `409`.

**Known gap:** the identity-creation step behind this endpoint (`com.freezhub.shared.security.IdentityProvider`) only has a local fake (`LocalIdentityProvider`, generates a random subject, `local` profile). There is no real Cognito `AdminCreateUser` implementation yet — no Cognito user pool exists to call (that's `FZ-063`). Running without the `local` profile fails to start (no `IdentityProvider` bean), same fail-fast behavior as the JWT decoder above. A real `CognitoIdentityProvider` must be added before this endpoint is used against a deployed environment.
