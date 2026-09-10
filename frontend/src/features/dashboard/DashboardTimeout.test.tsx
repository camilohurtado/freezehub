import { act, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { DashboardPage } from './DashboardPage'
import { renderRoute } from '../../test/renderRoute'

/**
 * What a person sees when the API accepts the request and never answers (`FZ-124`).
 *
 * Fake timers, and no `findBy*`: Testing Library's async helpers poll on real time, which
 * never advances here. Everything after the first render is driven by moving the clock on
 * purpose and then reading the DOM.
 */
describe('DashboardPage when the server does not answer', () => {
  beforeEach(() => sessionStorage.clear())
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  test('ends in an error somebody can act on, not a permanent spinner', async () => {
    vi.useFakeTimers()
    vi.stubGlobal(
      'fetch',
      vi.fn(
        (_input: RequestInfo | URL, init?: RequestInit) =>
          new Promise<Response>((_resolve, reject) => {
            init?.signal?.addEventListener('abort', () =>
              reject(new DOMException('The operation was aborted.', 'AbortError')),
            )
          }),
      ),
    )

    renderRoute(<DashboardPage />, { path: '/dashboard' })

    expect(screen.getByText(/loading restrictions/i)).toBeInTheDocument()

    // The deadline, plus room for whatever retrying the app does on top of it.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(120_000)
    })

    expect(screen.queryByText(/loading restrictions/i)).not.toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent(/did not answer within/i)
  })
})
