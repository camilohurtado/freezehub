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

export function getRestriction(
  token: string | null,
  id: number,
  signal?: AbortSignal,
): Promise<RestrictionDetail> {
  return apiRequest<RestrictionDetail>(`/api/restrictions/${id}`, { token, signal })
}

/** Full replacement, and only while SCHEDULED — the backend returns 409 otherwise (FZ-023). */
export function updateRestriction(
  token: string | null,
  id: number,
  body: CreateRestrictionBody,
): Promise<RestrictionDetail> {
  return apiRequest<RestrictionDetail>(`/api/restrictions/${id}`, { method: 'PUT', body, token })
}

/** An action, not a delete: the restriction stays as a record with status CANCELLED (FZ-024). */
export function cancelRestriction(token: string | null, id: number): Promise<RestrictionDetail> {
  return apiRequest<RestrictionDetail>(`/api/restrictions/${id}/cancel`, {
    method: 'POST',
    token,
  })
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
