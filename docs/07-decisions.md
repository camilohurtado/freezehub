# FreezeHub — Decision Log

## Purpose

Why the system is the way it is, for decisions that were genuinely open and whose answer shapes work beyond the story that made them.

`02-architecture.md` asks for this file "when meaningful architectural decisions accumulate". They had. Until now each decision lived only in the backlog entry of the story that made it, which is not where anyone looks for "why is it like this".

`D-1` to `D-4` were taken deliberately, in isolation, rather than as a side effect of implementing something. `D-5` onwards were backfilled by `FZ-066` from the stories that made them.

Format: what was decided, what else was considered, and what it costs. **A decision with no stated cost has not been thought about hard enough.**

Not everything belongs here. A decision earns an entry when it was genuinely open and its answer shapes work beyond the story that made it — not for every choice a story had to make.

---

## D-1 — Restriction changes are recorded as audit events, not by making restrictions immutable

**Date:** 2026-08-30 · **Resolves:** `OI-5` · **Implemented by:** `FZ-060`

### Decision

Editing a restriction keeps overwriting it in place. Every administrative action writes an immutable `AuditEvent` recording who did it, when, to which resource, and a snapshot of the fields that changed — before and after. `ChangeRestriction` stays a mutable aggregate.

### Why

The problem looked bigger than it is. `FZ-023` already refuses every edit once a restriction is `ACTIVE`, `COMPLETED` or `CANCELLED`, so **the mutable window is exactly the window in which the freeze has not yet done anything**. Nothing that has actually taken effect can be rewritten today. What was missing was not immutability but *memory*: no record that a change happened, or who made it.

An audit event carrying before-and-after answers the real question — "who changed this freeze, and to what?" — completely.

### Alternatives

- **Append-only revision table.** A row per version, plus an endpoint to read a restriction's history. Genuinely more capable: point-in-time reconstruction, not just a change record. Rejected as more machinery than the question needs, and it still would not make the aggregate pristine — it records history rather than preventing rewrites.
- **Immutable versioned restrictions.** An update creates a new version; "current" becomes a pointer. The only option that makes the aggregate genuinely pristine. Rejected on where the cost lands: `notification` and the Policy API both hold `restriction_id`, so versioning changes what that reference means across five modules — including the enforcement path, which is the last place to add ambiguity. It is also close to event sourcing, which `CLAUDE.md` §4 excludes from the MVP.

### Cost

No point-in-time reconstruction. "What did this freeze look like last Tuesday?" is answerable only by replaying audit snapshots by hand, and only for fields that changed. If that becomes a real question — a compliance audit is the likely trigger — the revision table is the upgrade, and the audit events written in the meantime are not wasted.

---

## D-2 — Webhook deliveries are signed with HMAC-SHA256 over timestamp and body

**Date:** 2026-08-30 · **Resolves:** `OI-7` · **Implemented by:** `FZ-048`

### Decision

Every webhook integration gets a signing secret at creation, returned once. Each delivery carries:

```text
X-FreezeHub-Timestamp: 1700000000
X-FreezeHub-Signature: sha256=<hex HMAC-SHA256(secret, "<timestamp>.<body>")>
```

A receiver recomputes the HMAC and compares. Secrets are rotatable, with no overlap window.

### Why

A receiver previously had no way to tell a FreezeHub delivery from anything else that found the URL. The event worth forging is `CANCELLED`: it tells an automated consumer a freeze has been lifted.

Signing the timestamp **together with** the body is what makes the signature non-replayable — a captured delivery cannot be re-sent with a different body, and a receiver that rejects old timestamps cannot be fed a stale one indefinitely. It is also the scheme Stripe and GitHub use, so receivers already have libraries and engineers already recognise it.

### Alternatives

- **Customer-supplied static token in a header.** Simpler on both sides, no signing code. Rejected: it is a bearer token, so anyone who captures one delivery can replay it for ever, and it says nothing about whether the body was altered.
- **Leave it unauthenticated and document the risk.** Defensible only while nobody automates on the events — which is precisely what the webhook channel exists to enable.

