import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router'
import type { ReactElement } from 'react'
import { AuthProvider } from '../features/auth/AuthProvider'

/**
 * Renders a component at a route with the providers the app supplies, so tests exercise
 * it the way it actually runs.
 *
 * Retries are off: a test asserting an error state should not wait for TanStack Query to
 * exhaust attempts first.
 */
export function renderRoute(
  element: ReactElement,
  options: { path?: string; token?: string | null } = {},
) {
  const { path = '/', token = 'test-token' } = options

  if (token === null) {
    sessionStorage.removeItem('freezehub.token')
  } else {
    sessionStorage.setItem('freezehub.token', token)
  }

  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })

  const router = createMemoryRouter(
    [
      { path, element },
      { path: '/signin', element: <div>Sign in screen</div> },
    ],
    { initialEntries: [path] },
  )

  return render(
    <AuthProvider>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </AuthProvider>,
  )
}
