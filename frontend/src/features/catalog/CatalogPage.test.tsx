import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { CatalogPage } from './CatalogPage'
import { renderRoute } from '../../test/renderRoute'

interface Routes {
  teams?: unknown
  applications?: unknown
  environments?: unknown
}

/**
 * Routes each catalog endpoint independently, and records writes so tests can assert on
 * the request that was actually sent rather than only on what was rendered.
 */
function stubApi(routes: Routes, override?: (url: string, init?: RequestInit) => Response | null) {
  const spy = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const custom = override?.(url, init)
    if (custom) return Promise.resolve(custom)

    const json = (body: unknown) =>
      new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })

    if (init?.method && init.method !== 'GET') return Promise.resolve(new Response(null, { status: 204 }))
    if (url.includes('/api/teams')) return Promise.resolve(json(routes.teams ?? []))
    if (url.includes('/api/applications')) return Promise.resolve(json(routes.applications ?? []))
    if (url.includes('/api/environments')) return Promise.resolve(json(routes.environments ?? []))
    return Promise.resolve(json([]))
  })
  vi.stubGlobal('fetch', spy)
  return spy
}

const entry = (id: number, name: string) => ({
  id,
  name,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
})

const application = (id: number, name: string, teamIds: number[] = []) => ({
  ...entry(id, name),
  teamIds,
})

describe('CatalogPage', () => {
  beforeEach(() => sessionStorage.clear())
  afterEach(() => vi.unstubAllGlobals())

  test('lists the three catalog types', async () => {
    stubApi({
      teams: [entry(1, 'Payments')],
      applications: [application(2, 'payments-api')],
      environments: [entry(3, 'production')],
    })
    renderRoute(<CatalogPage />, { path: '/catalog' })

    // Scoped per section: "Payments" legitimately appears twice — as the team, and as
    // the assignment checkbox under the application.
    const teams = await screen.findByRole('region', { name: /teams/i })
    expect(await within(teams).findByText('Payments')).toBeInTheDocument()

    const applications = screen.getByRole('region', { name: /applications/i })
    expect(await within(applications).findByText('payments-api')).toBeInTheDocument()

    const environments = screen.getByRole('region', { name: /environments/i })
    expect(await within(environments).findByText('production')).toBeInTheDocument()
  })

  test('says so when a catalog type is empty', async () => {
    stubApi({})
    renderRoute(<CatalogPage />, { path: '/catalog' })

    expect(await screen.findByText('No teams yet.')).toBeInTheDocument()
    expect(screen.getByText('No applications yet.')).toBeInTheDocument()
    expect(screen.getByText('No environments yet.')).toBeInTheDocument()
  })

  test('creates a team through the API', async () => {
    const user = userEvent.setup()
    const spy = stubApi({})
    renderRoute(<CatalogPage />, { path: '/catalog' })

    await user.type(await screen.findByLabelText('New team name'), 'Checkout')
    const teams = screen.getByRole('region', { name: /teams/i })
    await user.click(within(teams).getByRole('button', { name: 'Add' }))

    await waitFor(() => {
      const posted = spy.mock.calls.find(
        ([url, init]) => String(url).includes('/api/teams') && init?.method === 'POST',
      )
      expect(posted).toBeDefined()
      expect(JSON.parse(String(posted?.[1]?.body))).toEqual({ name: 'Checkout' })
    })
  })

  test('explains a duplicate name instead of failing silently', async () => {
    // 409 is the conflict users actually hit; the backend's message is the useful one.
    const user = userEvent.setup()
    stubApi({}, (url, init) =>
      String(url).includes('/api/teams') && init?.method === 'POST'
        ? new Response(JSON.stringify({ message: 'A team with this name already exists' }), {
            status: 409,
            headers: { 'Content-Type': 'application/json' },
          })
        : null,
    )
    renderRoute(<CatalogPage />, { path: '/catalog' })

    await user.type(await screen.findByLabelText('New team name'), 'Payments')
    const teams = screen.getByRole('region', { name: /teams/i })
    await user.click(within(teams).getByRole('button', { name: 'Add' }))

    expect(await within(teams).findByRole('alert')).toHaveTextContent(/already exists/i)
  })

  test('explains why a catalog entry in use cannot be deleted', async () => {
    // The gap FZ-020 left open, now a 409 the UI can actually explain.
    const user = userEvent.setup()
    stubApi({ teams: [entry(1, 'Payments')] }, (_url, init) =>
      init?.method === 'DELETE'
        ? new Response(
            JSON.stringify({
              message: 'This team is referenced by one or more change restrictions',
            }),
            { status: 409, headers: { 'Content-Type': 'application/json' } },
          )
        : null,
    )
    renderRoute(<CatalogPage />, { path: '/catalog' })

    await user.click(await screen.findByRole('button', { name: 'Delete Payments' }))

    const teams = screen.getByRole('region', { name: /teams/i })
    expect(await within(teams).findByRole('alert')).toHaveTextContent(/referenced by/i)
  })

  test('renames an entry', async () => {
    const user = userEvent.setup()
    const spy = stubApi({ environments: [entry(3, 'prod')] })
    renderRoute(<CatalogPage />, { path: '/catalog' })

    const environments = await screen.findByRole('region', { name: /environments/i })
    // Awaited: the section renders immediately in a loading state, so the row's buttons
    // do not exist until the request resolves.
    await user.click(await within(environments).findByRole('button', { name: 'Rename' }))

    const field = within(environments).getByLabelText('New name for prod')
    await user.clear(field)
    await user.type(field, 'production')
    await user.click(within(environments).getByRole('button', { name: 'Save' }))

    await waitFor(() => {
      const patched = spy.mock.calls.find(([, init]) => init?.method === 'PATCH')
      expect(JSON.parse(String(patched?.[1]?.body))).toEqual({ name: 'production' })
    })
  })

  test('assigns a team to an application', async () => {
    const user = userEvent.setup()
    const spy = stubApi({
      teams: [entry(1, 'Payments')],
      applications: [application(2, 'payments-api')],
    })
    renderRoute(<CatalogPage />, { path: '/catalog' })

    await user.click(await screen.findByLabelText('Payments owns payments-api'))

    await waitFor(() => {
      const put = spy.mock.calls.find(([, init]) => init?.method === 'PUT')
      expect(String(put?.[0])).toContain('/api/applications/2/teams/1')
    })
  })

  test('unassigns a team that is currently assigned', async () => {
    const user = userEvent.setup()
    const spy = stubApi({
      teams: [entry(1, 'Payments')],
      applications: [application(2, 'payments-api', [1])],
    })
    renderRoute(<CatalogPage />, { path: '/catalog' })

    const checkbox = await screen.findByLabelText('Payments owns payments-api')
    expect(checkbox).toBeChecked()
    await user.click(checkbox)

    await waitFor(() => {
      const removed = spy.mock.calls.find(([, init]) => init?.method === 'DELETE')
      expect(String(removed?.[0])).toContain('/api/applications/2/teams/1')
    })
  })

  test('tells the user to add a team before assigning one', async () => {
    stubApi({ applications: [application(2, 'payments-api')] })
    renderRoute(<CatalogPage />, { path: '/catalog' })

    expect(await screen.findByText('Add a team to assign one')).toBeInTheDocument()
  })
})
