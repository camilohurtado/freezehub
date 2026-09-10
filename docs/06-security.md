# FreezeHub — Security Specification

## Purpose

Finalizes the MVP human and machine authentication approach, per `FZ-010`, before any authentication/tenant-sensitive implementation begins (`FZ-011`+).

This document specifies the *approach*: mechanisms, tokens, and tenant-resolution rules. It does not specify implementation code.

## Governing Principles

Restated from `CLAUDE.md` and `02-architecture.md` — this document must not contradict them:

1. Human authentication and machine authentication are separate mechanisms.
2. A client-supplied `organizationId` (header, query, path, or body) is never treated as authorization. The organization is always resolved server-side from the authenticated principal.
3. Raw API key secrets are never stored after creation (`01-domain.md`).

## Human Authentication

**Provider: Amazon Cognito** (user pool). This confirms `02-architecture.md`'s infrastructure target, which named Cognito "subject to security-step confirmation" — this is that confirmation.

**Pricing tier: Lite.** `00-product.md` does not require passwordless login, passkeys, or advanced threat protection (Essentials/Plus features) — basic password authentication covers MVP scope. Lite is the cheaper option (~$0.0055/MAU beyond the 10,000 free MAU/month, vs. $0.015/MAU on Essentials) with no functional gap for what's specified. Revisit if a documented requirement later needs an Essentials/Plus-only feature.

- Flow: Cognito authenticates the user and issues an **access token** (JWT). The frontend attaches it as `Authorization: Bearer <token>` on API requests.
- Backend: Spring Security configured as an **OAuth2 Resource Server**, validating the JWT signature and claims against the Cognito user pool's JWKS endpoint (issuer URI supplied per environment via configuration, not hardcoded).
- Identity mapping: Cognito's `sub` claim is the external identity identifier. FreezeHub resolves it to a `users` row scoped to one `organization_id`.
  - This resolves `03-data-model.md`'s open item on the `users` table: add `external_subject VARCHAR NOT NULL UNIQUE`. (Updated in that document as part of this change.) **Named `cognito_subject` until `FZ-132`** (`OI-16`), which is the same column: it holds the issuer's `sub` claim, and naming the issuer in the schema claimed a coupling the backend does not have.
- Organization resolution: on every authenticated request, `organization_id` comes from the resolved `users` row — never from client input.

**Resolved by `FZ-012`:** user provisioning is **admin-provisioned bootstrap + in-product invite**:

- An organization's first user (its Administrator) is provisioned out-of-band — no self-service signup. Concretely: create the Cognito identity via `AdminCreateUser` (Cognito emails a temporary password), then insert the matching `users` row with `role = ADMINISTRATOR`. This is a manual/ops step for each new pilot organization, not a product feature.
- Every subsequent user is added via an **in-product invite**, restricted to Administrators. This is a separate backlog item (invite endpoint), not part of `FZ-012` itself — `FZ-012` delivers the authentication mechanism (JWT validation, identity resolution) that the invite feature and everything else builds on.
- Rationale: `00-product.md` names three actors per organization (Administrator, Manager, Engineer), so multi-user orgs are required — but no backlog item anywhere describes a self-service signup/onboarding flow, so building one isn't MVP scope. See that item for exact invite mechanics.

**Superseded in part by `FZ-080`:** a self-service signup flow is now specified — `docs/11-commercial.md` §4, built by `FZ-082`. The statements above remain an accurate description of what exists today, and of why nothing was built when they were written. What changes is only that "no backlog item describes one" is no longer true.

Three security rules govern it, and none of them are negotiable by the implementation:

- **`POST /api/signup` returns the same `202 Accepted` whether the organization was created or the email was already in use.** Varying the response makes signup a customer-enumeration oracle: anyone could learn which companies use FreezeHub by trying their domains. This is the same reasoning that makes a cross-tenant resource return `404` rather than `403`.
- **It cannot ship before rate limiting exists** (`OI-11`, `FZ-087`). It is an unauthenticated endpoint that creates a Cognito identity and sends an email.
- **It cannot ship before a real `IdentityProvider`** (`OI-2`, `FZ-046`). The only implementation today is a `@Profile("local")` fake.

