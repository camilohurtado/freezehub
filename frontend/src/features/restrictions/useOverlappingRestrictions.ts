import { useQuery } from '@tanstack/react-query'
import { getRestriction, listRestrictions } from '../../api/restrictions'
import { useAuth } from '../auth/authContext'
import { localInputToUtcIso } from '../../utils/datetime'
import { scopesCanCollide, windowsOverlap } from './restrictionPreview'
import type { RestrictionDetail } from '../../types/api'
import type { RestrictionFormValues } from './restrictionFormRules'

export interface Overlap {
  id: number
  name: string
  level: RestrictionDetail['level']
  /** True when the two could match the same deployment, not merely the same hours. */
  sharesScope: boolean
}

/**
 * Restrictions whose window meets the one being edited (`FZ-108`).
 *
 * Advisory only. Overlapping restrictions are deliberately allowed (`FZ-020`) — a hard
 * freeze and an advisory over the same weekend is a normal thing to want — so this never
 * blocks submission. It exists because creating the *same* freeze twice is a mistake
 * nobody notices until two teams are arguing about which one applies.
 *
 * Details are fetched only for the restrictions whose window actually overlaps, because
 * the list endpoint carries no scope and comparing scope is what separates "these share a
 * weekend" from "these fight over the same deployment". In practice that is nought or one.
 */
export function useOverlappingRestrictions(
  values: RestrictionFormValues,
  excludeId?: number,
): { overlaps: Overlap[]; checked: boolean } {
  const { token } = useAuth()

  const live = useQuery({
    queryKey: ['restrictions', 'live'],
    queryFn: ({ signal }) => listRestrictions(token, ['ACTIVE', 'SCHEDULED'], signal),
  })

  let startsAt = ''
  let endsAt = ''
  try {
    if (values.startsAtLocal && values.endsAtLocal) {
      startsAt = localInputToUtcIso(values.startsAtLocal)
      endsAt = localInputToUtcIso(values.endsAtLocal)
    }
  } catch {
    // A half-typed datetime is not an error worth reporting; there is simply nothing to
    // compare yet.
  }

  const candidates = (live.data ?? [])
    .filter((restriction) => restriction.id !== excludeId)
    .filter(
      (restriction) =>
        startsAt !== '' &&
        windowsOverlap(startsAt, endsAt, restriction.startsAt, restriction.endsAt),
    )

  const details = useQuery({
    queryKey: ['restrictions', 'overlap-details', candidates.map((c) => c.id).join(',')],
    enabled: candidates.length > 0,
    queryFn: ({ signal }) =>
      Promise.all(candidates.map((c) => getRestriction(token, c.id, signal))),
  })

  const scope = {
    teamIds: values.teamIds,
    applicationIds: values.applicationIds,
    environmentIds: values.environmentIds,
  }

  const overlaps: Overlap[] = (details.data ?? []).map((restriction) => ({
    id: restriction.id,
    name: restriction.name,
    level: restriction.level,
    sharesScope: scopesCanCollide(scope, restriction.scope),
  }))

  return {
    overlaps,
    // "No overlap" is only worth printing once we know: before the window is set, or
    // while the list is loading, the honest answer is silence.
    checked: startsAt !== '' && !live.isPending && (candidates.length === 0 || !details.isPending),
  }
}
