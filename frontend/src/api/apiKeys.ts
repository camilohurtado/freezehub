import { apiRequest } from './client'
import type { ApiKey, IssuedApiKey } from '../types/api'

/**
 * Machine credentials for CI/CD.
 *
 * The raw key is returned by `createApiKey` and by nothing else, ever — only its hash is
 * stored. There is deliberately no "read key" call, for the same reason there is no "read
 * config" for integrations.
 */
export function listApiKeys(token: string | null, signal?: AbortSignal): Promise<ApiKey[]> {
  return apiRequest<ApiKey[]>('/api/api-keys', { token, signal })
}

/** The response carries `key`. It cannot be recovered afterwards. */
export function createApiKey(token: string | null, name: string): Promise<IssuedApiKey> {
  return apiRequest<IssuedApiKey>('/api/api-keys', { method: 'POST', body: { name }, token })
}

/** Permanent — there is no un-revoke, because a withdrawn key may already be in use. */
export function revokeApiKey(token: string | null, id: number): Promise<ApiKey> {
  return apiRequest<ApiKey>(`/api/api-keys/${id}/revoke`, { method: 'POST', token })
}
