import { apiRequest } from './client'
import type { Organization } from '../types/api'

export function getOrganization(
  token: string | null,
  signal?: AbortSignal,
): Promise<Organization> {
  return apiRequest<Organization>('/api/organization', { token, signal })
}

/** Administrator only. Minutes, between 1 and 43200 — the backend is authoritative. */
export function updateOrganizationSettings(
  token: string | null,
  startingSoonLeadTimeMinutes: number,
): Promise<Organization> {
  return apiRequest<Organization>('/api/organization/settings', {
    method: 'PATCH',
    body: { startingSoonLeadTimeMinutes },
    token,
  })
}
