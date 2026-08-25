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

- `GET /api/restrictions/{id}` — one restriction with its full scope (`FZ-022`). Unused scope dimensions come back as empty arrays. `404` for both unknown ids and ids owned by another organization.
- `GET /api/restrictions` — list the caller's organization's restrictions (`FZ-021`), soonest-start-first. `?status=` is optional and repeatable: `?status=SCHEDULED&status=ACTIVE` narrows to those states, omitting it returns all, and an unrecognised value is `400`. Returns a **summary without scope** — scope belongs to the detail representation (`FZ-022`), which also keeps listing to a single query.

Scope matching semantics — OR within a dimension, AND across dimensions, an empty dimension acting as a wildcard — are specified in `../docs/01-domain.md`. `FZ-020` only persists scope; evaluation is `FZ-051`.

**Known gap:** the scope tables reference catalog rows with non-cascading foreign keys, so the database refuses to delete a team/application/environment that a restriction references — deliberately, since cascading would silently shrink a restriction's scope. That refusal isn't yet translated to HTTP, so deleting a **referenced** catalog resource currently returns `500` instead of `409`. The delete is genuinely refused and the data stays correct; only the status code is wrong. See `FZ-020` in `../docs/08-backlog.md`.

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

Every endpoint except `/actuator/health` requires a Cognito-issued JWT (`Authorization: Bearer <token>`), resolved to a `users` row and its `organization_id` — see `../docs/06-security.md`.

- **Local dev and tests always run with the `local` Spring profile active** (`-Dspring-boot.run.profiles=local`, or `@ActiveProfiles("local")` in tests). It self-issues/validates JWTs with a locally-generated key — no AWS dependency. Without it, the app has no `JwtDecoder` bean and **will not start**, since `spring.security.oauth2.resourceserver.jwt.issuer-uri` isn't set locally.
- Any real/deployed environment must set `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` to the real Cognito user pool's issuer URI and must **not** activate the `local` profile.
- Minting a test token: use `com.freezhub.shared.security.TestTokens.forSubject(jwtEncoder, subject)` (test-only helper); see `MeControllerTest` for a full example.

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
