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

---

## D-17 — Errors are RFC 9457 Problem Details

**Date:** 2026-08-31 · **Implemented by:** `FZ-061`

### Decision

Every error response is `application/problem+json` with `type`, `title`, `status`, `detail` and `instance`, plus two extensions: `timestamp`, and `errors` naming the fields that failed validation.

### Why

There were two shapes before — one for deliberate rejections, Spring's default for everything else — and a validation failure carried no information at all beyond `400`. A form cannot highlight a field it was never told about.

Problem Details rather than a house format because it is the standard, Spring supports it natively, and FreezeHub already has a machine-facing API where a caller may well have a library that understands it.

`type` is `about:blank` throughout: a URI pointing at documentation that does not exist would be worse than none. Typed error codes are a later addition if a client ever needs to branch on something finer than the status.

### Alternatives

- **Keep the existing ad-hoc shape and apply it everywhere.** Less churn for the frontend, but it would have been inventing a format where a standard exists, and it still had nowhere to put field errors.

### Cost

A breaking change to the response body — anticipated by this backlog entry since `FZ-030`, and cheaper than expected because the frontend wrapper already read `detail` as a fallback. Status codes are unchanged, which is what the UI actually branches on.

The invariant that survives from the previous handler, and matters more than the format: **a deliberate rejection explains itself, an unexpected failure never does.** A `500` always reads "The request could not be completed."; the real exception is logged. A catch-all handler makes that easy to get wrong, so there is a test that throws a deliberately identifiable message and asserts it appears nowhere in the response.

---

## D-18 — Notification failures are metrics, never a health indicator

**Date:** 2026-08-31 · **Implemented by:** `FZ-062`

### Decision

Failing or abandoned notifications are counted (`freezehub.notifications{outcome}`) and logged. They never affect `/actuator/health`.

### Why

A custom health indicator is the obvious way to surface "notifications are failing", and it is the wrong one. The load balancer reads health, and ECS replaces tasks that fail it — so reporting DOWN because a customer's Slack webhook is unreachable would take the **API** down over a problem the API does not have. Deployments would then be blocked by a freeze nobody could cancel, which is the worst available outcome.

Health answers one question: should this instance receive traffic. Anything else belongs in metrics, where it can be alerted on without being acted on automatically.

### Cost

Nothing surfaces a delivery problem on its own. It shows up on a dashboard or an alert rule that does not exist yet, so until an exporter is wired up (a deployment concern, once `FZ-063` is applied) the counters have to be looked at deliberately. Accepted: the alternative trades a silent notification failure for a loud outage.

---

## D-19 — Deployment checks are recorded as checks, in their own table

**Date:** 2026-08-31 · **Implemented by:** `FZ-070`

### Decision

Every policy evaluation is recorded in `deployment_check` — not in `audit_event` — and the concept is named a **check**, never a deployment.

### Why

Two separate things, both easy to get wrong.

**Its own table.** `FZ-060` deliberately kept ordinary evaluations out of the audit trail: one happens per deployment, and they would bury the administrative record they sat in. That reasoning did not stop being true when this feature was wanted, so the answer was a second table rather than a reversal — different volume, different retention, different reader, different question.

**Named a check.** FreezeHub observes the question and nothing after it. A pipeline told `ALLOW` may fail for its own reasons; one told `BLOCK` may deploy anyway, because enforcement is voluntary and lives in the customer's pipeline. A console labelled "Deployments" would therefore be wrong — and wrong in exactly the audit the feature exists to serve, which is the worst moment to be found out. The word is not cosmetic.

### Cost

The record cannot answer "did it actually deploy". Closing that gap needs the pipeline to report back afterwards, which is unverifiable — a half-populated "deployed" column is worse than no column, because it looks like data. Left out deliberately; revisit only with a customer willing to wire it up.

