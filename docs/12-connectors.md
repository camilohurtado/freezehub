# FreezeHub — Connectors

## Purpose

How a customer wires FreezeHub into their pipeline without copying a script into their repository.

`FZ-053` shipped `freeze-check.sh` and a GitLab example. It works, and every customer who adopts it has to vendor the file, wire it by hand, and remember to update it. That friction is the gap — not capability.

This document specifies the connector set and, more importantly, the boundary they must not cross. It is a specification: **nothing here is implemented.** Milestone 9 in `08-backlog.md` is the work.

## 1. The boundary

**FreezeHub reaches into nothing the customer owns, and connectors do not change that.**

Today the entire integration surface is one direction:

```text
   customer's pipeline  ──── POST /api/policy/evaluate ───▶  FreezeHub
                        ◀──── ALLOW / BLOCK ─────────────
```

FreezeHub holds no repository credential, receives no webhook from the customer, and has no agent inside their infrastructure. `10-demo.md` sells this deliberately — no credentials into your repositories, no blast radius — and it is the answer to the first question a security review asks.

Every connector in Milestone 9 is a **wrapper around that same call**. None of them requires FreezeHub to be granted anything. The arrow keeps pointing one way. See `D-24`.

### What this costs, stated plainly

A wrapper cannot post a commit status, cannot be made a required check by branch protection, and cannot stop a merge. **Enforcement stays voluntary** — a pipeline can skip the step, and a human can deploy by hand. That is the same limit `10-demo.md` tells you not to overclaim, and connectors do not move it.

Moving it requires an installed app with a write credential into the customer's forge, which inverts the arrow. That is `FZ-096`, deliberately separated so it is decided on its own merits rather than arriving as a side effect of "we added GitHub support".

## 2. One implementation, four wrappers

```text
                    connectors/freeze-check.sh
                     (the only implementation)
                              │
        ┌──────────┬──────────┼──────────┬──────────┐
        │          │          │          │          │
   GitHub Action  GitLab   Jenkins   Argo CD    container
                component  library  PreSync hook   image
```

A customer running GitLab in one team and Jenkins in another **must get the same answer from the same freeze**. Four native implementations — a TypeScript action, a Groovy step, a YAML-embedded curl — would drift, and the drift would show up as one team deploying during a freeze that stopped another. So there is one script, and the connectors are packaging.

Where a connector cannot reference the script across a repository boundary (Jenkins loads it as a library resource), it carries a copy, and **a check asserts the copy is byte-identical to the canonical file**. A drifted copy is the exact failure this rule exists to prevent, so it is verified rather than trusted.

### The container image

`ghcr.io/freezehub/freeze-check:v1` — Alpine, `curl`, `jq`, and the script on `PATH`.

Not gold-plating: Argo CD's PreSync hook *is* a Kubernetes Job, so it needs an image whatever else happens. Once it exists, the GitLab component uses it instead of `apk add --no-cache curl jq` on every pipeline run, and anyone on a CI system without a connector has a one-line answer.

## 3. The four connectors

| | Shape | How a customer uses it |
|---|---|---|
| **GitHub Actions** | composite action | `uses: freezehub/freezehub/connectors/github-action@v1` |
| **GitLab CI** | includable component | `include: {component: .../freeze-check@v1}` |
| **Jenkins** | shared library | `freezeCheck(application: 'payments-api', environment: 'production')` |
| **Argo CD** | PreSync hook Job | a manifest, with names read from Application annotations |

Inputs are the same everywhere and map onto the script's existing environment variables — `url`, `apiKey`, `application`, `environment`, `onError`, `timeout`. A connector adds no input the script does not already have, and interprets none of them itself.

### Argo CD is genuinely different

The other three gate a *step in a pipeline*. Argo gates a **sync**, and by the time Argo syncs, the change is already committed and merged — the freeze is being applied to convergence, not to a deploy button.

Consequences that have to be stated rather than discovered:

- A failed PreSync hook fails the sync. The Application stays `OutOfSync`, and Argo will retry on its own schedule — so during a freeze it will fail repeatedly, by design, and that must not read as an incident.
- With auto-sync enabled, the freeze is what stands between a merged commit and production. That is the strongest form of the product, and it is also the one most likely to surprise someone. It belongs in the connector's documentation, not in a footnote.
- The application and environment names come from annotations on the Argo `Application`, since a Job has no other way to know what it is syncing.

## 4. Exit codes are the contract

Every connector surfaces the script's three outcomes without collapsing them:

| Exit | Meaning | Connector must |
|---|---|---|
| `0` | allowed | succeed; print advisories if any |
| `1` | blocked | **fail** |
| `2` | not evaluated | **fail** |

`1` and `2` stay distinct in the output. "You may not deploy" and "I could not find out" are different facts and only the second is the customer's infrastructure problem.

**No connector may offer a "warn only" mode.** `continue-on-error`, `allow_failure: true` and their equivalents already exist in every CI system, so a customer who wants that can have it explicitly, in their own file, where it is visible in review. A connector input that quietly downgrades a freeze to a warning would be a supported way to defeat the product, and it would be set once and forgotten.

`FREEZEHUB_ON_ERROR` is the one genuine choice, and it is passed through unchanged — including its `block` default, and including the two cases the script never lets fail open: a missing variable, and a rejected credential.

## 5. Versioning

Connectors are consumed by customer pipelines, so a breaking change is an outage in someone else's build.

- Tagged `v1`, with `v1` moving forward to each compatible release — the convention every CI ecosystem already expects.
- **`v1` never breaks.** New inputs are optional and defaulted; an input is never removed or repurposed; exit-code meanings are frozen.
- Anything incompatible becomes `v2` and lives alongside `v1`.

## 6. Publication

Direct reference works immediately. Discovery does not: a GitHub Marketplace listing needs `action.yml` at the root of its own repository, and the GitLab CI/CD Catalog needs a dedicated catalog project. Both are a packaging step, not a rewrite — recorded as `OI-13`, owned by `FZ-097`.

This matters commercially as much as technically. A Marketplace listing is an inbound channel; a path inside a monorepo is not.

## 7. Not doing

| Not doing | Why |
|---|---|
| A GitHub App posting commit statuses | Inverts the trust arrow; decided separately (`FZ-096`) |
| Reading the customer's repository | Nothing in the product needs their code |
| Auto-registering applications from repositories | Guessing the catalog would silently create the names `D-14` exists to reject |
| A "warn only" connector input | A supported way to defeat the product (§4) |
| Per-ecosystem reimplementation | Four codebases, four bug surfaces, one HTTP call (§2) |
