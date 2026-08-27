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
6. Check `docs/09-open-issues.md` for known defects and deferred decisions touching the area.
7. Inspect the existing implementation before changing it.

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
│   ├── 08-backlog.md
│   └── 09-open-issues.md   # known defects, gaps and deferred decisions
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

### Local dependencies

```bash
# start local PostgreSQL (repo root)
docker compose up -d postgres

# stop it
docker compose down
```

### Backend (`backend/`)

Requires Java 21 (`backend/.java-version` pins this via jenv; otherwise ensure `JAVA_HOME` points to a Java 21 JDK).

`./mvnw clean verify` requires Docker running (integration tests use Testcontainers to start a real PostgreSQL and run Liquibase against it) — it does not require `docker compose up` first.

`./mvnw spring-boot:run` connects to PostgreSQL via `spring.datasource.*`, overridable with `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` (defaults match `docker-compose.yml`) — start `docker compose up -d postgres` first.

Every endpoint except `/actuator/health` requires a Cognito-issued JWT (`06-security.md`). **Local runs need the `local` Spring profile active** — without it there's no `JwtDecoder` bean and the app won't start (see `backend/README.md` § Authentication).

```bash
cd backend

# build + run tests (spins up Postgres via Testcontainers; tests activate the local profile themselves)
./mvnw clean verify

# run tests only
./mvnw test

# run the app locally (default port 8080); requires `docker compose up -d postgres` first
./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# health check (no auth required)
curl http://localhost:8080/actuator/health
```

### Frontend (`frontend/`)

Requires Node 20+.

```bash
cd frontend
npm install

npm run dev      # dev server, http://localhost:5173
npm run build    # type-check (tsc -b) + production build
npm run lint      # oxlint
npm run test      # vitest run
```

### Complete local startup

```bash
docker compose up -d postgres
(cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local)   # separate terminal
(cd frontend && npm run dev)                                             # separate terminal
```

## 9. Git discipline

Development follows a **one backlog item = one feature branch** strategy.

### Base Branch

`master` is the base branch for all feature development.

Do not implement backlog items directly on `master`.

### Feature Branch Naming

Every backlog item must be developed in its own branch created from the latest local `master`.

The branch name must be the backlog item ID exactly as defined in `docs/08-backlog.md`.

Examples:

```text
FZ-002
FZ-003
FZ-020
FZ-051
```

Do not introduce alternative prefixes such as:

```text
feature/FZ-002
feat/FZ-002
claude/FZ-002
FZ-002-backend
```

unless the repository policy is explicitly changed.

Each backlog item should produce a small, reviewable unit of work.

Unless explicitly requested:

* do not create commits;
* do not push;
* do not rewrite Git history;
* do not combine unrelated stories;
* do not modify unrelated files.

At the end of a story, provide a suggested commit message using:

`<type>(<scope>): <description>`

Examples:

`chore(project): bootstrap repository structure`

`feat(restrictions): create deployment restriction`

`feat(policy): add deployment policy evaluation`

`fix(notifications): retry failed webhook delivery`

The human operator decides when to commit.

### Before Starting a Story

Before modifying code for a backlog item:

1. Inspect the current Git status.
2. Confirm there are no unexpected uncommitted changes.
3. Confirm the requested backlog item exists in `docs/08-backlog.md`.
4. Switch to `master`.
5. Create a new feature branch from `master` using the backlog ID.

Conceptually:

```text
master
  │
  ├──── FZ-002
  │       │
  │       ├── implementation
  │       ├── tests
  │       └── documentation
  │
  └──── FZ-003
```

If the expected feature branch already exists, do not recreate, delete, reset, or overwrite it automatically.

Inspect its state and report the situation before continuing.

### One Story Per Branch

A feature branch must contain changes related only to its backlog item and the supporting changes strictly necessary to complete it.

Do not:

* implement another backlog item in the same branch;
* mix unrelated refactors;
* introduce speculative future functionality;
* reuse a previous story branch for a new story.

If implementation reveals work belonging to another backlog item, report it instead of silently implementing it.

### Commits

Do not create commits unless explicitly requested.

When the story is complete:

1. Run the Definition of Done.
2. Inspect the final Git diff.
3. Verify that changes belong to the current backlog item.
4. Provide a suggested Conventional Commit message.

Format:

```text
<type>(<scope>): <description>
```

Examples:

```text
chore(backend): bootstrap Spring Boot application

feat(restrictions): create deployment restriction

feat(policy): add deployment policy evaluation

fix(notifications): retry failed webhook delivery
```

The human operator decides when the commit is created.

### Push and Merge

Do not automatically:

* push branches;
* merge into `master`;
* create pull requests;
* delete branches;
* rewrite history;
* force push.

These operations require explicit instruction.

