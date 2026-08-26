/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    env: {
      // Deliberately NOT UTC. A datetime-local value that is submitted without being
      // converted to UTC looks perfectly correct on a UTC machine and is silently wrong
      // by the offset everywhere else — running the suite at UTC-5 is what makes that
      // class of bug fail a test instead of shipping (01-domain.md invariants 9 and 10).
      TZ: 'America/Bogota',
    },
  },
})
