# CI/CD integration

A worked example of gating a deployment on FreezeHub's Policy API (`FZ-053`).

| File | What it is |
|---|---|
| [`freeze-check.sh`](./freeze-check.sh) | the gate — POSIX shell, `curl` + `jq`, no FreezeHub-specific tooling |
| [`gitlab-ci.yml`](./gitlab-ci.yml) | GitLab CI wiring for it |

The logic lives in the script rather than in pipeline YAML so it is the same on every CI system, testable on your laptop, and readable by whoever has to debug it at 2am during a freeze.

## Setup

Issue a key (Administrator only — see [`docs/04-api.md`](../docs/04-api.md)):

```bash
curl -X POST https://freezehub.example.com/api/api-keys \
  -H "Authorization: Bearer $JWT" \
  -H 'Content-Type: application/json' -d '{"name":"gitlab-ci"}'
```

The raw key comes back **once** and is not recoverable. Store it as a masked, protected CI variable.

An API key reaches `/api/policy/**` and nothing else: it cannot read your restrictions, change your catalog, or issue another key. A leaked CI variable is not an account takeover.

## Running it

```bash
export FREEZEHUB_URL=https://freezehub.example.com
export FREEZEHUB_API_KEY=fzh_...
export FREEZEHUB_APPLICATION=payments-api
export FREEZEHUB_ENVIRONMENT=production

./freeze-check.sh
```

| Variable | Default | |
|---|---|---|
| `FREEZEHUB_URL` | — | required |
| `FREEZEHUB_API_KEY` | — | required; a credential |
| `FREEZEHUB_APPLICATION` | — | required; the name **exactly** as registered |
| `FREEZEHUB_ENVIRONMENT` | — | required; likewise |
| `FREEZEHUB_ON_ERROR` | `block` | `block` or `allow` — see below |
| `FREEZEHUB_TIMEOUT` | `10` | seconds |

| Exit | Meaning |
|---|---|
| `0` | allowed — deploy. Advisory restrictions, if any, are printed |
| `1` | blocked — a restriction is in force, or a name is not registered |
| `2` | not evaluated — misconfiguration, or FreezeHub could not be asked while `FREEZEHUB_ON_ERROR=block` |

`1` and `2` are separate on purpose: "you may not deploy" and "I could not find out" are different facts, and only the second one is your infrastructure's problem.

## The decision you have to make

**FreezeHub cannot tell you what its own silence means.** Nothing in the API can express "I could not be asked", so the pipeline decides:

- **`block`** (default) — a FreezeHub outage stops deployments. A gate that opens when it breaks is not a gate.
- **`allow`** — a FreezeHub outage lets deployments through, including during a freeze it can no longer tell you about.

Neither is safe in general. The default is `block` because that is the failure this tool exists to prevent; set it deliberately either way rather than inheriting whatever your HTTP client happens to do.

Two things are **never** subject to this setting, both deliberately:

- **A missing or invalid credential** (`HTTP 401`) exits `2` even under `allow`. Otherwise revoking a key — or fat-fingering a variable — would silently switch enforcement off for every pipeline still using it.
- **A missing required variable** exits `2`. Enforcement must not be disableable by breaking the configuration.

The script also sets a request timeout, because without one "fail closed" quietly means "hang until the job times out", which is worse than either choice.

## Names must match exactly

FreezeHub matches the application and environment name character for character, **including case**. A name it does not recognise is **blocked**, not ignored: an unregistered name matches no freeze's scope, so treating it as allowed would make a typo — or a deliberate misspelling — a way to deploy straight through a freeze.

The response says which name it did not recognise. The cost is that registering your applications and environments is part of onboarding, not an optional tidiness step.

## GitHub Actions

The script has nothing GitLab-specific in it:

```yaml
- name: FreezeHub check
  env:
    FREEZEHUB_URL: https://freezehub.example.com
    FREEZEHUB_API_KEY: ${{ secrets.FREEZEHUB_API_KEY }}
    FREEZEHUB_APPLICATION: payments-api
    FREEZEHUB_ENVIRONMENT: production
  run: ./examples/freeze-check.sh
```

Put it in the job that deploys, before the deploy step — or in a separate job the deploy job `needs`. Do not mark it `continue-on-error`; that turns every freeze into a warning.