### Cost

**A second recoverable secret at rest.** Unlike an API key, this cannot be stored hashed — signing needs the key itself. That makes `OI-4` (credentials stored in plain text) more pressing rather than less, and this decision is part of the reason to settle it before beta.

Rotation is abrupt: there is no window in which both the old and new secret are accepted, so rotating is a coordinated change with the receiver. Deliberate — an overlap window keeps a leaked secret alive for exactly as long as the window lasts.

Webhook integrations created before `FZ-048` have no secret and are delivered **unsigned**, with a warning logged, until rotated. Refusing to deliver would silently stop announcements a customer relies on; that trade is revisited if any such integration ever exists in a deployed environment.

---

## D-3 — Secret material is encrypted in the application, behind a port

**Date:** 2026-08-30 · **Resolves:** `OI-4` · **Implemented by:** `FZ-049`

### Decision

`integration.config` and `integration.signing_secret` are encrypted with **AES-256-GCM** before they reach the database, through a `SecretProtector` port with one implementation today. The key comes from `freezehub.secrets.encryption-key`, which a deployed environment sources from AWS Secrets Manager; there is no default outside the `local` profile, so an environment that forgets it fails to start.

The port is the point. Two later moves are open without touching a single caller:

- **Key management** — the AES key already comes from configuration, so pointing it at Secrets Manager, Vault, or a cloud KMS is configuration, not code.
- **Full delegation** — storing the secret *in* a provider and keeping only a reference in the row is a second implementation of `SecretProtector`, not a rewrite.

### Why this and not Secrets Manager outright

Delegating now would put an external dependency on the notification delivery path and force every local developer to run against a real AWS account or a fake. Encrypting in the application is self-contained, testable locally, and closes the exposure the register actually names — a database connection, a dump, or a backup — today rather than after infrastructure exists.

GCM rather than CBC because it authenticates as well as encrypts: a row edited directly in the database fails to decrypt instead of quietly yielding different plaintext.

### Alternatives

- **References to AWS Secrets Manager.** The most literal reading of `06-security.md`. Deferred, not rejected — it is the lane this decision deliberately keeps open.
- **RDS encryption plus restricted database access.** Cheapest, and it does protect stolen disks and backups. Rejected as the *primary* control because it does nothing about the threat named in `OI-4`: someone who has a database connection reads plaintext.

### Cost

**It does not protect against a compromised application**, which holds the key. That is the accepted limit of encrypting in-process, and the reason the key belongs in a secrets manager rather than a config file.

The stored form carries a `fzenc1:` scheme prefix, and anything without it is treated as pre-`FZ-049` plaintext and returned as-is. So **existing rows are not migrated**; they are encrypted the next time they are written. No bulk re-encryption task exists, which is acceptable because no deployed environment does — if one ever ships before this, it needs one.

Key rotation is not implemented. The scheme prefix is what makes it addable later without a flag day.

---

## D-4 — The Cognito identity adapter is sequenced with the infrastructure that provisions the pool

**Date:** 2026-08-30 · **Concerns:** `OI-2` · **Implemented by:** `FZ-046` together with `FZ-063`

### Decision

`FZ-046` (a real `AdminCreateUser` implementation of the `IdentityProvider` port) is not built ahead of `FZ-063`. The two ship together, and `OI-2` stays open until they do.

### Why

Writing the adapter now means writing it against a service nothing can reach: no AWS account is provisioned, so it could not be executed even once, and its first real run would happen during infrastructure work anyway. An adapter verified only against a mock, sitting unexercised for months, is a liability rather than a head start.

The alarming-sounding consequence of leaving it — **the backend cannot start at all outside the `local` profile**, because no `IdentityProvider` bean exists — bites exactly when the first deployed environment appears, which is `FZ-063` itself. That fail-fast is deliberate (`FZ-016`) and is doing its job.

### Cost

