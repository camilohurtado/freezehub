# FreezeHub — API Contract

## Purpose

The HTTP surface, and in particular the **machine-facing Policy Evaluation contract** that CI/CD systems depend on, per `FZ-050`.

Everything here documents what exists today.

## Conventions

### Authentication

Two mechanisms, deliberately distinct (`06-security.md`):

| Caller | Credential | Header |
|---|---|---|
| Human (browser) | Cognito JWT | `Authorization: Bearer <token>` |
| Machine (CI/CD) | API key | `X-API-Key: <key>` |

`/actuator/health` and its `liveness` / `readiness` probes are the only unauthenticated endpoints. Other actuator endpoints (`info`, `metrics`) require a JWT — their counters are aggregate and not tenant-scoped. Under the `local` profile only, `POST /api/dev/token` mints a development JWT (`FZ-035`).

The two credentials do not overlap, and that is enforced rather than merely intended (`FZ-052`):

- An API key is accepted **only** under `/api/policy/**`. Presented anywhere on the human API it is not a credential at all, so a key leaked from a CI variable cannot read an organization's restrictions, change its catalog, or issue further keys.
- A JWT is not accepted on `/api/policy/**`. A signed-in browser session cannot reach the machine boundary.
- Both mismatches are `401`, indistinguishable from no credential at all.

### Tenant isolation

The organization is always resolved from the credential, never from the request. A client-supplied organization identifier is not authorization and appears nowhere in this API.

A resource belonging to another organization returns **`404`, not `403`** — existence is never revealed across tenants. Unknown and forbidden are deliberately indistinguishable.

### Status codes

| Code | Meaning |
|---|---|
| `400` | request rejected by validation or a domain rule |
| `401` | missing, invalid, or unrecognised credential |
| `402` | refused by the plan — a limit reached, or the organization suspended (`FZ-081`) |
| `403` | authenticated but not permitted (currently: non-administrator) |
| `404` | unknown **or** another organization's resource |
| `409` | state conflict — the resource has moved on; refetch |

Every error is **RFC 9457 Problem Details**, served as `application/problem+json` (`FZ-061`):

```jsonc
{ "type": "about:blank", "title": "Conflict", "status": 409,
  "detail": "A team with this name already exists",
  "instance": "/api/teams", "timestamp": "2026-08-31T04:21:48Z" }
```

`detail` is the sentence worth showing a person. `timestamp` is an extension, kept because it is what correlates a support conversation with a log line.

**A validation failure names the offending fields**, in an `errors` extension:

```jsonc
{ "type": "about:blank", "title": "Bad Request", "status": 400,
  "detail": "The request has 2 invalid fields.",
  "instance": "/api/restrictions", "timestamp": "…",
  "errors": [
    { "field": "reason", "message": "must not be blank" },
    { "field": "name",   "message": "must not be blank" }
  ] }
```

With a single bad field, `detail` names it directly ("name must not be blank"), so a client that ignores extensions still gets something actionable.

**The rule underneath all of it: a deliberate rejection explains itself; an unexpected failure never does.** Anything the API chose to reject carries its reason. A `500` always reads *"The request could not be completed."* — the text of an unexpected exception is internal detail, and the real one is logged instead.

**Two responses carry no body**, by design rather than omission:

- `401` from the security chain, which answers before any handler runs.
- `204`, which has nothing to say.

A client must therefore not assume a body is present on failure. `type` is `about:blank` throughout: a URI pointing at documentation that does not exist would be worse than none. Typed error codes are a later addition if a client ever needs to branch on something finer than the status.

### Time

Every instant in every request and response is **ISO-8601 UTC**. Time zone is a presentation concern and never travels over the API (`01-domain.md` invariants 9 and 10).

**Precision is microseconds** (`FZ-098`). A request may send more — `Instant` carries nanoseconds and some clocks populate them — and it is rounded down on the way in, because that is what PostgreSQL `TIMESTAMPTZ` stores. The response then reports what was stored rather than echoing what was sent, so a client is told plainly what it got.

