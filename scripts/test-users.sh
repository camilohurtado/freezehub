#!/bin/sh
#
# Who can sign in locally, and what each one is for (FZ-102).
#
#   scripts/test-users.sh              # list every account
#   scripts/test-users.sh --add-member # add a MEMBER to an organization
#
# Reads the database rather than a document, because a list of test accounts written down
# is wrong the first time anybody seeds, provisions or resets. There is no password: under
# the `local` profile the sign-in page posts an email to /api/dev/token and gets a signed
# JWT back (FZ-035), so the email IS the credential.
#
# Requires: docker compose running Postgres. The backend does not need to be up to list.

set -eu

say()  { printf '\n\033[1m%s\033[0m\n' "$1"; }
fail() { printf '%s\n' "$1" >&2; exit 1; }

psql() { docker compose exec -T postgres psql -U freezehub -d freezehub "$@"; }

docker compose ps postgres >/dev/null 2>&1 || fail \
"PostgreSQL is not running.

  docker compose up -d postgres"

ADD_MEMBER=0
[ "${1:-}" = "--add-member" ] && ADD_MEMBER=1

if [ "$ADD_MEMBER" -eq 1 ]; then
    # A MEMBER is not optional for testing, it is the account that proves half of FZ-085:
    # the trial banner and the billing *read* reach every member, while the Stripe buttons
    # and the other Settings sections do not. An administrator cannot see that difference.
    ORG=$(psql -tAc "SELECT id FROM organization ORDER BY id LIMIT 1" | tr -d '[:space:]')
    [ -n "$ORG" ] || fail "There are no organizations yet. Run scripts/seed-demo.sh first."

    EMAIL="member-$(date +%s)@northwind.test"
    psql >/dev/null <<SQL
INSERT INTO users (organization_id, cognito_subject, email, role)
VALUES ($ORG, 'test-member-' || md5(random()::text), '$EMAIL', 'MEMBER');
SQL
    say "Added a MEMBER to organization $ORG"
    printf '  %s\n' "$EMAIL"
fi

say "Accounts you can sign in with"

psql -P pager=off -c "
SELECT u.email          AS \"sign in as\",
       u.role,
       o.name           AS organization,
       COALESCE(s.plan::text, '(no subscription)')   AS plan,
       COALESCE(s.status::text, '-')                 AS status,
       CASE WHEN s.trial_ends_at IS NULL THEN '-'
            ELSE to_char(s.trial_ends_at, 'YYYY-MM-DD') END AS \"trial ends\"
  FROM users u
  JOIN organization o ON o.id = u.organization_id
  LEFT JOIN subscription s ON s.organization_id = o.id
 ORDER BY o.id, u.id;"

# Say what is missing, because the gap is what stops somebody testing a case.
ADMINS=$(psql -tAc "SELECT count(*) FROM users WHERE role = 'ADMINISTRATOR'" | tr -d '[:space:]')
MEMBERS=$(psql -tAc "SELECT count(*) FROM users WHERE role = 'MEMBER'" | tr -d '[:space:]')
ORGS=$(psql -tAc "SELECT count(*) FROM organization" | tr -d '[:space:]')

say "What you can and cannot test with these"

if [ "$ORGS" -eq 0 ]; then
    printf '  %s\n' "Nothing — the database is empty. Run scripts/seed-demo.sh."
    exit 0
fi

[ "$ADMINS" -gt 0 ] \
    && printf '  %s\n' "Administrator view: yes" \
    || printf '  %s\n' "Administrator view: NO administrator exists — Settings is unreachable"

if [ "$MEMBERS" -gt 0 ]; then
    printf '  %s\n' "Member view: yes"
else
    printf '  %s\n' "Member view: none exists. Settings, the trial banner shown to everyone,"
    printf '  %s\n' "             and a 402 seen by a non-administrator cannot be checked."
    printf '  %s\n' "             Add one:  scripts/test-users.sh --add-member"
fi

[ "$ORGS" -gt 1 ] \
    && printf '  %s\n' "Tenant isolation: yes — $ORGS organizations, so one must not see the other" \
    || printf '  %s\n' "Tenant isolation: only one organization, so cross-tenant leakage is untestable"

cat <<'NOTE'

  Sign in at http://localhost:5173 — no password. The email is the credential, because
  /api/dev/token exists only under the `local` profile (FZ-035). In any deployed
  environment none of these accounts can sign in at all: there is no real identity
  provider yet (OI-2).
NOTE
