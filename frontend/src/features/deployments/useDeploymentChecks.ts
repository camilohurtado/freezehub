import { useInfiniteQuery, useMutation } from '@tanstack/react-query'
import {
  listDeploymentChecks,
  previewDeploymentCheck,
  type DeploymentCheckFilters,
} from '../../api/deploymentChecks'
import { useAuth } from '../auth/authContext'
import type { DeploymentCheck, DeploymentCheckPreview } from '../../types/api'

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

/**
 * "Can I deploy?" (`FZ-120`).
 *
 * A mutation although it is a GET, deliberately: `useQuery` would cache the answer and
 * serve it again, and a stale ALLOW shown while a freeze is in force is the same failure
 * the machine endpoint is a POST to prevent. It is also asked on purpose, by clicking —
 * not fetched because a screen opened.
 *
 * Note what it does *not* do: invalidate `['deployment-checks']`. A preview writes no row,
 * so there is nothing new for the list on this page to show (`D-29`).
 */
export function useDeploymentPreview() {
  const { token } = useAuth()

  return useMutation<DeploymentCheckPreview, Error, { application: string; environment: string }>({
    mutationFn: ({ application, environment }) =>
      previewDeploymentCheck(token, application, environment),
  })
}
