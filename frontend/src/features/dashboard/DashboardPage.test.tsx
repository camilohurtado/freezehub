import { screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { DashboardPage } from './DashboardPage'
import { renderRoute } from '../../test/renderRoute'
import type { RestrictionSummary } from '../../types/api'

/** Network is stubbed at the fetch boundary, so no backend is required. */
function stubFetch(handler: (url: string) => { status?: number; body?: unknown }) {
  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL) => {
      const url = String(input)
      const { status = 200, body = [] } = handler(url)
      return Promise.resolve(
        new Response(JSON.stringify(body), {
          status,
          headers: { 'Content-Type': 'application/json' },
        }),
      )
    }),
  )
}

function restriction(overrides: Partial<RestrictionSummary> = {}): RestrictionSummary {
  return {
    id: 1,
    name: 'Black Friday Freeze',
    reason: 'Revenue-critical period',
    type: 'DEPLOYMENT_FREEZE',
    level: 'HARD_FREEZE',
    status: 'SCHEDULED',
    startsAt: '2026-11-27T00:00:00Z',
    endsAt: '2026-12-02T00:00:00Z',
    createdBy: 1,
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z',
    ...overrides,
  }
}

/** The dashboard asks for ACTIVE+SCHEDULED in one request and COMPLETED in another. */
function isLiveQuery(url: string): boolean {
  return url.includes('status=ACTIVE')
}

describe('DashboardPage', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  test('shows a loading state while restrictions are being fetched', () => {
    stubFetch(() => ({ body: [] }))

    renderRoute(<DashboardPage />)

    expect(screen.getByRole('status')).toHaveTextContent(/loading/i)
  })

  test('tells the user nothing is blocking deploys when there is nothing at all', async () => {
    stubFetch(() => ({ body: [] }))

    renderRoute(<DashboardPage />)

    expect(await screen.findByText(/no deployment restrictions yet/i)).toBeInTheDocument()
  })

  test('surfaces an error with a way to retry', async () => {
    stubFetch(() => ({ status: 500, body: { message: 'Boom' } }))

    renderRoute(<DashboardPage />)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/could not load restrictions/i)
    expect(screen.getByRole('button', { name: /try again/i })).toBeInTheDocument()
  })

  test('separates active from upcoming restrictions', async () => {
    stubFetch((url) =>
      isLiveQuery(url)
        ? {
            body: [
              restriction({ id: 1, name: 'Running now', status: 'ACTIVE' }),
              restriction({ id: 2, name: 'Starts later', status: 'SCHEDULED' }),
            ],
          }
        : { body: [] },
    )

    renderRoute(<DashboardPage />)

    const active = await screen.findByRole('region', { name: /active now/i })
    const upcoming = screen.getByRole('region', { name: /upcoming/i })

    expect(within(active).getByText('Running now')).toBeInTheDocument()
    expect(within(upcoming).getByText('Starts later')).toBeInTheDocument()
    // Not merely present somewhere — in the *right* group.
    expect(within(active).queryByText('Starts later')).not.toBeInTheDocument()
  })

  test('distinguishes a blocking freeze from an advisory one', async () => {
    // The single most consequential thing an engineer reads off this page.
    stubFetch((url) =>
      isLiveQuery(url)
        ? {
            body: [
              restriction({ id: 1, name: 'Hard', level: 'HARD_FREEZE', status: 'ACTIVE' }),
              restriction({ id: 2, name: 'Soft', level: 'ADVISORY', status: 'ACTIVE' }),
            ],
          }
        : { body: [] },
    )

    renderRoute(<DashboardPage />)

    // Exact strings, not regexes: a substring regex also matches the enclosing element,
    // which makes the query ambiguous rather than precise.
    expect(await screen.findByText('Blocks deploys')).toBeInTheDocument()
    expect(screen.getByText('Advisory')).toBeInTheDocument()
  })

  test('links each restriction to its detail route', async () => {
    stubFetch((url) =>
      isLiveQuery(url) ? { body: [restriction({ id: 42, name: 'Linked' })] } : { body: [] },
    )

    renderRoute(<DashboardPage />)

    const link = await screen.findByRole('link', { name: 'Linked' })
    expect(link).toHaveAttribute('href', '/restrictions/42')
  })

  test('caps recently completed restrictions and shows the newest first', async () => {
    // The backend orders soonest-first and applies no recency window, so the cap and the
    // reversal are this page's job.
    const completed = Array.from({ length: 8 }, (_, index) =>
      restriction({ id: 100 + index, name: `Done ${index}`, status: 'COMPLETED' }),
    )
    stubFetch((url) => (isLiveQuery(url) ? { body: [] } : { body: completed }))

    renderRoute(<DashboardPage />)

    const group = await screen.findByRole('region', { name: /recently completed/i })
    await waitFor(() => {
      expect(within(group).getAllByRole('listitem')).toHaveLength(5)
    })
    expect(within(group).getByText('Done 7')).toBeInTheDocument()
    expect(within(group).queryByText('Done 2')).not.toBeInTheDocument()
  })

  test('sends the bearer token with its requests', async () => {
    stubFetch(() => ({ body: [] }))

    renderRoute(<DashboardPage />, { token: 'a-real-token' })

    await waitFor(() => {
      expect(fetch).toHaveBeenCalled()
    })
    const [, init] = vi.mocked(fetch).mock.calls[0]
    expect(init).toBeDefined()
    const headers = (init as RequestInit).headers as Record<string, string>
    expect(headers.Authorization).toBe('Bearer a-real-token')
  })
})
