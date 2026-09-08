import { useQuery } from '@tanstack/react-query'
import { getRestriction, listRestrictions } from '../../api/restrictions'
import { getDeploymentCheckSummary } from '../../api/deploymentChecks'
import { useAuth } from '../auth/authContext'
import type {
  DeploymentCheckSummary,
  RestrictionDetail,
  RestrictionSummary,
} from '../../types/api'

/** How many finished restrictions the dashboard shows. */
export const RECENTLY_COMPLETED_LIMIT = 5

export interface DashboardData {
  active: RestrictionSummary[]
  upcoming: RestrictionSummary[]
  recentlyCompleted: RestrictionSummary[]
  /**
   * The in-force hard freezes, fetched in full.
   *
   * The list endpoint returns no scope, and the status line has to name the environments
   * it claims are blocked. Fetching the detail of the *blocking* restrictions only keeps
   * this to the one or two requests that are normally in flight — a freeze that is
   * running right now is rare by design — rather than widening the list contract for a
   * sentence one screen needs.
   */
  blocking: RestrictionDetail[]
}

export function useDashboardRestrictions() {
  const { token } = useAuth()

  return useQuery<DashboardData>({
    queryKey: ['restrictions', 'dashboard'],
    queryFn: async ({ signal }) => {
      // `status` is repeatable, so active and upcoming arrive together (FZ-021).
      const [live, completed] = await Promise.all([
        listRestrictions(token, ['ACTIVE', 'SCHEDULED'], signal),
        listRestrictions(token, ['COMPLETED'], signal),
      ])

      const active = live.filter((restriction) => restriction.status === 'ACTIVE')
      const blocking = await Promise.all(
        active
          .filter((restriction) => restriction.level === 'HARD_FREEZE')
          .map((restriction) => getRestriction(token, restriction.id, signal)),
      )

      return {
        active,
        upcoming: live.filter((restriction) => restriction.status === 'SCHEDULED'),
        // Ordered soonest-first by the backend, so the most recent finishers are last.
        recentlyCompleted: completed.slice(-RECENTLY_COMPLETED_LIMIT).reverse(),
        blocking,
      }
    },
  })
}

/**
 * The check aggregates behind the metrics row (`FZ-105`).
 *
 * A separate query from the restrictions so the cards render as soon as they can: the
 * counts are worth waiting for, but not worth making the freeze in force wait for.
 */
export function useDeploymentCheckSummary() {
  const { token } = useAuth()

  return useQuery<DeploymentCheckSummary>({
    queryKey: ['deployment-checks', 'summary'],
    queryFn: ({ signal }) => getDeploymentCheckSummary(token, signal),
  })
}
