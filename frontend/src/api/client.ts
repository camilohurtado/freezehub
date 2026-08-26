/**
 * The single place that talks to the API.
 *
 * Everything else goes through here so that the auth header, the base URL and error
 * normalisation exist in exactly one place — no component calls `fetch` directly
 * (05-frontend.md, API interaction conventions).
 */

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

/** Raised for any non-2xx response, carrying the status the UI branches on. */
export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }

  /** Token missing, invalid, or resolving to no user — the caller must sign in again. */
  get isUnauthorized(): boolean {
    return this.status === 401
  }

  /** Unknown *or* another tenant's resource; the backend never distinguishes them. */
  get isNotFound(): boolean {
    return this.status === 404
  }

  /** State conflict — the resource has moved on and should be refetched. */
  get isConflict(): boolean {
    return this.status === 409
  }
}

/**
 * Error bodies are not standardised until FZ-061, so this must survive a body it cannot
 * parse and fall back to something derived from the status.
 */
async function toApiError(response: Response): Promise<ApiError> {
  const fallback = `Request failed (${response.status})`
  try {
    const text = await response.text()
    if (!text) return new ApiError(response.status, fallback)

    try {
      const body = JSON.parse(text) as { message?: string; error?: string; detail?: string }
      const message = body.message ?? body.detail ?? body.error
      return new ApiError(response.status, message && message.trim() ? message : fallback)
    } catch {
      // Not JSON — use the raw text if it is short enough to be a useful message.
      return new ApiError(response.status, text.length <= 200 ? text : fallback)
    }
  } catch {
    return new ApiError(response.status, fallback)
  }
}

export interface RequestOptions {
  method?: string
  body?: unknown
  token?: string | null
  signal?: AbortSignal
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, token, signal } = options

  const headers: Record<string, string> = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (token) headers.Authorization = `Bearer ${token}`

  const response = await fetch(`${BASE_URL}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    signal,
  })

  if (!response.ok) {
    throw await toApiError(response)
  }

  // 204 and other empty responses have nothing to parse.
  if (response.status === 204) {
    return undefined as T
  }
  const text = await response.text()
  return (text ? JSON.parse(text) : undefined) as T
}
