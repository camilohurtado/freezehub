import { useQuery } from '@tanstack/react-query'
import { listRestrictions } from '../../api/restrictions'
import { useAuth } from '../auth/authContext'
import type { RestrictionSummary } from '../../types/api'

/** How many finished restrictions the dashboard shows. See RECENTLY_COMPLETED_LIMIT. */
export const RECENTLY_COMPLETED_LIMIT = 5

export interface DashboardData {
  active: RestrictionSummary[]
  upcoming: RestrictionSummary[]
  recentlyCompleted: RestrictionSummary[]
}

/**
 * Two requests, not three: `status` is repeatable, so active and upcoming arrive
 * together and are split client-side (FZ-021).
 *
 * "Recently" is purely presentational — the backend applies no recency window, so the
 * cap is applied here over its already-sorted result.
 */
export function useDashboardRestrictions() {
  const { token } = useAuth()

  return useQuery<DashboardData>({
    queryKey: ['restrictions', 'dashboard'],
    queryFn: async ({ signal }) => {
      const [live, completed] = await Promise.all([
        listRestrictions(token, ['ACTIVE', 'SCHEDULED'], signal),
        listRestrictions(token, ['COMPLETED'], signal),
      ])

      return {
        active: live.filter((restriction) => restriction.status === 'ACTIVE'),
        upcoming: live.filter((restriction) => restriction.status === 'SCHEDULED'),
        // Ordered soonest-first by the backend, so the most recent finishers are last.
        recentlyCompleted: completed.slice(-RECENTLY_COMPLETED_LIMIT).reverse(),
      }
    },
  })
}
