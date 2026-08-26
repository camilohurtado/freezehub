import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, test } from 'vitest'
import { RequireAuth } from './RequireAuth'
import { renderRoute } from '../../test/renderRoute'

describe('RequireAuth', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  test('renders the page when a token is present', () => {
    renderRoute(
      <RequireAuth>
        <div>Protected content</div>
      </RequireAuth>,
      { token: 'a-token' },
    )

    expect(screen.getByText('Protected content')).toBeInTheDocument()
  })

  test('redirects to sign-in when there is no token', async () => {
    // Not "renders nothing" — an unauthenticated visitor must land somewhere useful
    // rather than on a blank page that only fails on its first request.
    renderRoute(
      <RequireAuth>
        <div>Protected content</div>
      </RequireAuth>,
      { token: null },
    )

    // Awaited: <Navigate> redirects in an effect, so the destination is not rendered
    // synchronously.
    expect(await screen.findByText('Sign in screen')).toBeInTheDocument()
    expect(screen.queryByText('Protected content')).not.toBeInTheDocument()
  })
})
