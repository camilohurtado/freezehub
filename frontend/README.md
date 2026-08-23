# frontend

React + TypeScript (Vite) application.

Bootstrapped in `FZ-003`. See `../docs/02-architecture.md` for the target stack and feature-oriented layout, and `../CLAUDE.md` §8 for commands.

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

`features/`, `components/`, `api/`, `hooks/`, `types/`, and `utils/` (per `../docs/02-architecture.md`) are added just-in-time as real feature work needs them, starting with `FZ-030` (frontend specification) and `FZ-031`+ (UI slices).