This is not cosmetic. Before it, an update comparing a client's nanoseconds against the truncated stored value found a difference on every no-op save, and wrote an audit entry describing a freeze window the database had already discarded.

One consequence worth naming: a window shorter than a microsecond collapses to zero length and is then rejected by `startsAt < endsAt` as a `400`. A window the database cannot represent as non-empty is not a window.

## Human API (summary)

Authenticated with a JWT; all tenant-scoped.

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/me` | resolved user and organization |
| `GET` | `/api/organization` | the caller's own organization and its settings |
| `PATCH` | `/api/organization/settings` | **ADMINISTRATOR only**; `startingSoonLeadTimeMinutes`, 1–43200 |
| `POST` | `/api/invites` | **ADMINISTRATOR only** |
| `GET POST PATCH DELETE` | `/api/teams`, `/api/applications`, `/api/environments` | catalog; duplicate name → `409` |
| `PUT DELETE` | `/api/applications/{id}/teams/{teamId}` | team assignment, idempotent |
| `POST GET` | `/api/restrictions` | create; list with repeatable `?status=` |
| `GET PUT` | `/api/restrictions/{id}` | detail; full replacement, `SCHEDULED` only |
| `POST` | `/api/restrictions/{id}/cancel` | `SCHEDULED` or `ACTIVE` only |
| `GET` | `/api/deployment-checks` | any member; every check and its answer, newest first, `?decision=BLOCK` for refusals, `?beforeId=` cursor |
| `GET` | `/api/audit` | **ADMINISTRATOR only**; newest first, `?resourceType=` filter, `?beforeId=` cursor, `?limit=` capped at 200 |
| `GET` | `/api/audit/resource` | **ADMINISTRATOR only**; everything that happened to one `resourceType`/`resourceId` |
| `GET POST PATCH DELETE` | `/api/integrations` | **ADMINISTRATOR only**; stored credentials are never returned. Creating a `WEBHOOK` returns its `signingSecret` once |
| `POST` | `/api/integrations/{id}/signing-secret` | **ADMINISTRATOR only**; rotates a webhook signing secret, `409` for any other channel |
| `GET POST` | `/api/api-keys` | **ADMINISTRATOR only**; `POST` returns the raw key once |
| `POST` | `/api/api-keys/{id}/revoke` | **ADMINISTRATOR only**; permanent, `409` if already revoked |

### API keys

```jsonc
// POST /api/api-keys   {"name": "gitlab-ci"}   → 201
{ "id": 1, "name": "gitlab-ci", "keyPrefix": "fzh_exampl",
  "key": "fzh_exampleKeyOnly-doNotUse-0000000000000000000",
  "createdBy": 3, "createdAt": "2026-08-29T06:21:27Z" }
```

`key` appears in this one response and nowhere else, ever. Only its hash is stored, so it cannot be read back, resent, or recovered by support — a lost key is replaced, not retrieved. Every other endpoint returns `keyPrefix` instead, which says *which* credential a row is without being enough to use it.

`POST /api/api-keys/{id}/revoke` is permanent and returns `409` if the key is already revoked, matching restriction cancellation: a second revoke means the caller believed the key was still live.

## Policy Evaluation

The machine boundary, and the reason FreezeHub exists as a service rather than a wiki page. It answers `00-product.md`'s second question: **"is this deployment currently allowed?"**

### Request

```http
POST /api/policy/evaluate
X-API-Key: <key>
Content-Type: application/json

