import { useCallback, useMemo, useState, type ReactNode } from 'react'
import { AuthContext, type AuthContextValue } from './authContext'

/**
 * Holds the bearer token for the session.
 *
 * `sessionStorage` so a page reload does not sign the user out, but a closed tab does.
 * Reads and writes are guarded: storage throws in some privacy modes, and failing to
 * remember a token is far better than failing to render.
 */
const STORAGE_KEY = 'freezehub.token'

function readStoredToken(): string | null {
  try {
    return sessionStorage.getItem(STORAGE_KEY)
  } catch {
    return null
  }
}

function writeStoredToken(token: string | null): void {
  try {
    if (token === null) sessionStorage.removeItem(STORAGE_KEY)
    else sessionStorage.setItem(STORAGE_KEY, token)
  } catch {
    // Non-fatal: the token still works for this page's lifetime.
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState<string | null>(() => readStoredToken())

  const signIn = useCallback((next: string) => {
    writeStoredToken(next)
    setToken(next)
  }, [])

  const signOut = useCallback(() => {
    writeStoredToken(null)
    setToken(null)
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({ token, isAuthenticated: token !== null, signIn, signOut }),
    [token, signIn, signOut],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
