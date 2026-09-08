import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { NotificationsPage } from './NotificationsPage'
import { renderRoute } from '../../test/renderRoute'
import type { NotificationEventRecord } from '../../types/api'

const activated = (over: Partial<NotificationEventRecord> = {}): NotificationEventRecord => ({
  restrictionId: 7,
  restrictionName: 'Black Friday Freeze',
  event: 'ACTIVATED',
  occurredAt: '2026-11-24T18:00:00Z',
  deliveries: [
    { integrationId: 1, channel: 'SLACK', status: 'SENT', attempts: 1, lastError: null, sentAt: '2026-11-24T18:00:01Z' },
    { integrationId: 2, channel: 'EMAIL', status: 'SENT', attempts: 1, lastError: null, sentAt: '2026-11-24T18:00:02Z' },
  ],
  ...over,
})

function stubApi(events: NotificationEventRecord[] | { status: number }) {
  const spy = vi.fn(() =>
    Promise.resolve(
      'status' in events
        ? new Response(JSON.stringify({ message: 'Forbidden' }), {
            status: events.status,
            headers: { 'Content-Type': 'application/json' },
          })
        : new Response(JSON.stringify(events), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }),
    ),
  )
  vi.stubGlobal('fetch', spy)
  return spy
}

describe('NotificationsPage', () => {
  beforeEach(() => sessionStorage.clear())
  afterEach(() => vi.unstubAllGlobals())

  test('shows an event once, with every channel beside it', async () => {
    // The outbox stores a row per channel; three rows saying the same thing happened is
    // what this screen exists not to show (FZ-115).
    stubApi([activated()])
    renderRoute(<NotificationsPage />, { path: '/notifications' })

    const list = await screen.findByRole('list', { name: 'Notifications' })
    expect(within(list).getAllByRole('listitem')).toHaveLength(1)
    expect(within(list).getByText('Restriction activated')).toBeInTheDocument()
    expect(within(list).getByText('Slack')).toBeInTheDocument()
    expect(within(list).getByText('Email')).toBeInTheDocument()
  })

  test('names the failure and why, above the list', async () => {
    // "Was Slack told?" is answered by delivered or failed. "Why not?" is the question
    // immediately after it.
    stubApi([
      activated({
        deliveries: [
          { integrationId: 1, channel: 'SLACK', status: 'SENT', attempts: 1, lastError: null, sentAt: null },
          { integrationId: 3, channel: 'WEBHOOK', status: 'FAILED', attempts: 5, lastError: '502 Bad Gateway', sentAt: null },
        ],
      }),
    ])
    renderRoute(<NotificationsPage />, { path: '/notifications' })

    expect(await screen.findByText('1 delivery failed')).toBeInTheDocument()
    // Twice on purpose: the banner names the failure, and the row it came from shows it
    // in place — so a reader who scrolls past the banner still sees which channel.
    expect(screen.getAllByText(/502 Bad Gateway/)).toHaveLength(2)
    expect(screen.getByText(/failed · 502 Bad Gateway/)).toBeInTheDocument()
  })

  test('says nothing when nothing failed', async () => {
    // A pending delivery is the outbox working. Announcing it would cry wolf every time a
    // restriction changes.
    stubApi([
      activated({
        deliveries: [
          { integrationId: 1, channel: 'SLACK', status: 'PENDING', attempts: 0, lastError: null, sentAt: null },
        ],
      }),
    ])
    renderRoute(<NotificationsPage />, { path: '/notifications' })

    expect(await screen.findByText('queued')).toBeInTheDocument()
    expect(screen.queryByText(/delivery failed/)).not.toBeInTheDocument()
  })

  test('the banner counts every failure, not the filtered ones', async () => {
    // Hiding the problem behind the filter that hides it is the one thing this screen
    // must not do.
    const user = userEvent.setup()
    stubApi([
      activated(),
      activated({
        restrictionId: 8,
        event: 'CANCELLED',
        deliveries: [
          { integrationId: 3, channel: 'WEBHOOK', status: 'FAILED', attempts: 5, lastError: '502', sentAt: null },
        ],
      }),
    ])
    renderRoute(<NotificationsPage />, { path: '/notifications' })

    await user.click(await screen.findByRole('radio', { name: 'Starting soon' }))

    // No event matches, but the failure is still announced.
    expect(screen.getByText('1 delivery failed')).toBeInTheDocument()
    expect(screen.getByText(/no events match/i)).toBeInTheDocument()
  })

  test('filters to what failed, and keeps it in the URL', async () => {
    const user = userEvent.setup()
    stubApi([
      activated(),
      activated({
        restrictionId: 8,
        event: 'CANCELLED',
        restrictionName: 'Payments incident',
        deliveries: [
          { integrationId: 3, channel: 'WEBHOOK', status: 'FAILED', attempts: 5, lastError: '502', sentAt: null },
        ],
      }),
    ])
    const { router } = renderRoute(<NotificationsPage />, { path: '/notifications' })

    await user.click(await screen.findByRole('radio', { name: 'Failed' }))

    await waitFor(() => expect(router.state.location.search).toContain('show=failed'))
    const list = screen.getByRole('list', { name: 'Notifications' })
    expect(within(list).getAllByRole('listitem')).toHaveLength(1)
    expect(within(list).getByText('Payments incident')).toBeInTheDocument()
  })

  test('explains a 403 rather than showing an empty page', async () => {
    // Delivery history names the destinations announcements were sent to, which is
    // configuration a member cannot see in the first place.
    stubApi({ status: 403 })
    renderRoute(<NotificationsPage />, { path: '/notifications' })

    expect(await screen.findByRole('alert')).toHaveTextContent(/only an administrator/i)
  })

  test('links each event to the restriction it is about', async () => {
    stubApi([activated()])
    renderRoute(<NotificationsPage />, { path: '/notifications' })

    expect(await screen.findByRole('link', { name: 'Black Friday Freeze' })).toHaveAttribute(
      'href',
      '/restrictions/7',
    )
  })

  test('says so plainly when nothing has been announced', async () => {
    stubApi([])
    renderRoute(<NotificationsPage />, { path: '/notifications' })

    expect(await screen.findByText(/nothing has been announced yet/i)).toBeInTheDocument()
  })
})
