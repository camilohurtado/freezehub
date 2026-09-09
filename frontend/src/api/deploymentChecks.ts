import { apiRequest } from './client'
import type {
  DeploymentCheck,
  DeploymentCheckPreview,
  DeploymentCheckSummary,
} from '../types/api'

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

/**
 * The figures the dashboard and the checks console draw (`FZ-105`).
 *
 * One request rather than four because they are read together — four round trips to paint
 * one row is four chances for it to paint inconsistently.
 */
export function getDeploymentCheckSummary(
  token: string | null,
  signal?: AbortSignal,
): Promise<DeploymentCheckSummary> {
  return apiRequest<DeploymentCheckSummary>('/api/deployment-checks/summary', { token, signal })
}

/**
 * What a deployment would be told right now (`FZ-120`).
 *
 * A GET, unlike the pipeline's POST: nothing is enforced on this answer and nothing is
 * recorded by it.
 */
export function previewDeploymentCheck(
  token: string | null,
  application: string,
  environment: string,
  signal?: AbortSignal,
): Promise<DeploymentCheckPreview> {
  const params = new URLSearchParams({ application, environment })
  return apiRequest<DeploymentCheckPreview>(
    `/api/deployment-checks/preview?${params.toString()}`,
    { token, signal },
  )
}
