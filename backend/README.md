# backend

Java 21 + Spring Boot modular monolith.

Bootstrapped in `FZ-002`; PostgreSQL + Liquibase added in `FZ-004`; `organization` module (tenant boundary persistence) added in `FZ-011`; human authentication added in `FZ-012`. See `../docs/02-architecture.md` for the target module structure and stack, `../docs/03-data-model.md` for the schema, `../docs/06-security.md` for the auth approach, and `../CLAUDE.md` §8 for commands.

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

### Provisioning a user (no signup flow exists yet — see `06-security.md`)

An organization's first (`ADMINISTRATOR`) user is provisioned out-of-band, manually:

1. Create the Cognito identity: `aws cognito-idp admin-create-user --user-pool-id <pool-id> --username <email>` (Cognito emails a temporary password).
2. Note the returned `sub` (or look it up via `aws cognito-idp admin-get-user`).
3. Insert the matching row: `INSERT INTO users (organization_id, cognito_subject, email, role) VALUES (<org-id>, '<sub>', '<email>', 'ADMINISTRATOR');`

Every subsequent user is added in-product by an Administrator — see `FZ-016` in `../docs/08-backlog.md` (not yet implemented).
