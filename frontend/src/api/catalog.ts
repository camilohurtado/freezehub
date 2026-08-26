import { apiRequest } from './client'
import type { ApplicationSummary, CatalogEntry } from '../types/api'

/**
 * Thin wrappers over the catalog API (FZ-013 – FZ-015).
 *
 * Deliberately thin: catalog data is expected to arrive from more than one place over
 * time — typed here today, plausibly ingested from GitLab/GitHub later — so nothing about
 * it is decided in the client. A future sync writing to these same endpoints must produce
 * the same result as a person typing.
 */

export function listTeams(token: string | null, signal?: AbortSignal): Promise<CatalogEntry[]> {
  return apiRequest<CatalogEntry[]>('/api/teams', { token, signal })
}

export function createTeam(token: string | null, name: string): Promise<CatalogEntry> {
  return apiRequest<CatalogEntry>('/api/teams', { method: 'POST', body: { name }, token })
}

export function renameTeam(token: string | null, id: number, name: string): Promise<CatalogEntry> {
  return apiRequest<CatalogEntry>(`/api/teams/${id}`, { method: 'PATCH', body: { name }, token })
}

export function deleteTeam(token: string | null, id: number): Promise<void> {
  return apiRequest<void>(`/api/teams/${id}`, { method: 'DELETE', token })
}

export function listApplications(
  token: string | null,
  signal?: AbortSignal,
): Promise<ApplicationSummary[]> {
  return apiRequest<ApplicationSummary[]>('/api/applications', { token, signal })
}

export function createApplication(token: string | null, name: string): Promise<ApplicationSummary> {
  return apiRequest<ApplicationSummary>('/api/applications', {
    method: 'POST',
    body: { name },
    token,
  })
}

export function renameApplication(
  token: string | null,
  id: number,
  name: string,
): Promise<ApplicationSummary> {
  return apiRequest<ApplicationSummary>(`/api/applications/${id}`, {
    method: 'PATCH',
    body: { name },
    token,
  })
}

export function deleteApplication(token: string | null, id: number): Promise<void> {
  return apiRequest<void>(`/api/applications/${id}`, { method: 'DELETE', token })
}

/** Both association calls are idempotent server-side (FZ-014). */
export function attachTeam(token: string | null, applicationId: number, teamId: number): Promise<void> {
  return apiRequest<void>(`/api/applications/${applicationId}/teams/${teamId}`, {
    method: 'PUT',
    token,
  })
}

export function detachTeam(token: string | null, applicationId: number, teamId: number): Promise<void> {
  return apiRequest<void>(`/api/applications/${applicationId}/teams/${teamId}`, {
    method: 'DELETE',
    token,
  })
}

export function listEnvironments(
  token: string | null,
  signal?: AbortSignal,
): Promise<CatalogEntry[]> {
  return apiRequest<CatalogEntry[]>('/api/environments', { token, signal })
}

export function createEnvironment(token: string | null, name: string): Promise<CatalogEntry> {
  return apiRequest<CatalogEntry>('/api/environments', { method: 'POST', body: { name }, token })
}

export function renameEnvironment(
  token: string | null,
  id: number,
  name: string,
): Promise<CatalogEntry> {
  return apiRequest<CatalogEntry>(`/api/environments/${id}`, {
    method: 'PATCH',
    body: { name },
    token,
  })
}

export function deleteEnvironment(token: string | null, id: number): Promise<void> {
  return apiRequest<void>(`/api/environments/${id}`, { method: 'DELETE', token })
}