`OI-2` stays open, and `FZ-063` carries more work than pure infrastructure. Accepted: the alternative front-loads the same work with none of the verification.

---

# Backfilled decisions

Everything below was decided inside the story that needed it and recorded only in `08-backlog.md`, which is not where anyone looks for "why is it like this". Recorded here by `FZ-066`.

**Numbering is by when a decision was written down, not when it was made**, because `D-1` to `D-3` are already referenced from code and renumbering would break those references. Each entry carries the story that made it.

---

## D-5 — Maven, not Gradle

**Made by:** `FZ-002`

Spring Boot's own documentation, most Stack Overflow answers, and every Spring Initializr default assume Maven. For a modular monolith with no unusual build requirements, the ceiling on Gradle's flexibility is never approached, and the floor — a build file anyone can read without learning Groovy or Kotlin DSL — matters more.

**Cost:** slower builds on a large codebase, and no incremental compilation. Neither is felt at this size.

---

## D-6 — Amazon Cognito, on the Lite tier

**Made by:** `FZ-010`

Confirms `02-architecture.md`, which named Cognito "subject to security-step confirmation". Lite because `00-product.md` requires none of what Essentials adds — no passwordless login, passkeys, or advanced threat protection — and Lite is roughly a third of the per-user price beyond the free tier.

**Cost:** revisit if a documented requirement later needs an Essentials-only feature. Moving up a tier is a configuration change, not a migration.

---

## D-7 — `BIGINT` identity keys, not UUIDs

**Made by:** `FZ-011`, at the user's direction

Cheaper to index and join, smaller on every foreign key, and readable in a log or a support conversation.

Sequential ids being guessable is not a tenant-isolation risk here, and that is a property of the design rather than luck: authorization is never derived from an id. Every request's `organization_id` comes from the authenticated principal, and every tenant-owned lookup is scoped by it — so guessing a neighbour's id yields a `404`, not their data.

**Cost:** ids are not globally unique, so they cannot be minted client-side or merged across databases. Neither is needed.

---

## D-8 — Scope matches with OR inside a dimension, AND across dimensions, and an empty dimension is a wildcard

**Made by:** `FZ-020`, at the user's direction · **Implemented by:** `FZ-051`

Teams, applications and environments are independent dimensions. Naming a team *and* an application therefore **narrows** — "this application, and only while it belongs to this team" — rather than widening to a union.

An empty dimension places no constraint, which is what lets "freeze every deployment to production" be expressed by naming only an environment. Invariant 3 keeps that safe: a restriction with no targets in any dimension is rejected, so a scope can never mean "everything, everywhere".

**Cost:** a scope naming a team and an application outside it matches nothing. Deliberately not rejected at creation, because membership is mutable — a scope that matches nothing today may match tomorrow.

---

## D-9 — Scope is an `@ElementCollection`, not an entity

**Made by:** `FZ-020`

Scope rows have no identity of their own and are owned parts of the restriction, so they are persisted and removed with it. Contrast `TeamApplication`, which joins two *independent* aggregates and is therefore an entity.

**Cost:** scope rows cannot be queried or referenced independently. Nothing needs to.

---

## D-10 — Scope collections are `LAZY`, initialised explicitly

**Made by:** `FZ-021`, revisited by `FZ-051`

`EAGER` made every list call issue 3N+1 queries. `LAZY` plus a deliberate `Hibernate.initialize` where scope is actually needed keeps listing to a single query regardless of row count.

`FZ-051` added `@BatchSize` on top, because policy evaluation touches the scope of a whole candidate set and is asked once per deployment: 20 in-force restrictions cost three scope queries rather than sixty.

**Cost:** touching scope outside a transaction throws. That is caught by tests, and the explicit initialisation is what makes the intent visible.

---

## D-11 — Restriction lifecycle is reconciled, not scheduled

**Made by:** `FZ-025`

No timers and no in-memory state. Each run compares persisted timestamps against a supplied instant and corrects the stored status with set-based updates, so it is idempotent, safe to run concurrently with itself, and recovers by itself after a restart or an outage of any length.

