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
  - This resolves `03-data-model.md`'s open item on the `users` table: add `cognito_subject VARCHAR NOT NULL UNIQUE`. (Updated in that document as part of this change.)
- Organization resolution: on every authenticated request, `organization_id` comes from the resolved `users` row — never from client input.

**Open question, deferred to `FZ-012`:** how a `users` row is provisioned (self-signup vs. invite-only vs. admin-created) is not decided by any existing document and is not invented here. `FZ-012` must decide it.

**Open question, deferred to `FZ-011`/`FZ-012`:** whether any role distinction (e.g., Administrator vs. Engineer, per `00-product.md`'s actor list) gates specific actions. `00-product.md` excludes "advanced RBAC" from MVP scope, but says nothing about a minimal role check — not decided here.

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

- **Key format:** a high-entropy random secret, prefixed for identifiability, e.g. `fzh_<random>`. The prefix aids leak-scanning and quick identification in logs; it does not reduce the secret portion's entropy.
- **Storage:** only a salted hash (e.g. SHA-256) of the key is persisted. The raw key is shown to the caller exactly once, at creation time (`FZ-052`).
- **Lookup:** the backend hashes the incoming key and looks up the matching `ApiKey` record. `organization_id` is resolved from that record — never from any client-supplied identifier.
- **Revocation:** an `ApiKey` can be disabled. Exact lifecycle fields (e.g. `revoked_at`) are not schema'd yet — deferred to `FZ-052`, consistent with `03-data-model.md`'s deferral of the `ApiKey` table.

## Authorization

MVP does not implement advanced RBAC (`00-product.md`, Out of Scope). Beyond organization membership, no further authorization rule is specified by existing documentation. Do not invent a role model here; `FZ-011`/`FZ-012` must either confirm "any authenticated org member can act" or raise a product decision if finer-grained rules turn out to be required.

## Tenant Isolation Enforcement

1. Authentication (human JWT or machine API key) resolves exactly one `organization_id`.
2. That `organization_id` is attached to the request's security context and is the only source of truth for scoping queries and writes.
3. Application/service code must filter every tenant-owned read and write by this resolved `organization_id`. A client-supplied organization identifier appearing anywhere in a request is not authorization and must not be used as one (Governing Principle 2, `01-domain.md` invariant 4).

## Secrets Management

- Cognito app client configuration: environment-specific configuration values (issuer URI, client ID), not secrets by themselves. Any actual secret material uses AWS Secrets Manager in deployed environments (`02-architecture.md`), and environment variables locally.
- API key raw secrets: never stored, anywhere, after creation.

## Out of Scope for MVP

- Enterprise SSO / SAML / external OIDC federation beyond Cognito's own hosted authentication (`00-product.md`).
- Advanced/granular RBAC (`00-product.md`).
- Cookie/session-based human authentication — token-based only.
- Mandating specific Cognito MFA policy — configurable later as a user-pool setting without application changes.
