import { createBrowserRouter, Navigate } from 'react-router'
import { AppLayout } from './AppLayout'
import { NotFoundPage } from './NotFoundPage'
import { RequireAuth } from '../features/auth/RequireAuth'
import { SignInPage } from '../features/auth/SignInPage'
import { DashboardPage } from '../features/dashboard/DashboardPage'
import { RestrictionsPage } from '../features/restrictions/RestrictionsPage'

/**
 * MVP routes per 05-frontend.md. Routes arrive with the story that builds their page:
 * /restrictions is FZ-032, /restrictions/new FZ-033, /restrictions/:id FZ-034.
 */
export const routes = [
  { path: '/signin', element: <SignInPage /> },
  {
    path: '/',
    element: (
      <RequireAuth>
        <AppLayout />
      </RequireAuth>
    ),
    children: [
      { index: true, element: <Navigate to="/dashboard" replace /> },
      { path: 'dashboard', element: <DashboardPage /> },
      { path: 'restrictions', element: <RestrictionsPage /> },
    ],
  },
  { path: '*', element: <NotFoundPage /> },
]

export const router = createBrowserRouter(routes)
