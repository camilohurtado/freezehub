# FreezeHub connectors

What a customer installs into their pipeline so they do not have to vendor a script and keep it current.

| | |
|---|---|
| [`freeze-check.sh`](./freeze-check.sh) | **the one implementation** — POSIX shell, `curl` + `jq` |
| [`Dockerfile`](./Dockerfile) | `ghcr.io/freezehub/freeze-check` — the script, packaged |
| [`github-action/`](./github-action) | the GitHub Action (`FZ-092`) |
| [`test/`](./test) | what proves all of the above still fail closed |

## One implementation

Every connector is packaging around `freeze-check.sh`. None of them reimplements the call.

A customer running GitLab in one team and Jenkins in another must get **the same answer from the same freeze**. Four native implementations would drift in exactly the places that matter — what a timeout means, whether a `401` fails open, how an unregistered name is reported — and the drift would show up as one team deploying during a freeze that stopped another. That is the product failing while appearing to work.

So: change the behaviour here, once. `docs/12-connectors.md` is the specification; `D-24` is why.

Where a connector cannot reach across a repository boundary and must carry a copy — Jenkins loads library resources from within the library itself — a check asserts the copy is byte-identical.

## What this does not do

It asks a question and reports the answer. It cannot stop a deployment, post a commit status, or be made a required check by branch protection: enforcement lives in your pipeline, and skipping the step skips the gate.

FreezeHub holds no credential for your repositories, receives no webhook from them, and runs no agent in your infrastructure. The arrow points one way — your pipeline calls FreezeHub.

## The image

```bash
docker build -t freeze-check connectors/

docker run --rm \
  -e FREEZEHUB_URL=https://freezehub.example.com \
  -e FREEZEHUB_API_KEY="$FREEZEHUB_API_KEY" \
  -e FREEZEHUB_APPLICATION=payments-api \
  -e FREEZEHUB_ENVIRONMENT=production \
  freeze-check
```

Alpine, `curl`, `jq`, non-root, script on `PATH` as `freeze-check`. The container's exit code **is** the gate's answer — `0` allowed, `1` blocked, `2` not evaluated.

It exists because an Argo CD PreSync hook is a Kubernetes Job and needs an image whatever else happens. Once it existed, the GitLab component stopped installing `curl` and `jq` on every pipeline run.

Publishing it to GHCR is `FZ-097`; until then, build it yourself.

## GitHub Actions

```yaml
- name: FreezeHub check
  uses: freezehub/freezehub/connectors/github-action@v1
  with:
    url: https://freezehub.example.com
    api-key: ${{ secrets.FREEZEHUB_API_KEY }}
    application: payments-api
    environment: production
```

Put it in the job that deploys, before the deploy step — or in a job the deploy job `needs`.

A blocked deployment **fails the step**, which is the whole point. Do not add
`continue-on-error` unless you mean it: it turns every freeze into a warning, and it will
be set once during an incident and never removed.

The action declares no outputs, deliberately. An output saying `BLOCKED` next to a step
that passed is a warn-only mode wearing a disguise. If you want a restriction to inform
rather than stop, make it `ADVISORY` — the gate exits `0` and prints it.

`on-error` covers a FreezeHub outage and nothing else. A missing input or a rejected API
key fails the step whatever it is set to.

## Configuration

Environment variables, the same everywhere. `examples/README.md` has the full table and the reasoning.

| | | |
|---|---|---|
| `FREEZEHUB_URL` | — | required |
| `FREEZEHUB_API_KEY` | — | required; a credential |
| `FREEZEHUB_APPLICATION` | — | required; the name **exactly** as registered, including case |
| `FREEZEHUB_ENVIRONMENT` | — | required; likewise |
| `FREEZEHUB_ON_ERROR` | `block` | what silence means — the one genuine choice |
| `FREEZEHUB_TIMEOUT` | `10` | seconds |

`FREEZEHUB_ON_ERROR=allow` covers a FreezeHub outage. It does **not** cover a missing variable or a rejected credential — both exit `2` regardless, because otherwise revoking a key or fat-fingering a variable would silently switch enforcement off for every pipeline still using it.

## Tests

```bash
node connectors/test/run-tests.js              # behaviour — the script and the action
node connectors/test/check-action.js           # the action's shape
docker build -t freeze-check:test connectors/
sh connectors/test/image-smoke.sh              # the same rules, as a container
```

`run-tests.js` drives the script against a stub Policy API in its own process, and most of what it asserts is the set of rules that must **not** fail open. Those are each one `case` branch away from turning a freeze into a warning for every customer at once, and the failure is silent: the pipeline deploys and reports success.

`run-tests.js` also executes the GitHub Action — the exact command its composite step runs, with the environment built from `action.yml` rather than restated, so the two cannot drift. That makes the action verifiable without a runner; the workflow in `verify.yml` is still what proves GitHub itself wires it up.

`check-action.js` is structural: an input declared but never wired reaches a customer as a setting that silently does nothing, and it also enforces the rule that no input may turn a blocked check into a passing one.

`image-smoke.sh` covers only what packaging can break — that busybox `ash` runs the script, that the exit code survives the container boundary, and that it is not root. It needs no network, deliberately, so it behaves the same on a laptop and on a CI runner.

## Coming

The remaining connectors are the rest of Milestone 9 in `docs/08-backlog.md`: GitLab CI (`FZ-093`), Jenkins (`FZ-094`), Argo CD (`FZ-095`).

Until they land, `examples/` shows how to wire the script by hand — which is also the answer for any CI system that never gets a connector.
