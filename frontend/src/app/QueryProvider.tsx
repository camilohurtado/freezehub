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
              /*
               * A timeout is not retried at all (`FZ-124`), and this was decided by
               * watching it rather than by reasoning about it.
               *
               * The arithmetic argued against retrying on its own: a timeout has already
               * cost its whole deadline before it is reported, so the default two retries
               * meant a minute of spinner before the person saw a word. What settled it
               * was that retrying *once* left the query in its pending state indefinitely
               * in the running application — both attempts were made and abandoned, and
               * the screen still said "Loading restrictions…" a minute later. With no
               * retry the same page shows the message and a "Try again" button at twenty
               * seconds, every time.
               *
               * A spinner that never resolves is precisely the defect this story exists to
               * remove, so the behaviour that reliably avoids it wins. `Try again` puts
               * the retry back in the hands of the person, who can see what is happening.
               */
              if (error instanceof ApiError && error.isTimeout) {
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