{
  "action": "DEPLOY",
  "application": "payments-api",
  "environment": "production",

  "actor": "alice@acme.test",
  "reference": "a1b2c3d4e5f6",
  "source": "https://gitlab.acme.test/acme/payments-api/-/pipelines/9182"
}
```

The last three are **optional** and exist to make the record worth reading (`FZ-070`): who is deploying, what, and where the run can be found. FreezeHub cannot discover any of them — the API key identifies the pipeline, never the person — so they are supplied or absent. `freeze-check.sh` fills them from whatever the CI system exposes; a request without them still succeeds and a pipeline written before they existed keeps working.

They are customer PII, held for as long as `deployment_check_retention_days` and never logged.

**Identified by name, not id.** A pipeline knows `payments-api` and `production`; it does not know FreezeHub's internal numeric ids and should not have to discover them — `00-product.md` asks for simple integration. Names are already unique per organization.

Consequence to accept: renaming a catalog entry changes the key a pipeline sends. A rename is therefore a breaking change for any pipeline referencing the old name, and the UI should say so before `FZ-036`'s rename is used in anger.

`action` is `DEPLOY`; it exists so the contract does not have to change when another action appears.

**POST rather than GET**, despite being a read: a `GET` is cacheable, and a cached `ALLOW` is precisely the wrong thing to serve during a freeze. Proxies must never be able to answer this.

### Response

```jsonc
{
  "decision": "BLOCK",
  "action": "DEPLOY",
  "application": "payments-api",
  "environment": "production",
  "evaluatedAt": "2026-11-27T14:03:11Z",
  "message": "Blocked by a change restriction in force: Black Friday Freeze.",
  "unregistered": [],
  "restrictions": [
    {
      "id": 23,
      "name": "Black Friday Freeze",
      "reason": "Revenue-critical period",
      "level": "HARD_FREEZE",
      "startsAt": "2026-11-27T14:00:00Z",
      "endsAt": "2026-12-02T09:30:00Z"
    }
  ]
}
```

`decision` is `ALLOW` or `BLOCK` (`01-domain.md`).

`restrictions` lists **every** restriction that matched, not only the deciding one — the domain requires a decision to identify what contributed, and an engineer told only "BLOCK" cannot act. An `ALLOW` carrying advisory restrictions is normal and the array is the useful part of that response.

`message` is always present and is the line worth printing in a build log — it names the freezes that blocked, or says that nothing applied.

`unregistered` is empty on a normal decision. It is non-empty only in the case below, and then names which parts of the request were not recognised.

### Decision rules

Restated from `01-domain.md` so the contract is self-contained:

1. No matching in-force restriction → **`ALLOW`**, empty `restrictions`.
2. Only matching `ADVISORY` restrictions → **`ALLOW`**, with those restrictions listed.
3. **Any** matching in-force `HARD_FREEZE` → **`BLOCK`**.
4. `CANCELLED` and `COMPLETED` restrictions never affect a decision.
5. The same persisted state and evaluation instant must always produce the same decision.

### What "in force" means — and why status is not enough

> **A restriction is in force when `startsAt <= now < endsAt` and it is not `CANCELLED`.**

Evaluation must derive this from the **persisted timestamps**, not from the `status` column.

`status` is a materialised convenience maintained by a reconciler that runs on an interval (`FZ-025`), so it can lag by up to that interval. Reading `status == ACTIVE` would allow a deployment during a freeze whose activation tick had not yet run — a silent enforcement hole that would appear only under load or right after a restart, and would be very hard to diagnose. `FZ-025` records this as the contract this section now formalises.

### Matching

A restriction matches a deployment when it satisfies **every** scope dimension (`01-domain.md` § Scope matching semantics):

```text
matches(application, environment) =
      (teams        is empty OR application belongs to one of teams)
  AND (applications is empty OR application is one of applications)
  AND (environments is empty OR environment is one of environments)
