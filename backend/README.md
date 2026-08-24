# backend

Java 21 + Spring Boot modular monolith.

Bootstrapped in `FZ-002`; PostgreSQL + Liquibase added in `FZ-004`; `organization` module (tenant boundary persistence) added in `FZ-011`. See `../docs/02-architecture.md` for the target module structure and stack, `../docs/03-data-model.md` for the schema, and `../CLAUDE.md` §8 for commands.

## Quick start

```bash
docker compose -f ../docker-compose.yml up -d postgres   # from repo root: docker compose up -d postgres

./mvnw clean verify       # build + test (Postgres via Testcontainers, no manual step needed)
./mvnw spring-boot:run    # run locally (port 8080), connects to the compose Postgres
curl http://localhost:8080/actuator/health
```

Requires Java 21 — `.java-version` pins this via [jenv](https://github.com/jenv/jenv); otherwise set `JAVA_HOME` to a Java 21 JDK.

Requires Docker running for `./mvnw clean verify` (Testcontainers) and for `spring-boot:run` against local Postgres.

## Database

- Connection is configured via `spring.datasource.*` in `src/main/resources/application.yml`, overridable with `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD`. Defaults match `../docker-compose.yml`.
- Migrations live in `src/main/resources/db/changelog/`, run by Liquibase on startup (`db.changelog-master.yaml`).
- Integration tests get a real PostgreSQL via Testcontainers (`src/test/java/com/freezhub/ContainersConfig.java`, `@ServiceConnection`) — see `FreezeHubApplicationTests`.
