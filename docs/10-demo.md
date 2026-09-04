# FreezeHub — Demo Walkthrough

A script for a commercial demo, built around the dataset `scripts/seed-demo.sh` creates.

Roughly four minutes. The arc is deliberately **problem → the moment it works → the evidence afterwards**, because the evidence is what distinguishes FreezeHub from a calendar invite and a Slack message, and it is the part a buyer has not seen before.

## Before recording

```bash
docker compose down -v && docker compose up -d postgres     # a clean database
cd backend  && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
cd frontend && npm run dev
./scripts/seed-demo.sh                                       # ~3 minutes; wait for "Done"
```

The seed waits for the lifecycle reconciler before finishing, so every screen agrees with every other by the time it returns. **Do not start recording before it says Done** — for the first minute a freeze that is already blocking deployments still reads `SCHEDULED`, which is correct and impossible to explain on camera.

Sign in at `http://localhost:5173/signin` as `dana@northwind.test`. Keep a terminal ready with the API key the seed prints.

## The script

### 1 · The problem — 20s, no screen

> "Every engineering organization has periods where deploying is a bad idea. Black Friday. A finance close. An incident. Today that gets communicated in a Slack message and a calendar invite — and then somebody deploys anyway, because they never saw it."

### 2 · The freeze exists, and it is specific — 40s · **Dashboard → Restrictions**

Show the dashboard: two restrictions in force, one upcoming, one recently finished.

Open **Black Friday trading freeze**. Point at the reason and the scope.

> "This isn't a note. It's a rule with a shape: production only, for three days, with a reason attached. And notice the second one — 'Payments on-call handover' is an *advisory*. It doesn't block anything, it just tells you what you're walking into. Not every restriction is a wall."

The advisory is worth showing. It is the detail that makes the product feel considered rather than blunt.

### 3 · A pipeline asks — 40s · **terminal**

```bash
export FREEZEHUB_URL=http://localhost:8099
export FREEZEHUB_API_KEY=fzh_...          # from the seed output
export FREEZEHUB_APPLICATION=payments-api
export FREEZEHUB_ENVIRONMENT=production

GITLAB_USER_EMAIL=you@northwind.test CI_COMMIT_SHA=9c1f0aa ./connectors/freeze-check.sh
```

It exits non-zero and names the freeze.

> "That's ten lines in a pipeline. It asks, it's told no, and it's told *why* — the freeze, the reason, and when it lifts. Nobody had to remember anything."

### 4 · Who tried — 50s · **Checks**, filtered to **Refused**

The one nobody expects, and the reason to keep watching.

> "Here's every deployment that was checked. Two engineers tried to ship to production during the freeze and were stopped — with their commit, and a link to the pipeline run.
>
> And this third one is my favourite. Somebody typed `paymnets-api` — a typo. FreezeHub doesn't recognise it, so it refuses. It will not let you sail past a freeze by misspelling something."

That last point lands with anyone who has run a change-control process, because it is the failure mode they have actually lived through.

### 5 · Why the rules look like this — 30s · **Audit**

> "And every change to the rules is recorded. Who scheduled the freeze. Who edited it, and what it was before. If somebody renames an application and pipelines start failing, the answer is right here — not in a Slack thread nobody can find."

### 6 · Setting it up — 30s · **Settings → API keys**

Issue a key on camera. The one-time reveal panel is a good visual.

> "An administrator issues a key, pastes it into CI as a masked variable, and adds one job. That's the integration. The key reaches this one endpoint and nothing else — it can't read your restrictions or change your catalog, so a leaked CI variable isn't an account takeover."

### 7 · Close — 20s

> "Freezes decided in one place, communicated to Slack, email and your own webhooks, enforced in the pipeline, and evidenced afterwards. It takes an afternoon to adopt."

## What not to say

The one claim to avoid, because a technical buyer will test it and being caught costs the deal:

> ~~"FreezeHub prevents deployments during a freeze."~~

It does not, and cannot. Enforcement lives in the customer's pipeline: a team can decline to call the API, or call it and ignore the answer. Say instead:

> "FreezeHub decides, communicates, answers and records. Your pipeline enforces — in ten lines, with the gate as a required check."

That is a stronger position anyway. It means adoption needs no agent, no credentials into their repositories, and no blast radius: FreezeHub can be wrong without breaking anything, which is why it takes an afternoon rather than a quarter.

Two other things to keep out of a first demo, since both invite a question you would rather answer later:

- **Notification delivery.** The seed leaves the Slack destination disabled, because a demo does not need retry failures behind it. Say the channels exist; do not wire one up live.
- **Sign-in.** The development sign-in has no password, and it is fenced to local development. Do not present it as the product's authentication — that is Cognito, and it arrives with the deployment (`FZ-046`).
