import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { AuditPage } from './AuditPage'
import { renderRoute } from '../../test/renderRoute'
import type { AuditEvent } from '../../types/api'

const event = (overrides: Partial<AuditEvent> = {}): AuditEvent => ({
  id: 1,
  actorType: 'USER',
  actorId: 1,
  actorLabel: 'dev@acme.test',
  action: 'RESTRICTION_CREATED',
  resourceType: 'RESTRICTION',
  resourceId: 7,
  details: null,
  occurredAt: '2026-09-01T10:00:00Z',
  ...overrides,
})

function stubApi(pages: AuditEvent[][]) {
  let call = 0
  const spy = vi.fn((input: RequestInfo | URL) => {
    void input
    const page = pages[Math.min(call, pages.length - 1)]
    call += 1
    return Promise.resolve(
      new Response(JSON.stringify(page), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
  })
  vi.stubGlobal('fetch', spy)
  return spy
}

describe('AuditPage', () => {
  beforeEach(() => sessionStorage.clear())
  afterEach(() => vi.unstubAllGlobals())

  test('shows what changed, who changed it, and what it was before', async () => {
    // The entry that explains a wall of refusals in the deployment console.
    stubApi([
      [
        event({
          action: 'CATALOG_RENAMED',
          resourceType: 'APPLICATION',
          details: { name: { from: 'payments-api', to: 'payments-service' } },
        }),
      ],
    ])
    renderRoute(<AuditPage />, { path: '/audit' })

    const list = await screen.findByRole('list', { name: 'Audit trail' })
    expect(within(list).getByText('Renamed application')).toBeInTheDocument()
    expect(within(list).getByText('dev@acme.test')).toBeInTheDocument()
    expect(within(list).getByText('name: payments-api → payments-service')).toBeInTheDocument()
  })

  test('does not attribute the system’s own work to a person', async () => {
    // A restriction takes effect because time passed. Naming someone would be a fiction.
    stubApi([[event({ action: 'RESTRICTION_ACTIVATED', actorType: 'SYSTEM', actorId: null, actorLabel: 'system' })]])
    renderRoute(<AuditPage />, { path: '/audit' })

    expect(await screen.findByText('Restriction took effect')).toBeInTheDocument()
    expect(screen.getByText('automatic')).toBeInTheDocument()
    expect(screen.queryByText('system')).not.toBeInTheDocument()
  })

  test('filters by what was changed, and puts it in the URL', async () => {
    const user = userEvent.setup()
    const spy = stubApi([[event()]])
    const { router } = renderRoute(<AuditPage />, { path: '/audit' })

    await user.click(await screen.findByRole('button', { name: 'API keys' }))

    await waitFor(() => expect(router.state.location.search).toContain('resourceType=API_KEY'))
    await waitFor(() =>
      expect(spy.mock.calls.some(([input]) => String(input).includes('resourceType=API_KEY'))).toBe(true),
    )
  })

  test('ignores a resource type the UI does not offer', async () => {
    // A hand-edited URL must not send the backend something it will reject.
    const spy = stubApi([[event()]])
    renderRoute(<AuditPage />, { path: '/audit?resourceType=NONSENSE', route: '/audit' })

    await waitFor(() => expect(spy).toHaveBeenCalled())
    expect(spy.mock.calls.every(([input]) => !String(input).includes('NONSENSE'))).toBe(true)
    expect(screen.getByRole('button', { name: 'Everything' })).toHaveAttribute('aria-pressed', 'true')
  })

  test('explains a 403 rather than looking broken', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(JSON.stringify({ status: 403, detail: 'This action requires an administrator.' }), {
            status: 403,
            headers: { 'Content-Type': 'application/problem+json' },
          }),
        ),
      ),
    )
    renderRoute(<AuditPage />, { path: '/audit' })

    expect(await screen.findByText(/only an administrator can read the audit trail/i)).toBeInTheDocument()
    // Not the generic failure message as well — one explanation, not two.
    expect(screen.queryByRole('button', { name: /try again/i })).not.toBeInTheDocument()
  })

  test('pages by cursor', async () => {
    const user = userEvent.setup()
    const fullPage = Array.from({ length: 50 }, (_, index) => event({ id: 100 - index }))
    const spy = stubApi([fullPage, [event({ id: 40 })]])
    renderRoute(<AuditPage />, { path: '/audit' })

    await user.click(await screen.findByRole('button', { name: /load older/i }))

    await waitFor(() =>
      expect(spy.mock.calls.some(([input]) => String(input).includes('beforeId=51'))).toBe(true),
    )
  })

  test('says the trail is immutable, because that is the point of it', async () => {
    stubApi([[event()]])
    renderRoute(<AuditPage />, { path: '/audit' })

    expect(await screen.findByText(/never edited or removed/i)).toBeInTheDocument()
  })
})
