import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { RestrictionDetailPage } from './RestrictionDetailPage'
import { renderRoute } from '../../test/renderRoute'
import type { RestrictionDetail, RestrictionStatus } from '../../types/api'

const entry = (id: number, name: string) => ({
  id,
  name,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
})

function detail(overrides: Partial<RestrictionDetail> = {}): RestrictionDetail {
  return {
    id: 7,
    name: 'Black Friday Freeze',
    description: 'No production deploys during peak trading.',
    reason: 'Revenue-critical period',
    type: 'DEPLOYMENT_FREEZE',
    level: 'HARD_FREEZE',
    status: 'SCHEDULED',
    startsAt: '2026-11-27T14:00:00.000Z',
    endsAt: '2026-12-02T14:00:00.000Z',
    createdBy: 1,
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z',
    scope: { teamIds: [], applicationIds: [], environmentIds: [] },
    ...overrides,
  }
}

function stubApi(restriction: RestrictionDetail | { status: number }, cancelStatus = 200) {
  const spy = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const json = (body: unknown, status = 200) =>
      new Response(JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/json' },
      })

    if (url.includes('/cancel')) {
      return Promise.resolve(
        cancelStatus === 200
          ? json(restriction)
          : json({ message: 'Only a SCHEDULED or ACTIVE restriction can be cancelled' }, cancelStatus),
      )
    }
    if (url.includes('/api/restrictions/')) {
      return 'status' in restriction && !('name' in restriction)
        ? Promise.resolve(json({ message: 'Restriction not found' }, restriction.status))
        : Promise.resolve(json(restriction))
    }
    if (url.includes('/api/teams')) return Promise.resolve(json([entry(1, 'Payments')]))
    if (url.includes('/api/applications')) {
      return Promise.resolve(json([{ ...entry(2, 'payments-api'), teamIds: [] }]))
    }
    if (url.includes('/api/environments')) return Promise.resolve(json([entry(3, 'production')]))
    void init
    return Promise.resolve(json([]))
  })
  vi.stubGlobal('fetch', spy)
  return spy
}

function render() {
  return renderRoute(<RestrictionDetailPage />, { path: '/restrictions/7', route: '/restrictions/:restrictionId' })
}

describe('RestrictionDetailPage', () => {
  beforeEach(() => sessionStorage.clear())
  afterEach(() => vi.unstubAllGlobals())

  test('shows the restriction with status, level, window and reason', async () => {
    const restriction = detail()
    stubApi(restriction)
    render()

    expect(await screen.findByRole('heading', { name: 'Black Friday Freeze' })).toBeInTheDocument()
    expect(screen.getByText('Revenue-critical period')).toBeInTheDocument()
    expect(screen.getByText('No production deploys during peak trading.')).toBeInTheDocument()
    expect(screen.getByText('scheduled')).toBeInTheDocument()
    expect(screen.getByText('Blocks deploys')).toBeInTheDocument()
  })

  test('resolves scope ids to names rather than showing raw numbers', async () => {
    const restriction = detail({
      scope: { teamIds: [1], applicationIds: [2], environmentIds: [3] },
    })
    stubApi(restriction)
    render()

    await waitFor(() => {
      expect(screen.getByText('Payments')).toBeInTheDocument()
      expect(screen.getByText('payments-api')).toBeInTheDocument()
      expect(screen.getByText('production')).toBeInTheDocument()
    })
  })

  test('shows an unconstrained dimension as "Any"', async () => {
    // The wildcard rule matters: an empty dimension means "any", not "nothing".
    const restriction = detail({
      scope: { teamIds: [], applicationIds: [], environmentIds: [3] },
    })
    stubApi(restriction)
    render()

    await waitFor(() => expect(screen.getAllByText('Any')).toHaveLength(2))
  })

  test.each<[RestrictionStatus, boolean, boolean]>([
    ['SCHEDULED', true, true],
    ['ACTIVE', false, true],
    ['COMPLETED', false, false],
    ['CANCELLED', false, false],
  ])('offers the right actions when %s', async (status, canEdit, canCancel) => {
    // Affordances mirror the backend rules: edit only while SCHEDULED (FZ-023), cancel
    // only while SCHEDULED or ACTIVE (FZ-024). Offering an action that can only be
    // refused wastes the user's time.
    const restriction = detail({ status })
    stubApi(restriction)
    render()

    await screen.findByRole('heading', { name: 'Black Friday Freeze' })

    expect(!!screen.queryByRole('link', { name: 'Edit' })).toBe(canEdit)
    expect(!!screen.queryByRole('button', { name: /cancel restriction/i })).toBe(canCancel)
  })

  test('cancels through the API', async () => {
    const user = userEvent.setup()
    const restriction = detail({ status: 'ACTIVE' })
    const spy = stubApi(restriction)
    render()

    await user.click(await screen.findByRole('button', { name: /cancel restriction/i }))

    await waitFor(() => {
      const call = spy.mock.calls.find(([url]) => String(url).includes('/cancel'))
      expect(call).toBeDefined()
      expect(call?.[1]?.method).toBe('POST')
    })
  })

  test('surfaces a conflict when the restriction moved on before cancelling', async () => {
    // The realistic race: it completed while this page was open.
    const user = userEvent.setup()
    const restriction = detail({ status: 'ACTIVE' })
    stubApi(restriction, 409)
    render()

    await user.click(await screen.findByRole('button', { name: /cancel restriction/i }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/can be cancelled/i)
  })

  test('says the restriction does not exist on 404', async () => {
    // 404 also covers another tenant's restriction; the wording must not imply it exists.
    stubApi({ status: 404 })
    renderRoute(<RestrictionDetailPage />, { path: '/restrictions/7', route: '/restrictions/:restrictionId' })

    expect(await screen.findByRole('alert')).toHaveTextContent(/does not exist/i)
  })
})
