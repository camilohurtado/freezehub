import { createContext, useContext } from 'react'

/**
 * Context and hook live apart from the provider component: React Fast Refresh only works
 * when a module exports components exclusively, so mixing them costs hot reloading.
 */
export interface AuthContextValue {
  token: string | null
  isAuthenticated: boolean
  signIn: (token: string) => void
  signOut: () => void
}

export const AuthContext = createContext<AuthContextValue | undefined>(undefined)

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used within an AuthProvider')
  return context
}
