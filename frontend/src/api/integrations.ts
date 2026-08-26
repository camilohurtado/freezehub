import { apiRequest } from './client'
import type { Integration, IntegrationType } from '../types/api'

/**
 * The stored `config` is never returned by the API — a Slack webhook URL is a bearer
 * credential — so there is no "read config" call here by design. Changing one means
 * replacing it.
 */
export function listIntegrations(
  token: string | null,
  signal?: AbortSignal,
): Promise<Integration[]> {
  return apiRequest<Integration[]>('/api/integrations', { token, signal })
}

export function createIntegration(
  token: string | null,
  type: IntegrationType,
  config: string,
): Promise<Integration> {
  return apiRequest<Integration>('/api/integrations', {
    method: 'POST',
    body: { type, config },
    token,
  })
}

export function setIntegrationEnabled(
  token: string | null,
  id: number,
  enabled: boolean,
): Promise<Integration> {
  return apiRequest<Integration>(`/api/integrations/${id}`, {
    method: 'PATCH',
    body: { enabled },
    token,
  })
}

export function deleteIntegration(token: string | null, id: number): Promise<void> {
  return apiRequest<void>(`/api/integrations/${id}`, { method: 'DELETE', token })
}
