# The image every connector that cannot run a script from the customer's checkout uses
# (FZ-091).
#
# Not a convenience. Argo CD's PreSync hook is a Kubernetes Job, so it needs an image
# whatever else happens; once that exists, the GitLab component uses it instead of
# running `apk add curl jq` on every pipeline run, and anyone on a CI system with no
# connector has a one-line answer.
#
#   docker build -t freeze-check connectors/
#   docker run --rm -e FREEZEHUB_URL=... -e FREEZEHUB_API_KEY=... \
#              -e FREEZEHUB_APPLICATION=payments-api -e FREEZEHUB_ENVIRONMENT=production \
#              freeze-check

FROM alpine:3.20

# Everything the gate needs and nothing else. Pinning the Alpine minor rather than
# tracking `latest` so a customer's pipeline does not change underneath them on a
# Tuesday — the whole point of this image is being boring.
RUN apk add --no-cache curl jq

# Not root. The gate reads environment variables and makes one HTTP call; it has no
# business being able to write anywhere.
RUN addgroup -S freezehub && adduser -S -G freezehub freezehub

# On PATH and named for what it does, so a pipeline step reads `freeze-check` rather
# than a path into a container someone has to go and look up.
COPY --chmod=0755 freeze-check.sh /usr/local/bin/freeze-check

USER freezehub

# `exec` form: the script is PID 1 and its exit code is the container's exit code,
# which is the entire contract (0 allowed, 1 blocked, 2 not evaluated). A shell
# wrapper here would be a way to lose that.
ENTRYPOINT ["freeze-check"]
