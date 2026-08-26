import { apiRequest } from './client'
import type {
  CreateRestrictionBody,
  RestrictionDetail,
  RestrictionStatus,
  RestrictionSummary,
} from '../types/api'

export function createRestriction(
  token: string | null,
  body: CreateRestrictionBody,
): Promise<RestrictionDetail> {
  return apiRequest<RestrictionDetail>('/api/restrictions', { method: 'POST', body, token })
}

/**
 * `status` is repeatable server-side (FZ-021), so several states come back in one
 * request rather than one request per state.
 */
export function listRestrictions(
  token: string | null,
  statuses: RestrictionStatus[] = [],
  signal?: AbortSignal,
): Promise<RestrictionSummary[]> {
  const query = statuses.map((status) => `status=${encodeURIComponent(status)}`).join('&')
  return apiRequest<RestrictionSummary[]>(`/api/restrictions${query ? `?${query}` : ''}`, {
    token,
    signal,
  })
}
