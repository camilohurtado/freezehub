#!/bin/sh
#
# Turns a demo into a customer (FZ-086).
#
# Creates an organization, puts it on an agreed plan, gives it a first Administrator, and
# marks the demo request it came from as converted.
#
#   scripts/provision-organization.sh --company "Northwind" \
#                                     --admin dana@northwind.test \
#                                     --plan GROWTH \
#                                     [--demo-request 7] \
#                                     [--applications 250]
#
# A SCRIPT AND NOT AN ADMIN CONSOLE, deliberately (D-23). The security model of this
# product is one sentence — the organization is always resolved from the credential, never
# from the request — and an in-product principal able to act across tenants is the exact
# negation of it. At this volume a script run by an operator is safer and cheaper, and it
# is reviewable in git.
#
# WHAT THIS NEEDS, and who can run it
# -----------------------------------
# Whoever runs this is not necessarily whoever wrote it, so:
#
#   * Database access. The first user of an organization cannot be created through the
#     API — there is no self-service signup (06-security.md), and inviting someone needs
#     an Administrator who does not exist yet. So the organization, that first user and
#     the subscription are inserted directly. Everything after the first Administrator
#     goes through the API.
#
#   * The backend reachable at FREEZEHUB_URL, and a psql that can reach its database.
#     Locally both come from docker compose. In a deployed environment that means an
#     operator with production database access, which is exactly the cost D-23 accepted.
#
#   * OI-2 IS NOT RESOLVED. Outside the `local` profile there is no real IdentityProvider,
#     so the invited user has no Cognito identity and cannot sign in. Until FZ-046 ships,
#     this script provisions correctly against a local backend and only half-provisions
#     against a deployed one. It says so at the end rather than pretending otherwise.
#
# Requires: curl, jq, docker compose (for psql), and a running backend.

set -eu

FREEZEHUB_URL="${FREEZEHUB_URL:-http://localhost:8099}"
COMPANY=""
ADMIN_EMAIL=""
PLAN=""
DEMO_REQUEST=""
APPLICATION_LIMIT=""

say()  { printf '\n\033[1m%s\033[0m\n' "$1"; }
note() { printf '  %s\n' "$1"; }
fail() { printf '%s\n' "$1" >&2; exit 1; }

usage() {
    fail "usage: provision-organization.sh --company NAME --admin EMAIL --plan PLAN
                                   [--demo-request ID] [--applications N]

  --plan          STARTER | GROWTH | SCALE | ENTERPRISE
  --demo-request  the demo_request row this closes, marked CONVERTED
  --applications  application limit override; ENTERPRISE only, since every other
                  plan's limit is a published number and not a per-deal one"
}

while [ $# -gt 0 ]; do
    case "$1" in
        --company)      COMPANY="${2:-}"; shift 2 ;;
        --admin)        ADMIN_EMAIL="${2:-}"; shift 2 ;;
        --plan)         PLAN="${2:-}"; shift 2 ;;
        --demo-request) DEMO_REQUEST="${2:-}"; shift 2 ;;
        --applications) APPLICATION_LIMIT="${2:-}"; shift 2 ;;
        -h|--help)      usage ;;
        *)              fail "Unknown argument: $1" ;;
    esac
done

[ -n "$COMPANY" ]     || usage
[ -n "$ADMIN_EMAIL" ] || usage
[ -n "$PLAN" ]        || usage

case "$PLAN" in
    STARTER|GROWTH|SCALE|ENTERPRISE) ;;
    # TRIAL is deliberately not provisionable: a trial is something an organization starts
    # for itself at signup (FZ-082), not something sales hands out.
    *) fail "Plan must be STARTER, GROWTH, SCALE or ENTERPRISE — got '$PLAN'." ;;
esac

if [ -n "$APPLICATION_LIMIT" ] && [ "$PLAN" != "ENTERPRISE" ]; then
    fail "--applications applies to ENTERPRISE only.

Every other plan's application limit is a published number (11-commercial.md). Overriding
one here would mean a customer paying for Starter with a limit nobody can look up, and the
pricing page quietly becoming untrue."
fi

command -v curl >/dev/null 2>&1 || fail "curl is not installed."
command -v jq   >/dev/null 2>&1 || fail "jq is not installed."

curl -sf "$FREEZEHUB_URL/actuator/health" >/dev/null 2>&1 || fail \
"No backend at $FREEZEHUB_URL.

  docker compose up -d postgres
  cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local"

psql() { docker compose exec -T postgres psql -U freezehub -d freezehub "$@"; }
scalar() { psql -tAc "$1" | tr -d '[:space:]'; }

# Single-quotes are the whole risk in a script that builds SQL: a company called
# O'Brien Ltd would otherwise end the string and run whatever came next. Doubling them is
# the SQL escape, and it is applied to every value that reaches a statement below.
sql_quote() { printf '%s' "$1" | sed "s/'/''/g"; }

COMPANY_SQL=$(sql_quote "$COMPANY")
ADMIN_SQL=$(sql_quote "$ADMIN_EMAIL")

