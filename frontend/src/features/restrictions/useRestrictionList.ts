import { useQuery } from '@tanstack/react-query'
import { listRestrictions } from '../../api/restrictions'
import { useAuth } from '../auth/authContext'
import type { RestrictionStatus, RestrictionSummary } from '../../types/api'

export const ALL_STATUSES: RestrictionStatus[] = ['ACTIVE', 'SCHEDULED', 'COMPLETED', 'CANCELLED']

/**
 * Filters arrive from the URL, where anyone can type anything, so unknown values are
 * discarded rather than forwarded to the API.
 */
export function parseStatuses(raw: string[]): RestrictionStatus[] {
  return raw.filter((value): value is RestrictionStatus =>
    (ALL_STATUSES as string[]).includes(value),
  )
}

/**
 * The filter is part of the query key, so each combination is cached separately and
 * switching back to a previous filter is instant.
 *
 * Results are rendered in the order the backend returns them (soonest start first) and
 * are never re-sorted here — the ordering is the API's contract, not this page's.
 */
export function useRestrictionList(statuses: RestrictionStatus[]) {
  const { token } = useAuth()

  return useQuery<RestrictionSummary[]>({
    queryKey: ['restrictions', 'list', [...statuses].sort()],
    queryFn: ({ signal }) => listRestrictions(token, statuses, signal),
  })
}
