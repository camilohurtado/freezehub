import { describe, expect, test, vi } from 'vitest'
import { ApiError, apiRequest } from './client'

/** How a failed response becomes something a person can act on (FZ-061). */
describe('apiRequest error handling', () => {
  function respondWith(status: number, body: unknown, contentType = 'application/problem+json') {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(typeof body === 'string' ? body : JSON.stringify(body), {
            status,
            headers: { 'Content-Type': contentType },
          }),
        ),
      ),
    )
  }

  async function failureFrom(): Promise<ApiError> {
    try {
      await apiRequest('/api/teams')
      throw new Error('expected the request to fail')
    } catch (caught) {
      return caught as ApiError
    }
  }

  test('uses the reason the backend stated', async () => {
    respondWith(409, { status: 409, title: 'Conflict', detail: 'A team with this name already exists' })

    const error = await failureFrom()

    expect(error.status).toBe(409)
    expect(error.isConflict).toBe(true)
    expect(error.message).toBe('A team with this name already exists')
  })

  test('spells out which fields were rejected', async () => {
    // "The request has 2 invalid fields" tells someone staring at a form nothing about
    // which two.
    respondWith(400, {
      status: 400,
      detail: 'The request has 2 invalid fields.',
      errors: [
        { field: 'name', message: 'must not be blank' },
        { field: 'reason', message: 'must not be blank' },
      ],
    })

    const error = await failureFrom()

    expect(error.message).toBe('name must not be blank, reason must not be blank')
    expect(error.fieldErrors).toHaveLength(2)
    expect(error.fieldErrors[0]).toEqual({ field: 'name', message: 'must not be blank' })
  })

  test('falls back to the status when the response has no body', async () => {
    // A 401 from the security chain is exactly this: no body at all.
    respondWith(401, '')

    const error = await failureFrom()

    expect(error.isUnauthorized).toBe(true)
    expect(error.message).toBe('Request failed (401)')
  })

  test('survives a body that is not the shape it should be', async () => {
    // Anything served by a proxy in front of the API is out of the backend's hands.
    respondWith(502, '<html>Bad Gateway</html>', 'text/html')

    const error = await failureFrom()

    expect(error.status).toBe(502)
    expect(error.message).toContain('Bad Gateway')
  })

  test('does not present an empty message when the body carries none', async () => {
    respondWith(500, { status: 500, detail: '   ' })

    const error = await failureFrom()

    expect(error.message).toBe('Request failed (500)')
  })
})
