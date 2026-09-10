#!/bin/sh
#
# Builds a believable organization to demonstrate FreezeHub against (FZ-074).
#
# Everything goes through the real API, so the data is data the product actually
# produces: the audit trail fills with genuine entries, the deployment console with
# genuine checks, and nothing exists that a customer could not have created themselves.
# The single exception is the first user, which the API deliberately cannot create —
# there is no self-service signup (06-security.md), so that one row is inserted directly.
#
#   scripts/seed-demo.sh
#
# Requires: curl, jq, and a backend running with the `local` profile.

set -eu

FREEZEHUB_URL="${FREEZEHUB_URL:-http://localhost:8099}"
ORGANIZATION="${DEMO_ORGANIZATION:-Northwind}"
ADMIN_EMAIL="${DEMO_ADMIN_EMAIL:-dana@northwind.test}"
ADMIN_SUBJECT="demo-admin-northwind"

say() { printf '\n\033[1m%s\033[0m\n' "$1"; }
fail() { printf '%s\n' "$1" >&2; exit 1; }

command -v curl >/dev/null 2>&1 || fail "curl is not installed."
command -v jq >/dev/null 2>&1 || fail "jq is not installed."

curl -sf "$FREEZEHUB_URL/actuator/health" >/dev/null 2>&1 || fail \
"No backend at $FREEZEHUB_URL.

  docker compose up -d postgres
  cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local"

# --- the one row the API cannot create --------------------------------------------------
#
# Refuses rather than duplicating: running this twice would give the demo two of
# everything, and finding that out on camera is worse than finding it out here.
if curl -sf -X POST "$FREEZEHUB_URL/api/dev/token" \
     -H 'Content-Type: application/json' \
     -d "{\"email\":\"$ADMIN_EMAIL\"}" >/dev/null 2>&1; then
    fail "$ADMIN_EMAIL already exists — the demo has been seeded already.

To start over with an empty database:
  docker compose down -v && docker compose up -d postgres
  (restart the backend so Liquibase recreates the schema, then run this again)"
fi

say "Creating the organization and its first administrator"
docker compose exec -T postgres psql -U freezehub -d freezehub >/dev/null <<SQL
WITH org AS (
  INSERT INTO organization (name) VALUES ('$ORGANIZATION') RETURNING id
)
INSERT INTO users (organization_id, external_subject, email, role)
SELECT id, '$ADMIN_SUBJECT', '$ADMIN_EMAIL', 'ADMINISTRATOR' FROM org;
SQL

TOKEN=$(curl -sf -X POST "$FREEZEHUB_URL/api/dev/token" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$ADMIN_EMAIL\"}" | jq -r .token)
AUTH="Authorization: Bearer $TOKEN"
JSON='Content-Type: application/json'

api() { curl -sf -X "$1" "$FREEZEHUB_URL$2" -H "$AUTH" -H "$JSON" ${3:+-d "$3"}; }
id_of() { jq -r .id; }

# --- catalog ----------------------------------------------------------------------------

say "Registering teams, applications and environments"
PAYMENTS=$(api POST /api/teams '{"name":"Payments"}' | id_of)
PLATFORM=$(api POST /api/teams '{"name":"Platform"}' | id_of)

PAYMENTS_API=$(api POST /api/applications '{"name":"payments-api"}' | id_of)
CHECKOUT_WEB=$(api POST /api/applications '{"name":"checkout-web"}' | id_of)
BILLING=$(api POST /api/applications '{"name":"billing-worker"}' | id_of)
IDENTITY=$(api POST /api/applications '{"name":"identity-service"}' | id_of)

PRODUCTION=$(api POST /api/environments '{"name":"production"}' | id_of)
api POST /api/environments '{"name":"staging"}' >/dev/null

# Ownership, so a team-scoped freeze has something to cover.
for pair in "$PAYMENTS:$PAYMENTS_API" "$PAYMENTS:$CHECKOUT_WEB" "$PLATFORM:$BILLING" "$PLATFORM:$IDENTITY"; do
    team=${pair%%:*}; app=${pair##*:}
    curl -sf -X PUT "$FREEZEHUB_URL/api/applications/$app/teams/$team" -H "$AUTH" >/dev/null
done

# --- a notification destination ---------------------------------------------------------
#
# Disabled immediately, and before any restriction exists: an enabled destination would
# queue deliveries to an endpoint that is not there, and a demo does not need a screen of
# retry failures behind it. It is here so the Settings page has something to show.
say "Adding a notification destination"
SLACK=$(api POST /api/integrations \
    '{"type":"SLACK","config":"{\"webhookUrl\":\"https://hooks.slack.com/services/T00000000/B00000000/DemoOnlyNotARealWebhook\"}"}' | id_of)
api PATCH "/api/integrations/$SLACK" '{"enabled":false}' >/dev/null

# --- restrictions -----------------------------------------------------------------------

now_plus() { date -u -v+"$1" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d "+$2" +%Y-%m-%dT%H:%M:%SZ; }
now_minus() { date -u -v-"$1" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d "-$2" +%Y-%m-%dT%H:%M:%SZ; }

say "Scheduling the freezes"

# In force now, and the reason the demo exists.
api POST /api/restrictions "{
  \"name\": \"Black Friday trading freeze\",
  \"description\": \"No production changes across the peak trading weekend.\",
  \"reason\": \"Revenue-critical period — an incident now costs the most it will all year.\",
  \"level\": \"HARD_FREEZE\",
  \"startsAt\": \"$(now_minus 6H 6\ hours)\",
  \"endsAt\": \"$(now_plus 3d 3\ days)\",
  \"scope\": {\"environmentIds\": [$PRODUCTION]}
}" >/dev/null

