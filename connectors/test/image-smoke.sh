#!/bin/sh
#
# Does the gate still behave when it is the container's entrypoint? (FZ-091)
#
# run-tests.js covers behaviour thoroughly, against the script, on the developer's own
# shell. This covers the three things that survive being packaged and can only break
# here: that busybox ash runs the script at all, that the exit code reaches the caller
# through the container boundary, and that it fails closed as a container just as it
# does as a script.
#
# Deliberately needs no network. Nothing listens on port 1 inside the container either,
# so "FreezeHub could not be reached" is provoked without any host networking, which is
# what would otherwise make this differ between a Linux runner and a laptop.
#
#   sh connectors/test/image-smoke.sh [image]

set -eu

IMAGE="${1:-freeze-check:test}"
UNREACHABLE="http://127.0.0.1:1"
failures=0

expect() {
    expected=$1
    name=$2
    shift 2
    set +e
    "$@" >/dev/null 2>&1
    actual=$?
    set -e
    if [ "$actual" -eq "$expected" ]; then
        echo "  ok   $name"
    else
        echo "  FAIL $name — expected exit $expected, got $actual"
        failures=$((failures + 1))
    fi
}

echo "$IMAGE"

expect 2 "an unreachable FreezeHub exits 2 by default" \
    docker run --rm \
        -e FREEZEHUB_URL="$UNREACHABLE" -e FREEZEHUB_API_KEY=fzh_test \
        -e FREEZEHUB_APPLICATION=payments-api -e FREEZEHUB_ENVIRONMENT=production \
        -e FREEZEHUB_TIMEOUT=3 "$IMAGE"

expect 0 "an unreachable FreezeHub exits 0 when explicitly asked to fail open" \
    docker run --rm \
        -e FREEZEHUB_URL="$UNREACHABLE" -e FREEZEHUB_API_KEY=fzh_test \
        -e FREEZEHUB_APPLICATION=payments-api -e FREEZEHUB_ENVIRONMENT=production \
        -e FREEZEHUB_TIMEOUT=3 -e FREEZEHUB_ON_ERROR=allow "$IMAGE"

expect 2 "a missing variable exits 2 even when asked to fail open" \
    docker run --rm \
        -e FREEZEHUB_URL="$UNREACHABLE" -e FREEZEHUB_API_KEY=fzh_test \
        -e FREEZEHUB_ENVIRONMENT=production \
        -e FREEZEHUB_ON_ERROR=allow "$IMAGE"

# GitLab Runner overrides the image's ENTRYPOINT and runs its own shell, then calls the
# job's script. That is why the gate is on PATH and not only the entrypoint — and why the
# GitLab component would break, in a way no other connector would, if it stopped being.
expect 2 "the gate is on PATH when the entrypoint is overridden, as GitLab runs it" \
    docker run --rm --entrypoint sh \
        -e FREEZEHUB_URL="$UNREACHABLE" -e FREEZEHUB_API_KEY=fzh_test \
        -e FREEZEHUB_APPLICATION=payments-api -e FREEZEHUB_ENVIRONMENT=production \
        -e FREEZEHUB_TIMEOUT=3 "$IMAGE" -c 'freeze-check'

# It reads environment variables and makes one HTTP call. Root would be a way to make a
# compromised runner's blast radius larger for no gain.
user=$(docker run --rm --entrypoint id "$IMAGE" -un)
if [ "$user" = "freezehub" ]; then
    echo "  ok   it runs as a non-root user"
else
    echo "  FAIL it runs as a non-root user — got '$user'"
    failures=$((failures + 1))
fi

echo
if [ "$failures" -gt 0 ]; then
    echo "$failures failure(s)"
    exit 1
fi
echo "all checks passed"