Nothing about it weakens tenant isolation: signup creates a *new* organization and resolves nothing from client input. The organization identifier still never appears in a request.

**Implemented by `FZ-016`:** `POST /api/invites`, Administrator-only. The Cognito `AdminCreateUser` call is behind an `IdentityProvider` port — same local/real split as JWT validation below, since no real Cognito user pool exists yet. Only the local fake ships now; a real Cognito-backed implementation is required before this endpoint runs against a deployed environment (see `FZ-016`'s known gap in `08-backlog.md`).

**Resolved by `FZ-012`:** minimal role model — `users.role` is one of `ADMINISTRATOR` or `MEMBER`. This is not "advanced RBAC" (`00-product.md`'s exclusion): it gates a short, enumerated list of organization-level actions (see Authorization, below), not general resource permissions. `Team`/`Application`/`Environment`/restriction management remain open to any authenticated org member unless a future requirement says otherwise.

### Local development and automated tests

Provisioning a real Cognito user pool is infrastructure work (`FZ-063`), which is sequenced far after `FZ-012` needs to test authenticated endpoints. To avoid blocking on that:

- **Local/test profile:** the backend issues and validates JWTs signed with a locally-generated key, matching the claim shape Cognito would produce (at minimum `sub`). No AWS dependency.
- **Any deployed environment** (including a shared dev/staging AWS environment) validates against the real Cognito JWKS endpoint. There is no environment where the local signing key is trusted outside a developer's own machine or CI test run.

## Machine Authentication (API Keys)

CI/CD and other machine clients authenticate to the Policy Evaluation API (and other machine-facing endpoints) using an **API key**, sent as a dedicated header:

```text
X-API-Key: <key>
```

A separate header (rather than reusing `Authorization`) keeps human (JWT) and machine (API key) authentication mechanically distinct, per Governing Principle 1.

- **Key format:** `fzh_` followed by 256 bits of `SecureRandom` entropy in URL-safe Base64. The prefix aids leak-scanning and quick identification in logs; it does not reduce the secret portion's entropy.
- **Storage:** only the SHA-256 hash of the key is persisted. The raw key is shown to the caller exactly once, at creation time, and is not recoverable afterwards.
- **Lookup:** the backend hashes the incoming key and looks up the matching `ApiKey` record. `organization_id` is resolved from that record — never from any client-supplied identifier.
- **Revocation:** `api_key.revoked_at` is set once and never cleared. Revocation is not a toggle: a key is withdrawn because it may already be in someone else's hands, and restoring it would revive that copy. Issue a new key instead.
- **Reach:** a key authenticates only against `/api/policy/**`. It cannot read or change an organization's data and cannot mint another key, so a credential leaked from CI is not an account takeover. Managing keys is part of the human API and requires an Administrator JWT.

**Resolved by `FZ-052` — the hash is not salted.** This document originally said "salted hash (e.g. SHA-256)". A salt defeats rainbow tables and offline brute force against *low-entropy* secrets; neither attack applies to a 256-bit random value, because there is nothing to guess. A per-key salt would also mean the hash of an incoming key no longer identifies its row, forcing either a second lookup handle inside the token or hashing every stored row on every call — and the Policy API is asked on every deployment. What this section actually requires is unchanged and holds exactly: the raw key is never stored, and lookup is by hash.

## Outbound Authentication (Webhook Signing)

Everything above is about authenticating what reaches FreezeHub. This is the other direction: letting a customer's receiver verify that a webhook delivery actually came from FreezeHub (`FZ-048`, decision `D-2` in `07-decisions.md`).

Without it, anyone who learns a customer's endpoint URL can post a forged event to it — and a forged `CANCELLED`, telling an automated consumer that a freeze has been lifted, is the one worth forging.

- **Secret:** `whsec_` followed by 256 bits of `SecureRandom` entropy, generated per webhook integration and returned **once** at creation. Stored recoverable rather than hashed, because signing requires the key itself — see the note under Secrets Management.
- **Headers on every delivery:**

  ```text
  X-FreezeHub-Timestamp: 1700000000
  X-FreezeHub-Signature: sha256=<hex>
  ```

- **Signed string:** `"<timestamp>.<body>"`, HMAC-SHA256 with the shared secret, hex-encoded. The timestamp is signed *with* the body deliberately: it is what stops a captured delivery being replayed with a different body, and it lets a receiver reject deliveries that are too old.
- **Verification, receiver side:** recompute the HMAC over `timestamp + "." + raw body` and compare in constant time. Reject if the timestamp is outside an acceptable window (300 seconds is a reasonable default). Compare against the **raw** body, before any JSON reformatting — re-serialising changes the bytes and the signature will not match.
- **Rotation:** `POST /api/integrations/{id}/signing-secret` issues a new secret and invalidates the previous one immediately. There is no overlap window, so rotation is coordinated with the receiver — an overlap would keep a leaked secret working for exactly as long as it lasted.

Integrations created before `FZ-048` have no secret and are delivered unsigned, with a warning logged, until rotated.

## Authorization

MVP does not implement advanced RBAC (`00-product.md`, Out of Scope). Role checks are deliberately few, and each one guards something that decides what the organization itself can do rather than a resource within it. `role = ADMINISTRATOR` is required to:

- invite a user (`FZ-016`);
- configure a notification destination (`FZ-045`) — it decides who hears about a freeze, and its configuration can hold a credential;
- issue or revoke an API key (`FZ-052`) — a key authenticates as the whole organization.

- read anything under `/actuator` except health (`FZ-065`) — the counters there are aggregate across every tenant, so they are not an organization's data at all. An administrator is not the right bar either, merely a cheaper one than the fix: see `OI-21`.

Every other authenticated action is available to any user within their own organization.

`/actuator/health` and its probes stay unauthenticated, because the load balancer reads them.

## Tenant Isolation Enforcement

1. Authentication (human JWT or machine API key) resolves exactly one `organization_id`.
2. That `organization_id` is attached to the request's security context and is the only source of truth for scoping queries and writes.
3. Application/service code must filter every tenant-owned read and write by this resolved `organization_id`. A client-supplied organization identifier appearing anywhere in a request is not authorization and must not be used as one (Governing Principle 2, `01-domain.md` invariant 4).

## Secrets Management

- Cognito app client configuration: environment-specific configuration values (issuer URI, client ID), not secrets by themselves. Any actual secret material uses AWS Secrets Manager in deployed environments (`02-architecture.md`), and environment variables locally.
- API key raw secrets: never stored, anywhere, after creation.
- **Webhook signing secrets are the exception, and a deliberate one**: HMAC requires the key itself, so there is nothing to compare a hash against. They are stored recoverable in `integration.signing_secret`.
- **Recoverable secret material is encrypted at rest** (`FZ-049`, decision `D-3`). `integration.config` and `integration.signing_secret` are AES-256-GCM encrypted in the application before they reach the database, so a database connection, a dump or a backup yields ciphertext. It does **not** protect against a compromised application, which holds the key.
- **The encryption key** comes from `freezehub.secrets.encryption-key` — 32 bytes, Base64. A deployed environment sources it from AWS Secrets Manager and **must** supply it: there is no default outside the `local` profile, and the application refuses to start without one, the same fail-fast as the missing `JwtDecoder`. The committed local key protects a developer's own database and is worth nothing.
- Encryption sits behind a `SecretProtector` port, so storing secrets *in* a provider and keeping only a reference is a second implementation rather than a rewrite. See `D-3`.

## Out of Scope for MVP

- Enterprise SSO / SAML / external OIDC federation beyond Cognito's own hosted authentication (`00-product.md`).
- Advanced/granular RBAC (`00-product.md`).
- Cookie/session-based human authentication — token-based only.
- Mandating specific Cognito MFA policy — configurable later as a user-pool setting without application changes.
