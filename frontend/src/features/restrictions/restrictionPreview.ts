import type { ApplicationSummary, CatalogEntry, RestrictionScope } from '../../types/api'
import type { RestrictionFormValues } from './restrictionFormRules'

/**
 * What the form can say about a restriction before it exists (FZ-108).
 *
 * Pure and free of React: these decide the two claims `1e` makes while you type — what
 * the restriction will match, and whether it overlaps one that already exists. Both are
 * statements about domain rules, so they are tested directly rather than through the DOM.
 */

/** "8 hours", "3 days" — the duration `1e` prints under the window. */
export function windowDuration(startsAtLocal: string, endsAtLocal: string): string | null {
  if (!startsAtLocal || !endsAtLocal) return null

  const from = new Date(startsAtLocal).getTime()
  const to = new Date(endsAtLocal).getTime()
  if (Number.isNaN(from) || Number.isNaN(to) || to <= from) return null

  const minutes = Math.round((to - from) / 60_000)
  if (minutes < 60) return `${minutes} ${minutes === 1 ? 'minute' : 'minutes'}`

  const hours = Math.round(minutes / 60)
  if (hours < 48) return `${hours} ${hours === 1 ? 'hour' : 'hours'}`

  const days = Math.round(hours / 24)
  return `${days} days`
}

export interface MatchSummary {
  /** Selected names, in catalog order. Empty means the dimension places no constraint. */
  teams: string[]
  applications: string[]
  environments: string[]
  /** How many catalogued applications this scope can actually match. */
  matched: number
  total: number
  /** True when nothing is selected at all — there is nothing to describe yet. */
  empty: boolean
}

/**
 * What the scope currently selects, resolved to names and counted.
 *
 * The count is the useful half: a scope naming two applications and a team matches only
 * the applications in **both**, and the AND-across-dimensions rule (`FZ-020`) is easy to
 * read past until a number says "2 of 14".
 */
export function matchSummary(
  values: Pick<RestrictionFormValues, 'teamIds' | 'applicationIds' | 'environmentIds'>,
  teams: CatalogEntry[],
  applications: ApplicationSummary[],
  environments: CatalogEntry[],
): MatchSummary {
  const names = (entries: { id: number; name: string }[], ids: number[]) =>
    entries.filter((entry) => ids.includes(entry.id)).map((entry) => entry.name)

  const matched = applications.filter((application) => {
    const byApplication =
      values.applicationIds.length === 0 || values.applicationIds.includes(application.id)
    const byTeam =
      values.teamIds.length === 0 ||
      application.teamIds.some((teamId) => values.teamIds.includes(teamId))
    // Environments constrain where a deployment goes, not which application it is, so
    // they do not narrow this count.
    return byApplication && byTeam
  }).length

  return {
    teams: names(teams, values.teamIds),
    applications: names(applications, values.applicationIds),
    environments: names(environments, values.environmentIds),
    matched,
    total: applications.length,
    empty:
      values.teamIds.length === 0 &&
      values.applicationIds.length === 0 &&
      values.environmentIds.length === 0,
  }
}

/**
 * Whether two scopes can both match the same deployment.
 *
 * Per dimension, a restriction matches when its set is empty (a wildcard — `FZ-020`) or
 * contains the deployment's value. So two restrictions can collide on some deployment
 * exactly when, for **every** dimension, one of them is a wildcard or the two share a
 * value. One dimension with no common ground is enough to keep them apart.
 */
export function scopesCanCollide(a: RestrictionScope, b: RestrictionScope): boolean {
  const dimensionCollides = (left: number[], right: number[]) =>
    left.length === 0 || right.length === 0 || left.some((id) => right.includes(id))

  return (
    dimensionCollides(a.teamIds, b.teamIds) &&
    dimensionCollides(a.applicationIds, b.applicationIds) &&
    dimensionCollides(a.environmentIds, b.environmentIds)
  )
}

/** Whether two half-open windows share any instant. */
export function windowsOverlap(
  aStart: string,
  aEnd: string,
  bStart: string,
  bEnd: string,
): boolean {
  return aStart < bEnd && bStart < aEnd
}