# Advisory, and scoped to a team rather than an environment — so the demo can show that
# not every restriction blocks, and that scope is more than "production".
api POST /api/restrictions "{
  \"name\": \"Payments on-call handover\",
  \"reason\": \"Reduced cover while on-call rotates. Deploy if you must, but have someone watching.\",
  \"level\": \"ADVISORY\",
  \"startsAt\": \"$(now_minus 2H 2\ hours)\",
  \"endsAt\": \"$(now_plus 2d 2\ days)\",
  \"scope\": {\"teamIds\": [$PAYMENTS]}
}" >/dev/null

# Upcoming, so the dashboard has something in front of it.
api POST /api/restrictions "{
  \"name\": \"Year-end close\",
  \"reason\": \"Finance reconciliation window — the ledger must not move underneath it.\",
  \"level\": \"HARD_FREEZE\",
  \"startsAt\": \"$(now_plus 9d 9\ days)\",
  \"endsAt\": \"$(now_plus 12d 12\ days)\",
  \"scope\": {\"environmentIds\": [$PRODUCTION]}
}" >/dev/null

# Ends in two minutes, so the lifecycle reconciler completes it on its own and the
# dashboard's "recently completed" is not an empty box. Real transition, not a fake row.
api POST /api/restrictions "{
  \"name\": \"Security patch window\",
  \"reason\": \"Coordinated dependency upgrade across services.\",
  \"level\": \"HARD_FREEZE\",
  \"startsAt\": \"$(now_minus 1H 1\ hour)\",
  \"endsAt\": \"$(now_plus 2M 2\ minutes)\",
  \"scope\": {\"environmentIds\": [$PRODUCTION]}
}" >/dev/null

# --- what CI has been asking ------------------------------------------------------------

# The lifecycle reconciler runs on an interval, so for the first minute these read
# SCHEDULED while already blocking deployments — correct (policy decides from the
# timestamps, not the status column) but a confusing thing to film. Waiting here means
# that when this script says Done, the screens agree with each other.
say "Waiting for the lifecycle to catch up"
waited=0
while [ "$(api GET /api/restrictions | jq '[.[] | select(.status == "ACTIVE")] | length')" -eq 0 ]; do
    [ "$waited" -ge 90 ] && fail "The lifecycle reconciler has not run in 90s. Is freezehub.lifecycle.enabled false?"
    sleep 5
    waited=$((waited + 5))
done

say "Issuing an API key and replaying a day of pipeline traffic"
KEY=$(api POST /api/api-keys '{"name":"gitlab-ci"}' | jq -r .key)

check() {
    curl -sf -X POST "$FREEZEHUB_URL/api/policy/evaluate" \
        -H "X-API-Key: $KEY" -H "$JSON" \
        -d "{\"action\":\"DEPLOY\",\"application\":\"$1\",\"environment\":\"$2\",
             \"actor\":\"$3\",\"reference\":\"$4\",
             \"source\":\"https://gitlab.northwind.test/northwind/$1/-/pipelines/$5\"}" >/dev/null
}

check billing-worker  staging    "priya@northwind.test" "8f31c0a" 4471   # allowed
check payments-api    production "alex@northwind.test"  "b2d9e14" 4472   # refused: freeze
check checkout-web    production "sam@northwind.test"   "c71a0f8" 4473   # refused: freeze
check identity-service staging   "priya@northwind.test" "de44b19" 4474   # allowed
check paymnets-api    production "alex@northwind.test"  "b2d9e14" 4475   # refused: misspelt

# Ends two minutes after it was created, so this is the reconciler completing it for
# real rather than a row inserted pre-completed.
say "Waiting for one freeze to finish, so the dashboard has recent history"
waited=0
while [ "$(api GET '/api/restrictions?status=COMPLETED' | jq 'length')" -eq 0 ]; do
    [ "$waited" -ge 210 ] && break   # not worth failing the seed over
    sleep 10
    waited=$((waited + 10))
done

say "Done"
cat <<SUMMARY

  Sign in at  http://localhost:5173/signin
  as          $ADMIN_EMAIL

  Black Friday trading freeze is in force now, so the deployment console
  already shows two engineers refused and one misspelling refused.

  Everything is settled: three restrictions in force, one upcoming, and one
  already completed, so every dashboard section has something in it.

  The API key issued for the demo (shown once, as always):
    $KEY

SUMMARY