# --- refuse to run twice ----------------------------------------------------------------
#
# Two ways this could duplicate, so both are checked. Provisioning the same customer twice
# gives them two organizations, and the one they are told about is not necessarily the one
# their pipeline authenticates into.
EXISTING_ORG=$(scalar "SELECT id FROM organization WHERE name = '$COMPANY_SQL' LIMIT 1")
[ -z "$EXISTING_ORG" ] || fail \
"An organization named '$COMPANY' already exists (id $EXISTING_ORG).

If this is a second organization for the same customer, give it a distinct name. If the
first attempt failed part-way, inspect it before running this again."

EXISTING_USER=$(scalar "SELECT organization_id FROM users WHERE lower(email) = lower('$ADMIN_SQL') LIMIT 1")
[ -z "$EXISTING_USER" ] || fail \
"$ADMIN_EMAIL already belongs to organization $EXISTING_USER.

A person exists once. Inviting them into a second organization is a different feature, and
not one this product has."

if [ -n "$DEMO_REQUEST" ]; then
    REQUEST_STATUS=$(scalar "SELECT status FROM demo_request WHERE id = $DEMO_REQUEST")
    [ -n "$REQUEST_STATUS" ] || fail "There is no demo request with id $DEMO_REQUEST."
    [ "$REQUEST_STATUS" != "CONVERTED" ] || fail \
"Demo request $DEMO_REQUEST is already marked CONVERTED. It has been provisioned once."
fi

# --- the rows the API cannot create -----------------------------------------------------
say "Creating $COMPANY on $PLAN"

# One statement, so a failure part-way leaves nothing behind. An organization with no
# administrator is unreachable, and one with no subscription is unbilled — both are worse
# than not having run at all.
psql >/dev/null <<SQL
BEGIN;

WITH org AS (
  INSERT INTO organization (name) VALUES ('$COMPANY_SQL') RETURNING id
), admin AS (
  INSERT INTO users (organization_id, external_subject, email, role)
  SELECT id, 'provisioned-' || id || '-' || md5(random()::text), '$ADMIN_SQL', 'ADMINISTRATOR'
  FROM org
)
INSERT INTO subscription (organization_id, plan, status, application_limit_override)
SELECT id, '$PLAN', 'ACTIVE', $( [ -n "$APPLICATION_LIMIT" ] && printf '%s' "$APPLICATION_LIMIT" || printf 'NULL' )
FROM org;

COMMIT;
SQL

ORGANIZATION_ID=$(scalar "SELECT id FROM organization WHERE name = '$COMPANY_SQL'")
[ -n "$ORGANIZATION_ID" ] || fail "Provisioning did not create an organization. Nothing was committed."

note "organization  $ORGANIZATION_ID"
note "administrator $ADMIN_EMAIL"
note "plan          $PLAN${APPLICATION_LIMIT:+ (limit $APPLICATION_LIMIT applications)}"

# --- close the loop on the demo request -------------------------------------------------
if [ -n "$DEMO_REQUEST" ]; then
    say "Marking demo request $DEMO_REQUEST converted"
    psql >/dev/null <<SQL
UPDATE demo_request
   SET status = 'CONVERTED', converted_organization_id = $ORGANIZATION_ID, updated_at = now()
 WHERE id = $DEMO_REQUEST;
SQL
    note "demo request $DEMO_REQUEST -> organization $ORGANIZATION_ID"
fi

# --- confirm it is actually usable ------------------------------------------------------
#
# Read back through the API rather than trusting the inserts. It is the only check that
# proves the organization resolves from a credential, which is what every other request
# will depend on.
say "Verifying"

TOKEN=$(curl -sf -X POST "$FREEZEHUB_URL/api/dev/token" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$ADMIN_EMAIL\"}" 2>/dev/null | jq -r '.token // empty')

if [ -n "$TOKEN" ]; then
    PLAN_SEEN=$(curl -sf "$FREEZEHUB_URL/api/billing/subscription" \
        -H "Authorization: Bearer $TOKEN" | jq -r '.plan // empty')
    [ "$PLAN_SEEN" = "$PLAN" ] || fail \
"The organization was created but reports plan '$PLAN_SEEN' rather than '$PLAN'. Inspect it."
    note "signed in and read back plan $PLAN_SEEN"
else
    # /api/dev/token only exists under the `local` profile, so this is the expected path in
    # a deployed environment rather than a failure.
    note "could not mint a token — expected unless this backend runs the local profile"
fi

say "Done"
cat <<SUMMARY
  $COMPANY is provisioned on $PLAN, and $ADMIN_EMAIL is its Administrator.

  Before telling the customer:

    * They cannot sign in yet unless this backend runs the "local" profile. There is no
      real identity provider (OI-2 / FZ-046), so no Cognito user was created and no
      invitation email was sent. That is the one step this script cannot do.

    * Further users are invited in-product by the Administrator, not by running this
      again — POST /api/invites, or Settings in the UI.
SUMMARY
