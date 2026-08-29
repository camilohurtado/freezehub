# FreezeHub — API Contract

## Purpose

The HTTP surface, and in particular the **machine-facing Policy Evaluation contract** that CI/CD systems depend on, per `FZ-050`.

The policy section is a specification for `FZ-051`, which is not yet implemented. Everything else documents what exists today.

## Conventions

### Authentication

Two mechanisms, deliberately distinct (`06-security.md`):

| Caller | Credential | Header |
|---|---|---|
| Human (browser) | Cognito JWT | `Authorization: Bearer <token>` |
| Machine (CI/CD) | API key | `X-API-Key: <key>` |

`/actuator/health` is the only unauthenticated endpoint. Under the `local` profile only, `POST /api/dev/token` mints a development JWT (`FZ-035`).

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
| `403` | authenticated but not permitted (currently: non-administrator) |
| `404` | unknown **or** another organization's resource |
| `409` | state conflict — the resource has moved on; refetch |

Errors carry the reason in a `message` field:

```jsonc
{ "timestamp": "…", "status": 409, "error": "Conflict",
  "message": "A team with this name already exists", "path": "/api/teams" }
```

Only deliberately-thrown rejections include `message`; unexpected failures fall back to Spring's default body without one, so a `500` never leaks internals. Standardising this shape further is `FZ-061`.

### Time

Every instant in every request and response is **ISO-8601 UTC**. Time zone is a presentation concern and never travels over the API (`01-domain.md` invariants 9 and 10).

## Human API (summary)

Authenticated with a JWT; all tenant-scoped.

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/me` | resolved user and organization |
| `POST` | `/api/invites` | **ADMINISTRATOR only** |
| `GET POST PATCH DELETE` | `/api/teams`, `/api/applications`, `/api/environments` | catalog; duplicate name → `409` |
| `PUT DELETE` | `/api/applications/{id}/teams/{teamId}` | team assignment, idempotent |
| `POST GET` | `/api/restrictions` | create; list with repeatable `?status=` |
| `GET PUT` | `/api/restrictions/{id}` | detail; full replacement, `SCHEDULED` only |
| `POST` | `/api/restrictions/{id}/cancel` | `SCHEDULED` or `ACTIVE` only |
| `GET POST PATCH DELETE` | `/api/integrations` | **ADMINISTRATOR only**; stored credentials are never returned |
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

## Policy Evaluation (specification for `FZ-051`)

The machine boundary, and the reason FreezeHub exists as a service rather than a wiki page. It answers `00-product.md`'s second question: **"is this deployment currently allowed?"**

### Request

```http
POST /api/policy/evaluate
X-API-Key: <key>
Content-Type: application/json

{
  "action": "DEPLOY",
  "application": "payments-api",
  "environment": "production"
}
```

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

### ⚠ Open decision — unrecognised application or environment names

**This is unresolved and blocks `FZ-051`.** Tracked as `OI-8`. It must not be settled by whoever implements the endpoint without an explicit decision.

The problem: because an unrecognised name matches no explicit scope list, evaluating it normally tends toward `ALLOW`. Sending `"prod"` when the environment is registered as `"production"` means a freeze scoped to `production` does not match, and the deployment proceeds **during a freeze**.

That is not only a typo risk. It is a **deliberate bypass vector**: anyone who wants to ship during a freeze can misspell the environment and get an `ALLOW` that looks entirely legitimate in the pipeline log.

The trade-off:

| Option | Bypass | Availability |
|---|---|---|
| Evaluate and flag the unrecognised name in the response | possible, but visible to anyone reading the response | unaffected |
| Reject the request | prevented | a pipeline for an application nobody has registered is blocked outright — FreezeHub becomes a gate on catalog completeness |
| Evaluate silently | possible and invisible | unaffected |

Constraint already agreed regardless of which is chosen: **if the request is rejected, the response must still name what was not recognised.** A bare `404` tells a pipeline nothing it can act on.

Also worth deciding alongside it: whether an evaluation involving an unrecognised name should be recorded, so the pattern is detectable after the fact (`FZ-060`).

## Client guidance

**If FreezeHub is unreachable, the pipeline decides — not FreezeHub.** Nothing in this API can express "I could not be asked". A pipeline that treats an error as `ALLOW` fails open and will deploy through an outage during a freeze; one that treats it as `BLOCK` fails closed and will halt all deployments if FreezeHub is down. Choose deliberately and state the choice in the pipeline, rather than inheriting whatever the HTTP client does by default.

`FZ-053` provides a worked example.

## Not in this contract

- Scope is **absent from webhook payloads** (`FZ-043`) on purpose: a consumer reconstructing "is my deployment affected" would be re-implementing the matching rules above, outside FreezeHub, where they would drift. Webhooks say *something changed*; this endpoint says *whether you may deploy*.
- No endpoint returns a stored integration credential (`FZ-045`).
- Versioning: the HTTP API is unversioned for the MVP. The webhook payload carries its own `version` because it is parsed by software outside FreezeHub's control; this API is consumed by clients that can be updated alongside it. Revisit before a public beta.