```

An empty dimension is a **wildcard**, not an empty set. Worked examples:

| Restriction scope | Deploy | Result |
|---|---|---|
| environments = [production] | `payments-api` → `production` | matches — application dimension is unconstrained |
| environments = [production] | `payments-api` → `staging` | no match |
| applications = [checkout-api], environments = [production] | `payments-api` → `production` | no match |
| teams = [Payments] | any application owned by Payments, any environment | matches |
| teams = [Payments], applications = [checkout-api] | `checkout-api` → anywhere | matches **only if** checkout-api belongs to Payments |

### Errors

| Code | When |
|---|---|
| `400` | malformed body, missing field, or unsupported `action` |
| `401` | missing, unknown or revoked API key, or a human JWT presented instead of one |

### Unregistered application or environment names

**Decided (`OI-8`, closed): an unregistered name blocks.** The response says which one.

The problem it solves: an unrecognised name matches no explicit scope list, so evaluating it normally tends toward `ALLOW`. Sending `"prod"` when the environment is registered as `"production"` meant a freeze scoped to `production` did not match and the deployment proceeded **during a freeze** — not merely a typo risk but a deliberate bypass, since anyone wanting to ship during a freeze could misspell the environment and get an `ALLOW` that looked entirely legitimate in the pipeline log.

```jsonc
// POST /api/policy/evaluate  {"action":"DEPLOY","application":"payments-api","environment":"prod"}
{
  "decision": "BLOCK",
  "action": "DEPLOY",
  "application": "payments-api",
  "environment": "prod",
  "evaluatedAt": "2026-11-27T14:03:11Z",
  "message": "Blocked: no environment 'prod' is registered in this organization, so this deployment cannot be evaluated against the restrictions that may apply to it.",
  "unregistered": ["ENVIRONMENT"],
  "restrictions": []
}
```

Three properties of that response are deliberate:

- **`200` carrying `BLOCK`, not a `4xx`.** An error status lands in the pipeline's error branch — which is exactly where *Client guidance* below tells clients to choose fail-open or fail-closed for themselves. A fail-open pipeline would quietly convert this block back into a deployment, so a rejection expressed as an error can be configured away and a decision cannot.
- **`unregistered` names the dimension, and the echoed `application`/`environment` name the value.** A refusal a pipeline cannot act on is barely better than no refusal.
- **Every unrecognised dimension is reported**, not just the first, so one round trip is enough to fix both.

**Names match exactly, including case.** Catalog uniqueness is case-sensitive, so accepting `"Production"` for `"production"` would make this endpoint disagree with the registry it reads from. A case mismatch is therefore unregistered, and blocks.

**Accepted cost: FreezeHub becomes a gate on catalog completeness.** An application nobody has registered cannot deploy at all, including when no freeze exists anywhere. That is the deliberate trade — the alternative leaves a bypass open to anyone who can misspell a string. Registering applications and environments is therefore part of onboarding, not an optional tidiness step.

Still open: whether these evaluations should be **recorded**, so a repeated bypass attempt is visible after the fact rather than only refused in the moment. That belongs to `FZ-060`.

## Client guidance

**If FreezeHub is unreachable, the pipeline decides — not FreezeHub.** Nothing in this API can express "I could not be asked". A pipeline that treats an error as `ALLOW` fails open and will deploy through an outage during a freeze; one that treats it as `BLOCK` fails closed and will halt all deployments if FreezeHub is down. Choose deliberately and state the choice in the pipeline, rather than inheriting whatever the HTTP client does by default.

[`connectors/freeze-check.sh`](../connectors/freeze-check.sh) is the gate customers run (`FZ-053`, moved to `connectors/` by `FZ-091`). Note what it does **not** leave to that setting: an `HTTP 401` fails the pipeline regardless, because otherwise revoking a key would silently disable the gate for everyone still using it.

## Not in this contract

- Scope is **absent from webhook payloads** (`FZ-043`) on purpose: a consumer reconstructing "is my deployment affected" would be re-implementing the matching rules above, outside FreezeHub, where they would drift. Webhooks say *something changed*; this endpoint says *whether you may deploy*.
- No endpoint returns a stored integration credential (`FZ-045`).
- Versioning: the HTTP API is unversioned for the MVP. The webhook payload carries its own `version` because it is parsed by software outside FreezeHub's control; this API is consumed by clients that can be updated alongside it. Revisit before a public beta.
