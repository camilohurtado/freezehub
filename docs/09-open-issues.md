# FreezeHub — Open Issues

## Purpose

Every known defect, gap and deferred decision in one place, each with an owner.

They were previously recorded only inside the entry of whichever story surfaced them, which meant a real bug could sit unnoticed in the middle of a paragraph about something else. This is the index; `08-backlog.md` remains where the work is specified.

**Rules for this file**

- Anything found and not fixed in the same story gets an entry here, immediately.
- Every entry names the story that will resolve it. If none exists, that is itself the next action — "no owner" is not a status.
- An entry is removed only when the work is done and verified, with the resolving story noted in the Resolved table below.

Severity is about consequence if it reaches beta, not effort:

| | |
|---|---|
| **Defect** | behaves incorrectly today |
| **Gap** | required behaviour is missing |
| **Decision** | a choice nobody has made; blocks or shapes later work |

## Open

### OI-2 — No real Cognito identity provider
**Severity:** Gap · **Owner:** needs a story · **Found in:** `FZ-016`

`IdentityProvider` has only `LocalIdentityProvider`, a `@Profile("local")` fake that invents a subject. Inviting a user in any deployed environment requires a real `AdminCreateUser` implementation.

Not silently broken: without the `local` profile the application refuses to start, because no `IdentityProvider` bean exists. That is deliberate fail-fast, but it does mean **the backend cannot run outside `local` at all today**, which `FZ-063` will hit the moment infrastructure work begins.

Depends on a Cognito user pool existing (`FZ-063`), so the adapter and that infrastructure should be sequenced together.

### OI-3 — "Restriction starting soon" notification is not implemented
**Severity:** Gap · **Owner:** needs a story · **Found in:** `FZ-040`

`00-product.md` lists it among the lifecycle notifications; nothing implements it. It is unlike every other event in two ways: it needs a **lead time** that no document specifies (how soon is "soon" — and is it per organization?), and it is triggered by the passage of time rather than by a state transition, so the outbox is written by a scheduled check rather than by a domain change.

Needs the lead-time decision before it can be specified.

### OI-7 — Webhook deliveries are not authenticated
**Severity:** Decision · **Owner:** needs a decision, then a story · **Found in:** `FZ-043`

A receiver has no way to verify that a webhook request actually came from FreezeHub. Anyone who learns or guesses a customer's endpoint can post a forged event to it — and a forged `CANCELLED` telling an automated consumer that a freeze has been lifted is exactly the event worth forging.

Standard remedies are an HMAC signature over the body with a per-integration shared secret, or requiring the customer's endpoint to carry its own token. Neither is specified anywhere: `00-product.md` lists the webhook channel, and `06-security.md` covers FreezeHub's *inbound* authentication but says nothing about authenticating what FreezeHub *sends*.

Partly mitigated by the endpoint URL being secret-ish in practice (usually carrying a token in its path or query), which is why the URL is kept out of logs and `last_error` — but obscurity of the URL is not authentication of the request.

Not fixed in `FZ-043` because inventing a signing scheme would have been inventing a requirement. It should be decided before a customer relies on webhook events for automation.

### OI-4 — Destination credentials are stored in plain text
**Severity:** Decision · **Owner:** needs a decision, then a story · **Found in:** `FZ-040`, `FZ-045`

A Slack webhook URL is a bearer credential and lives in `integration.config` as plain text in the database. `06-security.md` commits to AWS Secrets Manager for deployed secret material, and this does not follow that.

Partly mitigated already — the API never reads a credential back, and it is kept out of logs and `notification.last_error` — so the exposure is database-at-rest and anyone with database access, not the API surface. Acceptable for local development; needs an explicit decision before beta.

### OI-5 — Mutable restrictions have no change history
**Severity:** Decision · **Owner:** needs a decision · **Found in:** `FZ-023`

Editing a scheduled restriction overwrites the previous state with no record that it changed or who changed it. Raised during `FZ-023` and deliberately deferred, not overlooked.

`FZ-060` (Audit Events) covers part of it — recording *that* an administrative action happened — but not making the aggregate itself pristine. The alternatives (immutable/versioned restrictions, or an append-only change log) differ enough that the choice should be made before `FZ-060` is designed, not after.

### OI-6 — `docs/07-decisions.md` does not exist
**Severity:** Gap · **Owner:** needs a story · **Found in:** ongoing

`02-architecture.md` says to create it "when meaningful architectural decisions accumulate". They have: Maven over Gradle; Cognito plus its Lite pricing tier; `BIGINT` keys over UUIDs; scope AND/OR/wildcard semantics; `@ElementCollection` for scope; `LAZY` collections with explicit initialisation; reconciliation rather than an in-memory timer; CSS Modules with no UI framework; the outbox's per-destination grain; hand-rolled forms instead of React Hook Form and Zod.

Each is currently recorded only in the backlog entry of the story that made it, which is not where anyone would look for "why is it like this".

## Resolved

| Issue | Found in | Resolved by |
|---|---|---|
| Deleting a catalog entry referenced by a restriction returned `500` instead of `409` | `FZ-020` | `FZ-036` |
| Deliberate rejection reasons never reached clients — Spring omits `message` from its error body, so every `ResponseStatusException` reason in the API arrived as a bare status code | `FZ-036` | `FZ-036` |
| No API or UI configured notification destinations | `FZ-040` | `FZ-045` |
| No UI created catalog data, so the create-restriction form had nothing to scope to | `FZ-030` | `FZ-036` |
| No way to obtain a token in development before a Cognito pool exists | `FZ-030` | `FZ-035` |
| **Notification retry was unbounded** — a failed delivery was retried on every dispatch pass for ever, with no attempt limit, no backoff and no terminal state | `FZ-041` | `FZ-044` |
| CORS was absent, so every browser request failed preflight with `401` | `FZ-031` | `FZ-031` |
| Testing Library's DOM cleanup never registered, leaking rendered DOM between tests | `FZ-031` | `FZ-031` |
