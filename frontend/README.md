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

## Current structure

```text
src/
├── app/       # root App, router, routes
├── main.tsx
└── index.css
```

`features/`, `components/`, `api/`, `hooks/`, `types/`, and `utils/` (per `../docs/05-frontend.md`) are added just-in-time as real feature work needs them, starting with `FZ-031`+ (UI slices).

Note: `FZ-035` (a dev-only sign-in token endpoint in the backend) is required before any authenticated page can be rendered locally — no Cognito user pool exists until `FZ-063`.
