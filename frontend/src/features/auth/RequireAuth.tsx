import { Navigate, useLocation } from 'react-router'
import type { ReactNode } from 'react'
import { useAuth } from './authContext'

/**
 * Gates authenticated routes. Without a token the user is sent to sign-in rather than
 * shown an empty page that will only fail on its first request (05-frontend.md).
 *
 * The attempted location is carried along so sign-in can return the user to it.
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { isAuthenticated } = useAuth()
  const location = useLocation()

  if (!isAuthenticated) {
    return <Navigate to="/signin" replace state={{ from: location.pathname }} />
  }

  return <>{children}</>
}
