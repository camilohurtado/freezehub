import { apiRequest } from './client'
import type { AuditEvent, AuditResourceType } from '../types/api'

/**
 * One page of the audit trail, newest first. Administrator only.
 *
 * `beforeId` is a cursor rather than an offset: the trail is append-only, so an offset
 * window would shift under a reader as new entries arrive.
 */
export function listAuditEvents(
  token: string | null,
  resourceType?: AuditResourceType,
  beforeId?: number,
  signal?: AbortSignal,
): Promise<AuditEvent[]> {
  const params = new URLSearchParams()
  if (resourceType) params.set('resourceType', resourceType)
  if (beforeId !== undefined) params.set('beforeId', String(beforeId))

  const query = params.toString()
  return apiRequest<AuditEvent[]>(`/api/audit${query ? `?${query}` : ''}`, { token, signal })
}
