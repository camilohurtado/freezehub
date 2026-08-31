/**
 * The single place that talks to the API.
 *
 * Everything else goes through here so that the auth header, the base URL and error
 * normalisation exist in exactly one place — no component calls `fetch` directly
 * (05-frontend.md, API interaction conventions).
 */

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

/** One invalid field, as named by the backend (RFC 9457 `errors` extension). */
export interface ApiFieldError {
  field: string
  message: string
}

/** Raised for any non-2xx response, carrying the status the UI branches on. */
export class ApiError extends Error {
  readonly status: number
  /** Empty unless the backend rejected specific fields. */
  readonly fieldErrors: ApiFieldError[]

  constructor(status: number, message: string, fieldErrors: ApiFieldError[] = []) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.fieldErrors = fieldErrors
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

interface ProblemDetail {
  detail?: string
  title?: string
  errors?: ApiFieldError[]
  /** Pre-FZ-061 shape. Kept only so an older backend does not produce a blank message. */
  message?: string
}

/**
 * Turns a failed response into an ApiError.
 *
 * The backend answers with RFC 9457 Problem Details (FZ-061), so `detail` is expected.
 * The defensive parsing is still here on purpose: not every failure reaches a controller.
 * A 401 from the security chain has no body at all, and anything served by a proxy or a
 * load balancer in front of the API is out of the backend's hands entirely — so this must
 * never depend on the body being what it should be.
 */
async function toApiError(response: Response): Promise<ApiError> {
  const fallback = `Request failed (${response.status})`
  try {
    const text = await response.text()
    if (!text) return new ApiError(response.status, fallback)

    try {
      const body = JSON.parse(text) as ProblemDetail
      const fieldErrors = Array.isArray(body.errors) ? body.errors : []
      const stated = body.detail ?? body.message ?? body.title

      // Field errors are spelled out rather than left as "the request has 3 invalid
      // fields", which tells someone staring at a form nothing about which three.
      const message = fieldErrors.length
        ? fieldErrors.map((error) => `${error.field} ${error.message}`).join(', ')
        : stated

      return new ApiError(
        response.status,
        message && message.trim() ? message : fallback,
        fieldErrors,
      )
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
