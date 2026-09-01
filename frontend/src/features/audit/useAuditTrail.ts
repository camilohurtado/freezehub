import { useInfiniteQuery } from '@tanstack/react-query'
import { listAuditEvents } from '../../api/audit'
import { useAuth } from '../auth/authContext'
import type { AuditEvent, AuditResourceType } from '../../types/api'

/** Matches the backend's default page, so "load older" appears exactly when there is more. */
const PAGE_SIZE = 50

export function useAuditTrail(resourceType?: AuditResourceType) {
  const { token } = useAuth()

  return useInfiniteQuery({
    queryKey: ['audit', resourceType ?? 'all'],
    queryFn: ({ pageParam, signal }) =>
      listAuditEvents(token, resourceType, pageParam as number | undefined, signal),
    initialPageParam: undefined as number | undefined,
    getNextPageParam: (lastPage: AuditEvent[]) =>
      lastPage.length < PAGE_SIZE ? undefined : lastPage[lastPage.length - 1]?.id,
  })
}
