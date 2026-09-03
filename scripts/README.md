# scripts

## `seed-demo.sh`

Builds a believable organization to demonstrate against — teams, applications, freezes in
force and upcoming, an API key, and a history of pipeline checks including two engineers
refused during a freeze.

```bash
./scripts/seed-demo.sh
```

Everything goes through the **real API**, so the audit trail and deployment console fill
with genuine entries rather than fabricated rows. The single exception is the first user:
the API deliberately cannot create one, because there is no self-service signup
(`../docs/06-security.md`), so that one row is inserted directly.

It refuses to run twice rather than duplicating the demo. To start over:

```bash
docker compose down -v && docker compose up -d postgres
# restart the backend so Liquibase recreates the schema, then seed again
```

Takes about three minutes, most of it waiting for the lifecycle reconciler so that every
screen agrees with every other before it returns.

**It also solves an ordinary problem:** an empty database has no users at all, so
`POST /api/dev/token` returns `404` and nobody can sign in. Running this after a
`docker compose down -v` makes a fresh checkout usable.

See [`../docs/10-demo.md`](../docs/10-demo.md) for the walkthrough it was built for.
