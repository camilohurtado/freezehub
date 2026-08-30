#!/bin/sh
#
# Ask FreezeHub whether this deployment may proceed (FZ-053).
#
# Run it as a gate before your deploy step. It is plain POSIX shell so it drops
# into any runner; see gitlab-ci.yml and README.md for wiring.
#
#   Exit 0  allowed        — deploy
#   Exit 1  blocked        — a restriction is in force, or a name is unregistered
#   Exit 2  not evaluated  — misconfiguration, or FreezeHub could not be asked
#                            while FREEZEHUB_ON_ERROR=block
#
# Requires: curl, jq.

set -eu

# FreezeHub cannot express "I could not be asked", so the pipeline has to decide
# what silence means. Neither answer is safe in general: fail open and an outage
# during a freeze lets deployments through; fail closed and a FreezeHub outage
# stops every deployment. Blocking is the default because this is a gate, and a
# gate that opens when it breaks is not a gate — but the choice is yours, and it
# is deliberately spelled out here rather than inherited from curl's behaviour.
FREEZEHUB_ON_ERROR="${FREEZEHUB_ON_ERROR:-block}"
FREEZEHUB_TIMEOUT="${FREEZEHUB_TIMEOUT:-10}"

fail_setup() {
    echo "freeze-check: $1" >&2
    exit 2
}

not_evaluated() {
    echo "freeze-check: $1" >&2
    if [ "$FREEZEHUB_ON_ERROR" = "allow" ]; then
        echo "freeze-check: FREEZEHUB_ON_ERROR=allow — deploying WITHOUT a policy decision." >&2
        exit 0
    fi
    echo "freeze-check: FREEZEHUB_ON_ERROR=block — refusing to deploy without a policy decision." >&2
    exit 2
}

# A setup problem is never subject to FREEZEHUB_ON_ERROR. If a missing key or a
# typo'd URL could fail open, enforcement would be switchable off by breaking
# the configuration — which is the one thing a gate must not allow.
for required in FREEZEHUB_URL FREEZEHUB_API_KEY FREEZEHUB_APPLICATION FREEZEHUB_ENVIRONMENT; do
    eval "value=\${$required:-}"
    [ -n "$value" ] || fail_setup "$required is not set."
done

command -v curl >/dev/null 2>&1 || fail_setup "curl is not installed."
command -v jq >/dev/null 2>&1 || fail_setup "jq is not installed."

case "$FREEZEHUB_ON_ERROR" in
    block|allow) ;;
    *) fail_setup "FREEZEHUB_ON_ERROR must be 'block' or 'allow', got '$FREEZEHUB_ON_ERROR'." ;;
esac

body=$(mktemp)
trap 'rm -f "$body"' EXIT

request=$(jq -nc \
    --arg application "$FREEZEHUB_APPLICATION" \
    --arg environment "$FREEZEHUB_ENVIRONMENT" \
    '{action: "DEPLOY", application: $application, environment: $environment}')

echo "freeze-check: asking FreezeHub about $FREEZEHUB_APPLICATION -> $FREEZEHUB_ENVIRONMENT"

# --max-time matters more than it looks: without it, "fail closed" would really
# mean "hang until the job times out", which is worse than either choice above.
#
# The key is passed as an argument and is therefore visible in `ps` on a shared
# runner. Where that matters, feed it to curl on stdin instead:
#   printf 'header = "X-API-Key: %s"\n' "$FREEZEHUB_API_KEY" | curl --config - ...
set +e
status=$(curl --silent --show-error \
    --output "$body" --write-out '%{http_code}' \
    --connect-timeout 5 --max-time "$FREEZEHUB_TIMEOUT" \
    --request POST "$FREEZEHUB_URL/api/policy/evaluate" \
    --header "X-API-Key: $FREEZEHUB_API_KEY" \
    --header 'Content-Type: application/json' \
    --data "$request")
curl_status=$?
set -e

if [ "$curl_status" -ne 0 ]; then
    not_evaluated "FreezeHub could not be reached (curl exit $curl_status)."
fi

case "$status" in
    200) ;;
    400|401)
        # Not an outage: the request or the credential is wrong. Deliberately
        # not subject to FREEZEHUB_ON_ERROR — otherwise revoking a key would
        # silently disable the gate for every pipeline still using it.
        fail_setup "FreezeHub rejected the request (HTTP $status). Check FREEZEHUB_API_KEY and the application/environment names."
        ;;
    *)
        not_evaluated "FreezeHub returned HTTP $status."
        ;;
esac

decision=$(jq -r '.decision' "$body")
message=$(jq -r '.message' "$body")

case "$decision" in
    ALLOW)
        echo "freeze-check: ALLOW — $message"
        # Advisories do not block, but they are the reason someone wrote one.
        jq -r '.restrictions[]? | "  advisory: \(.name) — \(.reason)"' "$body"
        exit 0
        ;;
    BLOCK)
        echo "freeze-check: BLOCK — $message" >&2
        jq -r '.restrictions[]? | "  \(.level): \(.name) — \(.reason) (until \(.endsAt))"' "$body" >&2
        if [ "$(jq -r '.unregistered | length' "$body")" -gt 0 ]; then
            cat >&2 <<'HINT'

  FreezeHub does not have this application or environment registered, so it
  could not evaluate the restrictions that might apply. Register it, or correct
  the name — matching is exact, including case.
HINT
        fi
        exit 1
        ;;
    *)
        fail_setup "Unrecognised decision '$decision' from FreezeHub."
        ;;
esac
