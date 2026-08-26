import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import * as catalog from '../../api/catalog'
import { useAuth } from '../auth/authContext'
import type { ApplicationSummary, CatalogEntry } from '../../types/api'

/**
 * Catalog reads and writes.
 *
 * Every mutation invalidates its list rather than patching the cache by hand: the server
 * is the source of truth, and a rejected write must not leave the UI showing something
 * that never happened.
 */

export function useTeams() {
  const { token } = useAuth()
  return useQuery<CatalogEntry[]>({
    queryKey: ['catalog', 'teams'],
    queryFn: ({ signal }) => catalog.listTeams(token, signal),
  })
}

export function useApplications() {
  const { token } = useAuth()
  return useQuery<ApplicationSummary[]>({
    queryKey: ['catalog', 'applications'],
    queryFn: ({ signal }) => catalog.listApplications(token, signal),
  })
}

export function useEnvironments() {
  const { token } = useAuth()
  return useQuery<CatalogEntry[]>({
    queryKey: ['catalog', 'environments'],
    queryFn: ({ signal }) => catalog.listEnvironments(token, signal),
  })
}

type CatalogKind = 'teams' | 'applications' | 'environments'

export function useCatalogMutations(kind: CatalogKind) {
  const { token } = useAuth()
  const queryClient = useQueryClient()

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['catalog', kind] })
    // A team change alters the teams an application can be assigned, and vice versa.
    if (kind === 'teams') void queryClient.invalidateQueries({ queryKey: ['catalog', 'applications'] })
  }

  const create = useMutation({
    mutationFn: (name: string) => {
      if (kind === 'teams') return catalog.createTeam(token, name)
      if (kind === 'applications') return catalog.createApplication(token, name)
      return catalog.createEnvironment(token, name)
    },
    onSuccess: invalidate,
  })

  const rename = useMutation({
    mutationFn: ({ id, name }: { id: number; name: string }) => {
      if (kind === 'teams') return catalog.renameTeam(token, id, name)
      if (kind === 'applications') return catalog.renameApplication(token, id, name)
      return catalog.renameEnvironment(token, id, name)
    },
    onSuccess: invalidate,
  })

  const remove = useMutation({
    mutationFn: (id: number) => {
      if (kind === 'teams') return catalog.deleteTeam(token, id)
      if (kind === 'applications') return catalog.deleteApplication(token, id)
      return catalog.deleteEnvironment(token, id)
    },
    onSuccess: invalidate,
  })

  return { create, rename, remove }
}

export function useTeamAssignment() {
  const { token } = useAuth()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({
      applicationId,
      teamId,
      attach,
    }: {
      applicationId: number
      teamId: number
      attach: boolean
    }) =>
      attach
        ? catalog.attachTeam(token, applicationId, teamId)
        : catalog.detachTeam(token, applicationId, teamId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['catalog', 'applications'] })
    },
  })
}