**Standing instruction, granted during `FZ-031`:** merging a *finished* story into `master` is pre-approved. Once a story has passed its Definition of Done and been committed on its own branch, merge it with `git merge --no-ff` and branch the next story from the updated `master`.

This covers merging only. Everything else in the list above — pushing, pull requests, deleting branches, rewriting history, force pushing — still requires explicit instruction each time, as does committing work that is not a completed story.

### Story Completion

A backlog item is considered ready for human review when:

```text
Feature branch
      │
      ▼
Implementation
      │
      ▼
Tests / Build / Lint
      │
      ▼
Acceptance Criteria
      │
      ▼
Diff Review
      │
      ▼
Suggested Commit
      │
      ▼
Human Review
```

After human approval, commit/merge operations can be explicitly requested.

### Safety

Never automatically use destructive Git operations such as:

```text
git reset --hard
git clean -fd
git push --force
git branch -D
```

If repository state prevents safe continuation, stop and report the problem rather than attempting to repair Git history destructively.

## Story Execution Rules

Development is driven by backlog items defined in `docs/08-backlog.md`.

### One Story at a Time

* Work on exactly one backlog item at a time.
* Do not implement requirements belonging to later backlog items.
* Do not perform speculative work for future features.
* Supporting changes are allowed only when strictly required to complete the current story.
* If a required change appears to belong to another backlog item, report it before implementing it.

### Before Coding

For every story:

1. Read the root `CLAUDE.md`.
2. Read the story in `docs/08-backlog.md`.
3. Read the documentation referenced by the story or relevant to the affected domain.
4. Inspect the existing implementation before proposing changes.
5. Identify:

   * affected modules;
   * domain rules involved;
   * API impact;
   * database impact;
   * frontend impact;
   * security/tenant-isolation impact;
   * required tests.
6. Check whether the requested behavior is sufficiently specified.

If an important business, domain, security, or architectural decision is missing, **do not guess**.

Report:

* what is unspecified;
* why it matters;
* reasonable alternatives;
* the recommended option.

Wait for the decision before implementing behavior that depends on it.

Trivial implementation details that do not affect product behavior or architecture do not require approval.

## Scope Discipline

Prefer the smallest change that completely satisfies the current story.

Do not introduce:

* abstractions for hypothetical future requirements;
* dependencies without an immediate use;
* infrastructure for future features;
* generic frameworks when a simple implementation is sufficient;
* unrelated refactors;
* undocumented domain behavior.

Follow existing patterns unless there is a concrete reason to change them.

If an existing pattern should be changed, explain why before introducing a new architectural pattern.

## Vertical Implementation

When applicable, implement a feature through all layers required by the story:

```text
Database
   ↓
Domain
   ↓
Application
   ↓
API
   ↓
Frontend
   ↓
Tests
```

Do not create unused layers merely to satisfy this structure.

Business rules must remain authoritative in the backend.

The frontend may validate for user experience but must not become the authoritative implementation of domain rules.

## Definition of Done

A story is not complete only because the code was generated.

Before reporting completion:

1. Run the relevant build.
2. Run all tests affected by the change.
3. Run configured lint/static-analysis/type-check commands.
4. Verify the primary acceptance criteria.
5. Review the resulting diff for unrelated changes.
6. Verify no future backlog items were accidentally implemented.
7. Update documentation only when the story changes a documented contract, decision, command, or invariant.

Never claim that a command, build, test, or validation succeeded unless it was actually executed.

If something cannot be executed, explicitly state:

* what was not executed;
* why;
* what remains to be verified.

## Documentation Discipline

Documentation exists to constrain implementation decisions, not to duplicate source code.

Update documentation when:

* a domain invariant changes;
* an architectural decision changes;
* an API contract changes;
* a security rule changes;
* a development command changes;
* the scope or acceptance criteria of a backlog item changes.

Do not create documentation for implementation details already obvious from the code.

Create just-in-time documents only when their corresponding implementation requires them.

## Dependency Discipline

Before adding a dependency:

1. Confirm the functionality is not reasonably available in the existing stack.
2. Confirm the dependency is needed by the current story.
3. Prefer established, actively maintained libraries.
4. Avoid overlapping libraries solving the same problem.
5. Explain non-obvious dependency additions in the implementation summary.

Do not add dependencies only because they may be useful later.

## Final Story Report

After implementation, report using this structure:

### Implemented

What was implemented for the requested backlog item.

### Changed

Important files/modules changed.

### Validation

Commands and tests actually executed and their results.

### Decisions

Any implementation or architectural decisions introduced.

### Documentation

Documentation created or updated, if any.

### Remaining Issues

Known limitations, unresolved questions, or validations that could not be performed.

### Suggested Commit

Suggested Conventional Commit message.

If there are no remaining issues, explicitly state `None`.