Recording every evaluation also puts a write on the hot path, once per deployment, and makes FreezeHub a holder of customer PII (the deploying engineer's identity) where it previously held only names a customer typed. Retention is per organization for that reason, and is the only thing bounding either.

---

## D-20 — FreezeHub is priced per registered application, with users and policy evaluations unlimited

**Date:** 2026-09-03 · **Specified by:** `FZ-080` · **Implemented by:** `FZ-081`

### Decision

Plans are bounded by how many applications an organization has registered in its catalog. Users, teams, environments, restrictions and policy evaluations are unlimited on every plan, and that is a commitment rather than current generosity.

### Why

The two conventional metrics are both actively harmful here.

**Seats charge for the product.** The value of FreezeHub arrives as a Slack message and as an API answer inside a pipeline; most engineers at a customer will never sign in. Seats therefore undercount usage badly — but the real objection is that they price the thing the product is *for*. A freeze that half the organization has not been told about is not a freeze, so charging per person told is charging the customer to make the product work.

**Evaluations tax enforcement.** Metering `POST /api/policy/evaluate` gives a customer a financial reason to call it less, and calling it less is exactly how a deployment gets past a freeze. A pricing model that rewards the failure mode the product exists to prevent is not a pricing model.

Applications avoid both. Scope is built on them (`01-domain.md`), so the metric is the same thing the domain already counts. They are visible in the product, so a customer can predict their own bill. And they cannot be gamed: `D-14` blocks deployments for applications that are not registered, so hiding one to save money breaks that application's pipeline. The incentive points at registering everything, which is simultaneously what the customer wants and what FreezeHub is paid for.

### Alternatives

- **Flat per-organization tiers.** Easiest to explain and to build. Rejected because a five-engineer startup and a five-hundred-engineer bank would pay the same, which means either underpricing the bank or pricing the startup out.
- **Per application plus metered add-ons** (retention, lead time, API keys). Highest revenue ceiling, and the levers already exist as per-organization settings. Rejected for now as billing surface to build and explain before there is a single customer; the levers stay available as tier differentiators without being metered.

### Cost

The metric is coarse. An organization with two hundred tiny services pays more than one with ten large ones, regardless of which gets more value — and application granularity is the customer's choice, so the bill is partly a consequence of how they happened to draw service boundaries. A customer who splits a monolith crosses a tier without gaining anything.

It also decouples price from usage entirely: a customer who registers fifty applications and never creates a restriction pays full price. That is fine for revenue and bad for renewal, and it means adoption has to be measured directly rather than inferred from the invoice.

---

## D-21 — A billing state never changes a policy answer

**Date:** 2026-09-03 · **Specified by:** `FZ-080` · **Implemented by:** `FZ-081`

### Decision

Suspension for non-payment makes the human API read-only and stops notifications. `POST /api/policy/evaluate` keeps answering, unchanged, and active restrictions keep being enforced exactly as before. Suspension is a 30-day grace state; deactivation after it is a deliberate, communicated human action, never a scheduled job.

### Why

The two obvious enforcement mechanisms are both worse than not enforcing.

**Returning `401` breaks the customer's builds.** Every pipeline calling FreezeHub would fail at once, on a schedule the customer did not choose, and `freeze-check.sh` defaults to `block` on error — so an unpaid invoice would halt deployment across the organization. FreezeHub would have caused an outage to collect a debt, and would deserve everything said about it afterwards.

**Returning `ALLOW` silently lifts every freeze.** This is worse. The product would fail at its only job, without saying so, at the exact moment of a commercial dispute — and the customer would discover it from an incident rather than from an invoice.

The leverage that remains is real: a suspended organization cannot schedule the next freeze, cannot change scope, and gets no notifications. That is enough to force the conversation without making FreezeHub dangerous to owe money to.

### Alternatives

- **Hard cut-off at trial end.** Standard SaaS, and it converts better. Rejected on blast radius: the failure lands in the customer's deployment pipeline, not in their FreezeHub tab.
- **Degrade to advisory** — keep answering but downgrade every `HARD_FREEZE` to `ADVISORY`. Rejected because it is the silent-`ALLOW` failure wearing a disguise; the deployment still goes out.

### Cost

Non-payment is cheap for the customer for 30 days, and a customer who only ever needed the Policy API could sit suspended and keep most of the value. Collection depends on a human noticing and acting, which does not scale and will occasionally be forgotten. Accepted deliberately: the alternative is a product that can take a customer's deployments down or quietly un-freeze production, and neither is survivable in a compliance-adjacent tool.

---

## D-22 — Plan limits refuse creation and are never applied retroactively

**Date:** 2026-09-03 · **Specified by:** `FZ-080` · **Implemented by:** `FZ-081`

### Decision

A plan limit is checked when a resource is created. Downgrading below current usage deletes, disables and hides nothing — the customer keeps every application they have and simply cannot create the next one until the count is back under the limit. The refusal is `402 Payment Required`, as Problem Details, naming the plan, the limit and the current count.

### Why

Enforcing retroactively would mean choosing applications to remove from the catalog, and `D-14` blocks deployments for applications that are not in the catalog. A downgrade would therefore start blocking pipelines for services the customer still runs. If instead the applications were merely detached from restriction scopes, every restriction scoped to them would silently narrow — **a billing event would un-freeze production**, which is the failure `FZ-020` refused to let even the database cause when it chose `RESTRICT` over `CASCADE` for scope foreign keys.

`402` because no other code says what happened. The request was well-formed, so not `400`; the caller is permitted, so not `403`; nothing conflicts, so not `409`. The plan refused, and `402` is the only status that means that.

### Cost

A customer can sit indefinitely over the limit of the plan they pay for, by downgrading, and FreezeHub will keep serving all of it. Over-limit organizations have to be visible somewhere or the situation is invisible until someone runs a query. Adding `402` also widens the status-code surface in `04-api.md`, which every client now has to understand.

---

## D-23 — There is no platform super-administrator; sales provisioning is an operator script

**Date:** 2026-09-03 · **Specified by:** `FZ-080` · **Implemented by:** `FZ-086`

### Decision

Provisioning an organization after a demo is a script run by a FreezeHub operator with production access. There is no in-product role, endpoint or console that can act across organizations.

### Why

The security model is one sentence: *the organization is always resolved from the credential, never from the request*. It is stated in `CLAUDE.md` §5, restated in `04-api.md`, and is the reason a cross-tenant resource returns `404` rather than `403`. Every endpoint written so far is correct because that sentence is unconditionally true.

A principal that can act across tenants makes it conditionally true. From then on, every endpoint — including ones not yet written — has to be reasoned about twice, and a single mistake exposes every customer to every other customer. That is the largest available downside in a multi-tenant product, traded for saving a few minutes on an operation that happens rarely.

At the volume where sales-assisted provisioning matters — the first twenty customers — a script is safer and cheaper than the console, and it is inspectable in git.

### Alternatives

- **A `PLATFORM_OPERATOR` role on `users` with a null organization.** Cheapest to build, worst placed: it puts the cross-tenant principal inside the application that serves tenants, which is precisely where it must not be.
- **A separate internal admin service** with its own credential and its own deployment. The right answer eventually, and what to build when this is revisited. Rejected now as a second deployable to secure, monitor and maintain for an operation performed a few times a month.

### Cost

Provisioning requires production access, so only engineers can do it — which is wrong the moment a non-engineer needs to close a deal on a Friday, and it is a reason to hand out production access that would not otherwise exist. There is no audit trail of provisioning beyond shell history and whatever the script writes. Revisit when provisioning becomes weekly, or the first time somebody without production access needs to do it; build it then as the separate service, not as a role.

---

## D-24 — Connectors distribute the existing gate; FreezeHub gains no reach into customer systems

**Date:** 2026-09-03 · **Specified by:** `FZ-090` · **Implemented by:** `FZ-091`–`FZ-095`

### Decision

The GitHub Action, GitLab component, Jenkins library and Argo CD hook are packaging around one implementation — `connectors/freeze-check.sh`. None of them requires FreezeHub to hold a credential for the customer's forge, receive a webhook from it, or run an agent inside their infrastructure. The integration arrow keeps pointing one way: their pipeline calls FreezeHub, and FreezeHub calls nothing.

### Why

**One implementation, because four would drift.** A customer running GitLab in one team and Jenkins in another must get the same answer from the same freeze. Four native implementations — a TypeScript action, a Groovy step, curl embedded in YAML — would diverge in exactly the places that matter: what a timeout means, whether a `401` fails open, how an unregistered name is reported. The drift would surface as one team deploying during a freeze that stopped another, which is the product failing while appearing to work.

**No reach, because the reach is the objection.** `10-demo.md` sells "no agent, no credentials into your repositories, no blast radius" deliberately: it is the answer to the first question a security review asks, and it is why a platform team can adopt FreezeHub without a procurement cycle. Adding a forge credential to solve a *distribution* problem would spend that position on something packaging solves for free.

**Adoption friction is what is actually missing**, not capability. The gate works today; every customer has to vendor it and keep it current. That is the gap.

### Alternatives

- **Native app integrations now** (a GitHub App posting a required commit status). The only option that makes a `HARD_FREEZE` genuinely enforced rather than voluntary, and probably the most valuable thing the product could ship. Deferred to `FZ-096` as its own decision with its own security review — it inverts the trust direction, and that should be chosen on its merits rather than arriving as a side effect of adding GitHub support.
- **Reimplement per ecosystem** in each one's native language, for better logs and typed inputs. Rejected: four codebases and four bug surfaces wrapped around a single HTTP call.
- **Leave it at the script and write better documentation.** Cheapest, preserves everything, and leaves every customer maintaining a vendored copy — which is where the friction was.

### Cost

**Enforcement stays voluntary, and connectors do not move that.** A wrapper cannot post a commit status, cannot be made a required check by branch protection, and cannot stop a merge or a manual deploy. This is the same limit `10-demo.md` forbids overclaiming, and shipping four polished connectors makes it easier to forget, not harder — a customer who installs an official GitHub Action may reasonably assume it enforces something.

The Jenkins connector must carry a copy of the script, because Jenkins loads library resources from within the library. That copy is the one place the one-implementation rule can break, so it is verified byte-for-byte rather than trusted.

And distribution is not finished by building them: Marketplace and Catalog listings need dedicated repositories (`OI-13`), so the connectors are usable by direct reference before they are discoverable.


---

## D-25 — Instants are normalised to the precision the database stores

**Date:** 2026-09-04 · **Implemented by:** `FZ-098`

### Decision

A user-supplied `Instant` is truncated to microseconds — PostgreSQL `TIMESTAMPTZ`'s precision — at the edge where it enters the system, in `RestrictionRequest`. The aggregate truncates again on construction and replacement, so its in-memory state equals what a reload would produce for every caller, not only for ones that arrived over HTTP.

### Why

Found by the first CI run this project ever executed. `AuditTrailTest.recordsNothingWhenAnUpdateChangedNothing` failed on Linux and passed on macOS, because `Instant.now()` hands out nanoseconds on one and typically microseconds on the other.

The failure was not the test. An update compared the client's nanosecond value against the stored microsecond one, found a difference, and recorded:

```json
{"startsAt":{"from":"2026-09-06T07:03:40Z","to":"2026-09-06T07:03:40.000000123Z"}}
```

Nothing had changed; the client sent back exactly what it sent before. Worse, **the `to` value was never persisted** — the database truncated it straight back to `from`. The audit trail, whose whole purpose is answering "who changed this freeze, and to what", was recording an after-state that never existed, on every update, burying real changes in noise.

Normalising at the boundary makes one value flow through validation, the diff, the aggregate and the response. A comparison between the stored value and the incoming one then means something.

### Alternatives

- **Truncate inside the comparison only.** Two lines, fixes the symptom. Rejected because it puts knowledge of storage precision in the service, and leaves the request, the response and the entity disagreeing about what the value is.
- **Reject sub-microsecond precision with a `400`.** Honest and explicit. Rejected as hostile: a client formatting an ISO-8601 instant with nanoseconds has done nothing wrong, and refusing it would break integrations over a difference that cannot matter.
- **Store timestamps as text, or as an epoch-nanosecond `BIGINT`.** Preserves everything. Rejected: it trades every date function PostgreSQL has for precision nobody asked for.

### Cost

Precision is silently lost. A client that sends nanoseconds gets microseconds back and is told only by the response, never by an error — which is the right trade, but it is a trade.

A window shorter than one microsecond now collapses to zero length and is rejected as a `400`. Defensible, since the database cannot represent it as non-empty, but it is a case that used to be accepted.

And the rule lives in two places — the request record and the aggregate — which is duplication, deliberately: the first is what fixes the diff, the second is the aggregate refusing to hold state it cannot store, for callers that never touched a request. Any new user-supplied timestamp field needs the same treatment, and nothing enforces that but this entry.


---

## D-26 — One image is the connector; per-ecosystem packages are reference implementations

**Date:** 2026-09-04 · **Specified by:** `FZ-097` · **Published by:** `FZ-099`

### Decision

One implementation and **one published artifact**: the container image, public on GHCR as `ghcr.io/freezehubio/freeze-check`, Apache-2.0, free with or without a subscription. Every CI system integrates by running it, and `connectors/README.md` carries a guideline per system.

The GitHub Action and the GitLab component stay in the source tree as **tested reference implementations, not published packages**. The product source stays private and there is no second repository.

What is sold is the answer the image fetches. Nothing in it checks a licence, a plan or an entitlement, and nothing ever will.

### Why

**Nearly every CI system runs containers**, so one artifact covers nearly the whole market. A per-ecosystem package covers one ecosystem and adds a repository to keep in step. And because it is literally the same binary everywhere, a customer running GitLab in one team and Jenkins in another cannot get different answers from the same freeze — which is what `D-24` is protecting.

**Publishing native packages requires public source repositories.** `uses:` and `component:` resolve against a repository the customer can read; a private one fails with "repository not found" whatever else is right. So Marketplace and Catalog listings mean a second public repository, a sync step, a cross-repo token and a release that force-pushes a build artefact — machinery to maintain before there is a customer, bought with roughly two lines of YAML in each customer's pipeline.

**Free and open is required, not generous.** The gate is about two hundred lines of POSIX shell making one HTTP POST. Any customer can rewrite it in an afternoon, so a licence check is unenforceable theatre — and it is read by a security team before it enters a deploy path. "No agent, no credentials into your repositories, no blast radius" (`D-24`) is only credible if they can read the thing that runs there.

**The billable boundary is already in the right place.** An API key reaches `/api/policy/**` and nothing else (`FZ-052`); without one the connector gets `401` and fails the build, which is the correct behaviour for a non-customer. And because policy evaluations are unlimited on every plan (`D-20`) and a billing state never changes a policy answer (`D-21`), the connector never needs to know anything about billing — permanently.

**The incentives align.** An unregistered application is blocked (`D-14`) and billing is per registered application (`D-20`), so every pipeline that adopts the free image pushes the customer to register that application. The free thing drives the meter.

### Alternatives

- **Publish native packages from a second public repository**, listed on Marketplace and the GitLab Catalog. Best ergonomics, and a listing is a genuine inbound channel. **Deferred rather than rejected** (`FZ-096`): revisit when a listing is worth its maintenance, which is a question about demand, not engineering.
- **Make the whole monorepo public** — no extraction, no sync, and the security review reads everything. Rejected on what it discloses: `11-commercial.md` is the pricing model and go-to-market, and that is not something to hand a competitor before the first customer.
- **Source-available, licensed only with a subscription.** Keeps a legal tether and still passes a security read. Rejected: unenforceable at this size, forfeits any listing, and converts goodwill into friction for no collectable revenue.
- **MIT rather than Apache-2.0.** Shorter and better known. Apache-2.0 chosen for the explicit patent grant, which is what enterprise legal review — the exact audience for a change-freeze tool — waves through without a question.

### Cost

**No Marketplace or Catalog listing, so no inbound discovery from either.** That is the main thing being traded, and it is a marketing loss rather than a technical one. It is also the thing most likely to be regretted, because discovery compounds and this decision postpones the start of that compounding.

**Every integration guideline carries plumbing a native package would have hidden.** Passing `GITHUB_ACTOR`, `GITHUB_SHA` and the run URL through to the container is three lines the customer sees and could get wrong, and getting it wrong degrades the deployment record silently rather than loudly.

**The image is the only thing a customer can inspect.** Reading the script means `docker run --entrypoint cat`, which is a worse answer in a security review than "here is the repository". The script is short and that keeps it survivable, but it is a real weakening of the strongest thing we have to say.

**Two reference implementations are maintained and tested but shipped to nobody.** If a listing never happens they are dead weight, and the temptation to document them as installable — which they are not — is a live risk that the README has to keep resisting.

And a permissive licence on a thin client means anyone can point it at their own implementation of the Policy API, or fork it. That is the honest consequence, and it is worth less to a competitor than it looks: the client is the easy part.


## D-27 — Aggregates bucket by UTC day, and the bucketing lives in SQL

`FZ-105`

Every screen prints "all times GMT". The aggregates behind them — today's checks by
decision, the 14-day series — therefore bucket by **UTC calendar day**, not by the viewer's
day and not by the server's.

### Why not a local day

A local day is more comfortable to read and produces a figure two people can disagree
about. "How many deployments were refused yesterday" is a question asked in an incident
review, and an answer that depends on which office the person asking is sitting in is
worse than an answer that needs a mental offset. One answer beats a convenient one.

### Why in SQL, spelled out

The first implementation grouped in JPQL with `cast(c.checkedAt as LocalDate)`. On a
`timestamptz` that truncates in whatever zone the JDBC session carries — the server's —
so the same rows bucketed differently depending on where the process happened to run, and
the endpoint's own "today" (computed in Java, in UTC) could name a day the series had
split. It passed every test on a developer machine and would have passed CI, because both
sit close enough to UTC for a midday fixture to hide it.

It was caught by running the suite under `TZ=Asia/Tokyo`, and the query is now native with
`at time zone 'UTC'` written out. `DeploymentCheckSummaryTest` pins it with checks at
02:00Z and 23:00Z — the two instants where a UTC day and a local one disagree.

### Consequence

Any future aggregate over an instant column inherits this: bucket in SQL, name the zone,
and test at a boundary instant rather than at midday. A test whose fixtures all sit at
noon cannot tell the two implementations apart.

This is the daily-grain sibling of `D-25`, which normalises instants to the precision the
database stores. Both exist because a timestamp read back is not automatically the
timestamp written.
