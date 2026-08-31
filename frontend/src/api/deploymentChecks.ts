import { apiRequest } from './client'
import type { DeploymentCheck } from '../types/api'

export interface DeploymentCheckFilters {
  decision?: 'ALLOW' | 'BLOCK'
  application?: string
  environment?: string
}

/**
 * One page of checks, newest first.
 *
 * `beforeId` is a cursor, not an offset. The table is written once per deployment, so
 * with an offset the rows arriving between two page requests would shift the window and
 * the reader would silently skip some.
 */
export function listDeploymentChecks(
  token: string | null,
  filters: DeploymentCheckFilters = {},
  beforeId?: number,
  signal?: AbortSignal,
): Promise<DeploymentCheck[]> {
  const params = new URLSearchParams()
  if (filters.decision) params.set('decision', filters.decision)
  if (filters.application) params.set('application', filters.application)
  if (filters.environment) params.set('environment', filters.environment)
  if (beforeId !== undefined) params.set('beforeId', String(beforeId))

  const query = params.toString()
  return apiRequest<DeploymentCheck[]>(
    `/api/deployment-checks${query ? `?${query}` : ''}`,
    { token, signal },
  )
}
