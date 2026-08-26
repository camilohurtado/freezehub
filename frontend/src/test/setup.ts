import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

/*
 * Testing Library only auto-registers its cleanup when Vitest runs with `globals: true`.
 * This project does not, so without this every test would leave its DOM behind and later
 * tests would match elements rendered by earlier ones.
 */
afterEach(() => {
  cleanup()
})
