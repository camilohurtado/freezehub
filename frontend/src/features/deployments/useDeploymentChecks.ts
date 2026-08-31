import { useInfiniteQuery } from '@tanstack/react-query'
import { listDeploymentChecks, type DeploymentCheckFilters } from '../../api/deploymentChecks'
import { useAuth } from '../auth/authContext'
import type { DeploymentCheck } from '../../types/api'

/** Matches the backend's default page size, so "load more" appears exactly when there is more. */
const PAGE_SIZE = 50

/**
 * Pages of checks, oldest-ward, by cursor.
 *
 * The next page starts before the last id seen rather than at an offset: this table only
 * grows, and a deployment happening mid-read would shift an offset window and silently
 * hide a row.
 */
export function useDeploymentChecks(filters: DeploymentCheckFilters) {
  const { token } = useAuth()

  return useInfiniteQuery({
    queryKey: ['deployment-checks', filters],
    queryFn: ({ pageParam, signal }) =>
      listDeploymentChecks(token, filters, pageParam as number | undefined, signal),
    initialPageParam: undefined as number | undefined,
    getNextPageParam: (lastPage: DeploymentCheck[]) =>
      // A short page means the end. Asking again would return nothing and offer a button
      // that does nothing.
      lastPage.length < PAGE_SIZE ? undefined : lastPage[lastPage.length - 1]?.id,
  })
}