Triggered on startup as well as periodically, because a periodic tick alone would leave statuses stale for however long the process was down.

**Cost:** the stored `status` can lag by up to one interval — which is exactly why `D-13` exists.

---

## D-12 — The notification outbox is per (event × destination)

**Made by:** `FZ-040`

Slack succeeding while a webhook fails is a normal outcome, and one status per event could not express it. Retry (`FZ-044`) has to be per destination for the same reason.

Rows are written **in the same transaction as the domain change**, which is what makes the intent survive a crash between "restriction activated" and "notification queued" — the job a message broker would otherwise do, which `02-architecture.md` rules out for the MVP.

The unique constraint on (restriction, integration, event) is the load-bearing part: it makes enqueueing idempotent, so a reconciliation that runs twice cannot notify anyone twice. `FZ-047` later depended on exactly that, and needed no new machinery.

**Cost:** more rows than a per-event design, and fan-out happens at enqueue time, so a destination added later does not receive past events.

---

## D-13 — "In force" is derived from timestamps, never from the `status` column

**Made by:** `FZ-050`, implemented by `FZ-051`

A restriction is in force when `startsAt <= now < endsAt` and it is not `CANCELLED`. `status` is a materialised convenience maintained by the reconciler in `D-11` and can lag by up to one interval.

Reading `status == ACTIVE` would allow a deployment during a freeze whose activation tick had not yet run — a silent enforcement hole appearing only under load or right after a restart, and very hard to diagnose.

**Cost:** the decision cannot be answered by an index-only lookup on `status`. Irrelevant at this scale, and correctness is not negotiable on the enforcement path.

---

## D-14 — Policy evaluation is a `POST`, and an unregistered name blocks

**Made by:** `FZ-050` and `FZ-051`, the second at the user's direction · **Resolves:** `OI-8`

`POST` despite being a read: a `GET` is cacheable, and a cached `ALLOW` served during a freeze is precisely the failure the endpoint exists to prevent.

An application or environment name the catalog does not recognise returns `BLOCK`, naming what was not recognised. An unrecognised name matches no scope list, so evaluating it normally tends toward `ALLOW` — making a misspelt environment a route to deploying straight through a freeze with a legitimate-looking permission in the pipeline log.

Returned as a `200` carrying `BLOCK` rather than a `4xx`, because an error status lands in the pipeline's error branch, which is where clients choose fail-open or fail-closed for themselves — a rejection expressed as an error can be configured away, a decision cannot.

**Cost:** FreezeHub becomes a gate on catalog completeness. An unregistered application cannot deploy at all, even with no freeze anywhere, so registering applications and environments is part of onboarding.

---

## D-15 — API keys are stored as an unsalted SHA-256 hash

**Made by:** `FZ-052`

`06-security.md` originally said "salted hash". A salt defeats rainbow tables and offline brute force against *low-entropy* secrets; against 256 bits of `SecureRandom` there is nothing to guess.

A per-key salt would also stop the hash of an incoming key from identifying its row, forcing either a second lookup handle inside the token or hashing every stored row on every call — on an endpoint asked once per deployment.

Note the contrast with `D-3`: a webhook signing secret cannot be hashed at all, because HMAC needs the key itself. Different problems, different storage.

**Cost:** a documented divergence from the original wording of the security specification, which was updated with this reasoning rather than left to contradict the code.

---

## D-16 — CSS Modules and native form controls, with no UI framework

**Made by:** `FZ-030`, extended by `FZ-032`

No styling dependency and no component library (`CLAUDE.md`: no dependencies without a concrete need). `<input type="datetime-local">` and `<select multiple>` cover the create-restriction form. Forms are hand-rolled rather than using React Hook Form and Zod, because the backend is the authoritative validator and the frontend only needs enough to be pleasant.

**Cost:** multi-select UX is basic, and each new form repeats a little wiring. Accepted deliberately; revisit if the form count grows.
