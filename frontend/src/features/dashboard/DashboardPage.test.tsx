import { screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { DashboardPage } from './DashboardPage'
import { renderRoute } from '../../test/renderRoute'
import type {
  DeploymentCheckSummary,
  RestrictionDetail,
  RestrictionSummary,
} from '../../types/api'

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

const emptySummary: DeploymentCheckSummary = {
  today: { total: 0, allowed: 0, refused: 0 },
  applications: { seen: 0, total: 0 },
  daily: [],
  refusalsByRestriction: [],
}

interface World {
  live?: RestrictionSummary[]
  completed?: RestrictionSummary[]
  details?: Record<number, RestrictionDetail>
  environments?: { id: number; name: string }[]
  summary?: DeploymentCheckSummary
  /** Force a status on the restriction list requests. */
  status?: number
}

/**
 * Network is stubbed at the fetch boundary, so no backend is required. Routed by URL
 * rather than by call order, because the page now issues five different requests and
 * some of them depend on the answer to an earlier one.
 */
function stubWorld(world: World = {}) {
  const spy = vi.fn((input: RequestInfo | URL) => {
    const url = String(input)
    const json = (body: unknown, status = 200) =>
      Promise.resolve(
        new Response(JSON.stringify(body), {
          status,
          headers: { 'Content-Type': 'application/json' },
        }),
      )

    if (url.includes('/api/deployment-checks/summary')) {
      return json(world.summary ?? emptySummary)
    }
    if (url.includes('/api/environments')) {
      return json(
        (world.environments ?? [{ id: 1, name: 'production' }, { id: 2, name: 'staging' }]).map(
          (entry) => ({ ...entry, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' }),
        ),
      )
    }
    // A single restriction by id — the scope lookup behind the status line.
    const byId = url.match(/\/api\/restrictions\/(\d+)/)
    if (byId) {
      const detail = world.details?.[Number(byId[1])]
      return detail ? json(detail) : json({ message: 'Not found' }, 404)
    }
    if (url.includes('/api/restrictions')) {
      if (world.status && world.status !== 200) return json({ message: 'Boom' }, world.status)
      return json(url.includes('status=ACTIVE') ? (world.live ?? []) : (world.completed ?? []))
    }
    return json([])
  })
  vi.stubGlobal('fetch', spy)
  return spy
}

function activeFreeze(over: Partial<RestrictionSummary> = {}) {
  return restriction({
    id: 1,
    name: 'Black Friday Freeze',
    status: 'ACTIVE',
    level: 'HARD_FREEZE',
    startsAt: '2020-01-01T00:00:00Z',
    endsAt: '2099-12-02T09:00:00Z',
    ...over,
  })
}

function detailFor(summary: RestrictionSummary, environmentIds: number[]): RestrictionDetail {
  return {
    ...summary,
    description: null,
    scope: { teamIds: [], applicationIds: [], environmentIds },
  }
}

describe('DashboardPage', () => {
  beforeEach(() => sessionStorage.clear())
  afterEach(() => vi.unstubAllGlobals())

  test('shows a loading state while restrictions are being fetched', () => {
    stubWorld()
    renderRoute(<DashboardPage />)
    expect(screen.getByRole('status')).toHaveTextContent(/loading/i)
  })

  test('surfaces an error with a way to retry', async () => {
    stubWorld({ status: 500 })
    renderRoute(<DashboardPage />)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/could not load restrictions/i)
    expect(screen.getByRole('button', { name: /try again/i })).toBeInTheDocument()
  })

  test('names the environments a freeze in force is blocking', async () => {
    // The status line is the one sentence the page asserts rather than displays, and it
    // has to name what it claims — which means resolving scope ids to catalog names.
    const active = activeFreeze()
    stubWorld({ live: [active], details: { 1: detailFor(active, [1, 2]) } })

    renderRoute(<DashboardPage />)

    expect(
      await screen.findByText('Deploys are blocked in production and staging'),
    ).toBeInTheDocument()
    expect(screen.getByText(/^In force now$/i)).toBeInTheDocument()
  })

  test('an empty environment scope is reported as every environment, not as none', async () => {
    // FZ-020's wildcard rule, in the sentence people read first.
    const active = activeFreeze()
    stubWorld({ live: [active], details: { 1: detailFor(active, []) } })

    renderRoute(<DashboardPage />)

    expect(await screen.findByText('Deploys are blocked in every environment')).toBeInTheDocument()
  })

  test('an advisory in force does not claim deploys are blocked', async () => {
    // An advisory is reported, not enforced. Saying otherwise on the headline would be
    // the most consequential thing this page could get wrong.
    const advisory = activeFreeze({ level: 'ADVISORY' })
    stubWorld({ live: [advisory] })

    renderRoute(<DashboardPage />)

    expect(await screen.findByText('No restriction is blocking deploys.')).toBeInTheDocument()
    expect(screen.queryByText(/deploys are blocked in/i)).not.toBeInTheDocument()
  })

  test('separates active from upcoming restrictions', async () => {
    stubWorld({
      live: [
        restriction({ id: 1, name: 'Running now', status: 'ACTIVE', level: 'ADVISORY' }),
        restriction({ id: 2, name: 'Starts later', status: 'SCHEDULED' }),
      ],
    })

    renderRoute(<DashboardPage />)

    const active = await screen.findByRole('region', { name: /active now/i })
    const upcoming = screen.getByRole('region', { name: /upcoming/i })

    expect(within(active).getByText('Running now')).toBeInTheDocument()
    expect(within(upcoming).getByText('Starts later')).toBeInTheDocument()
    expect(within(active).queryByText('Starts later')).not.toBeInTheDocument()
  })

  test('links each restriction to its detail route', async () => {
    stubWorld({ live: [restriction({ id: 42, name: 'Linked' })] })
    renderRoute(<DashboardPage />)

    expect(await screen.findByRole('link', { name: 'Linked' })).toHaveAttribute(
      'href',
      '/restrictions/42',
    )
  })

  test('draws the four metrics from the check summary', async () => {
    stubWorld({
      live: [restriction({ id: 2, name: 'Later', status: 'SCHEDULED' })],
      summary: {
        today: { total: 86, allowed: 77, refused: 9 },
        applications: { seen: 11, total: 14 },
        daily: [],
        refusalsByRestriction: [],
      },
    })

    renderRoute(<DashboardPage />)

    const metrics = await screen.findByRole('region', { name: /at a glance/i })
    expect(within(metrics).getByText('86')).toBeInTheDocument()
    expect(within(metrics).getByText('9 refused, 77 allowed')).toBeInTheDocument()
    expect(within(metrics).getByText('11')).toBeInTheDocument()
    expect(within(metrics).getByText('of 14 applications')).toBeInTheDocument()
  })

  test('the completed table shows what each restriction refused', async () => {
    const done = restriction({ id: 7, name: 'Peak trading rehearsal', status: 'COMPLETED' })
    stubWorld({
      completed: [done],
      summary: { ...emptySummary, refusalsByRestriction: [{ restrictionId: 7, refused: 14 }] },
    })

    renderRoute(<DashboardPage />)

    const group = await screen.findByRole('region', { name: /recently completed/i })
    const row = within(group).getByRole('row', { name: /peak trading rehearsal/i })
    expect(within(row).getByText('14')).toBeInTheDocument()
  })

  test('a completed restriction that refused nothing shows zero, not blank', async () => {
    const done = restriction({ id: 7, name: 'Payments incident', status: 'COMPLETED' })
    stubWorld({ completed: [done], summary: emptySummary })

    renderRoute(<DashboardPage />)

    const group = await screen.findByRole('region', { name: /recently completed/i })
    const row = within(group).getByRole('row', { name: /payments incident/i })
    expect(within(row).getByText('0')).toBeInTheDocument()
  })

  test('caps recently completed restrictions and shows the newest first', async () => {
    // The backend orders soonest-first and applies no recency window, so the cap and the
    // reversal are this page's job.
    const completed = Array.from({ length: 8 }, (_, index) =>
      restriction({ id: 100 + index, name: `Done ${index}`, status: 'COMPLETED' }),
    )
    stubWorld({ completed })

    renderRoute(<DashboardPage />)

    const group = await screen.findByRole('region', { name: /recently completed/i })
    await waitFor(() => {
      // One row per restriction, plus the header row.
      expect(within(group).getAllByRole('row')).toHaveLength(6)
    })
    expect(within(group).getByText('Done 7')).toBeInTheDocument()
    expect(within(group).queryByText('Done 2')).not.toBeInTheDocument()
  })

  test('says what happens next, ending with "Clear from here."', async () => {
    const active = activeFreeze()
    stubWorld({ live: [active], details: { 1: detailFor(active, [1]) } })

    renderRoute(<DashboardPage />)

    const thenWhat = await screen.findByRole('region', { name: /then what/i })
    expect(
      within(thenWhat).getByText('Black Friday Freeze completes. Deploys reopen.'),
    ).toBeInTheDocument()
    expect(within(thenWhat).getByText('Clear from here.')).toBeInTheDocument()
  })

  test('sends the bearer token with its requests', async () => {
    stubWorld()
    renderRoute(<DashboardPage />, { token: 'a-real-token' })

    await waitFor(() => expect(fetch).toHaveBeenCalled())
    const [, init] = vi.mocked(fetch).mock.calls[0]
    const headers = (init as RequestInit).headers as Record<string, string>
    expect(headers.Authorization).toBe('Bearer a-real-token')
  })
})
