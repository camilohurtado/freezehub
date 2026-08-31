# FreezeHub — Decision Log

## Purpose

Why the system is the way it is, for decisions that were genuinely open and whose answer shapes work beyond the story that made them.

`02-architecture.md` asks for this file "when meaningful architectural decisions accumulate". They had. Until now each decision lived only in the backlog entry of the story that made it, which is not where anyone looks for "why is it like this".

**This file is not complete.** It starts with the two decisions taken deliberately, in isolation, rather than as a side effect of implementing something. Backfilling the decisions already made inside stories — Maven over Gradle, `BIGINT` keys over UUIDs, scope AND/OR/wildcard semantics, reconciliation rather than timers, CSS Modules with no UI framework, the outbox's per-destination grain, the unsalted API key hash, `POST` rather than `GET` for policy evaluation — remains `OI-6`.

Format: what was decided, what else was considered, and what it costs. A decision with no stated cost has not been thought about hard enough.

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
