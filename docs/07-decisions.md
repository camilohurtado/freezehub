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
