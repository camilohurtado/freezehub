import { QueryCache, QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useMemo, type ReactNode } from 'react'
import { ApiError } from '../api/client'
import { useAuth } from '../features/auth/authContext'

/**
 * Owns all server state, and handles the one error every query shares.
 *
 * A `401` means the token is missing, expired, or resolves to no user. Clearing it here —
 * once, centrally — flips `RequireAuth` and sends the user to sign-in, so no page has to
 * handle expiry itself (05-frontend.md).
 */
export function QueryProvider({ children }: { children: ReactNode }) {
  const { signOut } = useAuth()

  const queryClient = useMemo(
    () =>
      new QueryClient({
        queryCache: new QueryCache({
          onError: (error) => {
            if (error instanceof ApiError && error.isUnauthorized) {
              signOut()
            }
          },
        }),
        defaultOptions: {
          queries: {
            // Retrying a rejection the server will repeat just delays the message.
            retry: (failureCount, error) => {
              if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
                return false
              }
              return failureCount < 2
            },
            refetchOnWindowFocus: false,
          },
        },
      }),
    [signOut],
  )

  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
}
