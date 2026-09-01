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

**Decided (`D-4`): sequenced with `FZ-063`, not built ahead of it.** Writing the adapter now means writing it against a service nothing can reach — it could not be run once, and its first real execution would happen during infrastructure work anyway. An adapter verified only against a mock and left unexercised is a liability rather than a head start.

The "cannot start outside `local`" consequence bites exactly when the first deployed environment appears, which is `FZ-063` itself. Stays open until the pair ships.

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
| **The Policy API had no machine credential** — `FZ-051` was ordered before API keys, so the endpoint deciding whether deployments are blocked would have shipped with nothing able to authenticate to it | `FZ-050` | `FZ-052`, taken out of order |
| Any error behind the machine chain came back as `401` — the forward to `/error` is re-filtered and does not match `/api/policy/**`, so it fell through to the human chain and reported a credential failure instead of the real one | `FZ-052` | `FZ-052` |
| Testing Library's DOM cleanup never registered, leaking rendered DOM between tests | `FZ-031` | `FZ-031` |
| **Administrator features existed only in the API** — API keys, the advance-warning setting, and the audit trail could each be reached only with `curl` | `FZ-047`, beta-readiness audit | `FZ-038` and `FZ-039` |
| **Catalog changes were not audited** — renaming an application turned every pipeline using the old name into a refusal, with nothing in the trail explaining when it started or who caused it | `FZ-060` | `FZ-072` |
| **`docs/07-decisions.md` did not exist**, so every architectural decision was recorded only in the backlog entry of the story that made it | ongoing | `FZ-048` created it; `FZ-066` backfilled the rest |
| **The "restriction starting soon" notification was never implemented**, though `00-product.md` lists it — it needed a lead-time decision nobody had made | `FZ-040` | `FZ-047` — per organization, defaulting to 24 hours |
| **Destination credentials and webhook signing secrets were stored in plain text**, readable to anyone with a database connection or a backup | `FZ-040`, `FZ-045`, `FZ-048` | `FZ-049` — decided (`D-3`): AES-256-GCM in the application, behind a port that keeps the Secrets Manager lane open |
| Frontend tests finished with three unhandled rejections — the test router had no route for where the page navigates on success, so React Router threw while unmounting | `FZ-052` | `FZ-037` |
| **Mutable restrictions had no change history** — editing overwrote the previous state with no record of what changed or who changed it | `FZ-023` | decided (`D-1`): audit events with before/after, implemented by `FZ-060` |
| **Webhook deliveries were unauthenticated** — a receiver could not tell a FreezeHub delivery from a forged one, and a forged `CANCELLED` announces that a freeze has been lifted | `FZ-043` | `FZ-048` |
| **An unrecognised application or environment name could bypass a freeze** — it matches no scope list, so evaluating it normally tended toward `ALLOW`; misspelling the environment was a deliberate route to deploying during a freeze | `FZ-050` | `FZ-051` — decided: it blocks, and the response names what was not recognised |
