# frontend

React + TypeScript (Vite) application.

Bootstrapped in `FZ-003`. **Read [`../docs/05-frontend.md`](../docs/05-frontend.md) before writing UI** — it specifies the MVP routes, page responsibilities, API interaction conventions, the UTC/time-zone rule, and the styling approach. See also `../docs/02-architecture.md` for the target stack and `../CLAUDE.md` §8 for commands.

Styling is **CSS Modules with native form controls** — no UI framework, no styling dependency (`FZ-030`).

## Quick start

```bash
npm install
npm run dev      # http://localhost:5173
npm run build    # type-check + production build
npm run lint
npm run test
```

## Running against the backend

```bash
docker compose up -d postgres                                   # repo root
(cd ../backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local)
npm run dev                                                     # http://localhost:5173
```

Sign in at `/signin` with the email of an existing user (see `../backend/README.md` § Signing in during development). The API base URL comes from `VITE_API_BASE_URL`, defaulting to `http://localhost:8080`.

**If the browser reports a CORS error**, the backend has to allow the origin the UI is served from. The `local` profile allows `http://localhost:5173` and `:5174`; for any other port set `FREEZEHUB_CORS_ALLOWED_ORIGINS` when starting the backend.

## Current structure

```text
src/
├── app/              # App, providers, router, layout, not-found
├── api/              # fetch wrapper (ApiError) + typed endpoint functions
├── features/
│   ├── auth/         # token context, route guard, dev sign-in
│   └── dashboard/    # FZ-031
├── types/            # request/response types mirroring the API
├── utils/            # UTC-aware date formatting
├── test/             # setup + render helper
├── main.tsx
└── index.css
```

`features/`, `components/`, `api/`, `hooks/`, `types/`, and `utils/` (per `../docs/05-frontend.md`) are added just-in-time as real feature work needs them, starting with `FZ-031`+ (UI slices).

Note: `FZ-035` (a dev-only sign-in token endpoint in the backend) is required before any authenticated page can be rendered locally — no Cognito user pool exists until `FZ-063`.
