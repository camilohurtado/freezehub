import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { OrganizationSection } from './OrganizationSection'
import { renderRoute } from '../../test/renderRoute'

function stubApi(leadTimeMinutes: number, writeResponse?: () => Response) {
  const spy = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    void input
    const organization = {
      id: 1,
      name: 'Acme',
      startingSoonLeadTimeMinutes: leadTimeMinutes,
    }
    const json = (body: unknown, status = 200) =>
      new Response(JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/json' },
      })

    if (init?.method && init.method !== 'GET') {
      return Promise.resolve(writeResponse?.() ?? json(organization))
    }
    return Promise.resolve(json(organization))
  })
  vi.stubGlobal('fetch', spy)
  return spy
}

describe('OrganizationSection', () => {
  beforeEach(() => sessionStorage.clear())
  afterEach(() => vi.unstubAllGlobals())

  test('shows the lead time the organization already has', async () => {
    stubApi(1440)
    renderRoute(<OrganizationSection />, { path: '/settings' })

    const select = (await screen.findByLabelText(/this far ahead/i)) as HTMLSelectElement
    expect(select.value).toBe('1440')
  })

  test('keeps a value that is not one of the offered choices', async () => {
    // The API accepts anything from 1 minute to 30 days. A value set outside this screen
    // must not be silently replaced by the nearest option on the next save.
    stubApi(999)
    renderRoute(<OrganizationSection />, { path: '/settings' })

    const select = (await screen.findByLabelText(/this far ahead/i)) as HTMLSelectElement
    expect(select.value).toBe('999')
    expect(screen.getByRole('option', { name: '999 minutes' })).toBeInTheDocument()
  })

  test('sends the chosen lead time in minutes', async () => {
    const user = userEvent.setup()
    const spy = stubApi(1440)
    renderRoute(<OrganizationSection />, { path: '/settings' })

    await user.selectOptions(await screen.findByLabelText(/this far ahead/i), '240')
    await user.click(screen.getByRole('button', { name: /save/i }))

    await waitFor(() => {
      const write = spy.mock.calls.find(([, init]) => init?.method === 'PATCH')
      expect(write).toBeDefined()
      expect(JSON.parse(String(write?.[1]?.body))).toEqual({ startingSoonLeadTimeMinutes: 240 })
    })
  })

  test('cannot save what is already saved', async () => {
    stubApi(1440)
    renderRoute(<OrganizationSection />, { path: '/settings' })

    expect(await screen.findByRole('button', { name: /save/i })).toBeDisabled()
  })

  test('confirms what will now happen rather than just saying saved', async () => {
    const user = userEvent.setup()
    stubApi(1440)
    renderRoute(<OrganizationSection />, { path: '/settings' })

    await user.selectOptions(await screen.findByLabelText(/this far ahead/i), '240')
    await user.click(screen.getByRole('button', { name: /save/i }))

    expect(await screen.findByText(/announced 4 hours before they start/i)).toBeInTheDocument()
  })

  test('explains a 403 rather than appearing to have saved', async () => {
    const user = userEvent.setup()
    stubApi(1440, () =>
      new Response(JSON.stringify({ status: 403, detail: 'This action requires an administrator.' }), {
        status: 403,
        headers: { 'Content-Type': 'application/problem+json' },
      }),
    )
    renderRoute(<OrganizationSection />, { path: '/settings' })

    await user.selectOptions(await screen.findByLabelText(/this far ahead/i), '240')
    await user.click(screen.getByRole('button', { name: /save/i }))

    expect(
      await screen.findByText(/only an administrator can change how much warning/i),
    ).toBeInTheDocument()
    expect(screen.queryByText(/^Saved\./)).not.toBeInTheDocument()
  })
})
