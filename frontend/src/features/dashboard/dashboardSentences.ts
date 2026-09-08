import type { RestrictionDetail, RestrictionSummary } from '../../types/api'

/**
 * The sentences the dashboard derives rather than displays (FZ-106).
 *
 * Kept out of the component and free of React on purpose. These are the only places the
 * product asserts something it was not told — "deploys are blocked in production and
 * staging", "deploys reopen", "clear from here" — and a sentence like that is worse than
 * no sentence if it is wrong. They are pure functions so they can be tested against the
 * awkward cases directly: overlapping windows, a freeze with no environments named, an
 * advisory that blocks nothing.
 */

/** A restriction stops deployments only if it is both in force and a hard freeze. */
export function isBlocking(restriction: RestrictionSummary): boolean {
  return restriction.status === 'ACTIVE' && restriction.level === 'HARD_FREEZE'
}

/**
 * The environments a set of in-force freezes covers, resolved to names.
 *
 * An empty `environmentIds` is a wildcard, not an empty set (`FZ-020`) — the restriction
 * places no constraint on environment and therefore covers all of them. Returning
 * `'every'` rather than the resolved list keeps that distinction: naming today's
 * environments would be a narrower claim than the restriction actually makes, and would
 * quietly stop being true the day someone adds one.
 */
export function blockedEnvironments(
  blocking: RestrictionDetail[],
  environmentNames: Map<number, string>,
): 'every' | string[] {
  if (blocking.length === 0) return []
  if (blocking.some((restriction) => restriction.scope.environmentIds.length === 0)) {
    return 'every'
  }

  const names = new Set<string>()
  for (const restriction of blocking) {
    for (const id of restriction.scope.environmentIds) {
      // An id with no name yet means the catalog is still loading; skip rather than
      // print "#3" in the one sentence people read first.
      const name = environmentNames.get(id)
      if (name) names.add(name)
    }
  }
  return [...names].sort()
}

/** "production and staging", "production, staging and canary", "every environment". */
export function describeEnvironments(environments: 'every' | string[]): string {
  if (environments === 'every') return 'every environment'
  if (environments.length === 0) return 'no environment'
  if (environments.length === 1) return environments[0]
  return `${environments.slice(0, -1).join(', ')} and ${environments[environments.length - 1]}`
}

export interface StatusLine {
  blocking: boolean
  sentence: string
  /** When blocking stops, if it does. ISO-8601. */
  until: string | null
}

/**
 * The one sentence that says what is true right now.
 *
 * "Until" is the *latest* end among the freezes in force, not the earliest: while any of
 * them is still running deploys are still blocked, so the earliest end would promise a
 * reopening that does not happen.
 */
export function statusLine(
  blocking: RestrictionDetail[],
  environmentNames: Map<number, string>,
): StatusLine {
  if (blocking.length === 0) {
    return { blocking: false, sentence: 'No restriction is blocking deploys.', until: null }
  }

  const where = describeEnvironments(blockedEnvironments(blocking, environmentNames))
  const until = blocking
    .map((restriction) => restriction.endsAt)
    .reduce((latest, end) => (end > latest ? end : latest))

  return { blocking: true, sentence: `Deploys are blocked in ${where}`, until }
}

export interface Transition {
  /** ISO-8601 instant the change happens. */
  at: string
  sentence: string
  /** True once nothing further is known to happen — the last line. */
  clear: boolean
}

/**
 * "Then what" — the forward list of changes, as date plus plain sentence (`1c`).
 *
 * A list of what *exists* is the group of cards above this. The value here is that it
 * says what *happens*, in the order it happens, so the answer to "when can I deploy
 * again?" is read rather than worked out.
 */
export function thenWhat(
  active: RestrictionSummary[],
  upcoming: RestrictionSummary[],
  now: Date = new Date(),
): Transition[] {
  const nowIso = now.toISOString()
  const transitions: Transition[] = []

  for (const restriction of active) {
    transitions.push({
      at: restriction.endsAt,
      sentence: `${restriction.name} completes.`,
      clear: false,
    })
  }

  for (const restriction of upcoming) {
    if (restriction.endsAt <= nowIso) continue
    transitions.push({
      at: restriction.startsAt,
      sentence: `${restriction.name} starts. ${
        restriction.level === 'HARD_FREEZE' ? describeDuration(restriction) : 'Advisory only.'
      }`,
      clear: false,
    })
    transitions.push({
      at: restriction.endsAt,
      sentence: `${restriction.name} completes.`,
      clear: false,
    })
  }

  transitions.sort((a, b) => a.at.localeCompare(b.at))

  /*
   * "Deploys reopen" is only true if nothing else is still blocking at that instant.
   * Overlapping restrictions are deliberately allowed (`FZ-020`), so this is a real case
   * and not a defensive one: two freezes running into each other must not produce a line
   * announcing that deploys reopen in the middle of the second.
   */
  const freezes = [...active, ...upcoming].filter(
    (restriction) => restriction.level === 'HARD_FREEZE',
  )
  const annotated = transitions.map((transition) => {
    if (!transition.sentence.endsWith('completes.')) return transition
    const stillBlocked = freezes.some(
      (freeze) => freeze.startsAt <= transition.at && freeze.endsAt > transition.at,
    )
    return stillBlocked
      ? transition
      : { ...transition, sentence: `${transition.sentence} Deploys reopen.` }
  })

  if (annotated.length === 0) return []

  return [
    ...annotated,
    { at: annotated[annotated.length - 1].at, sentence: 'Clear from here.', clear: true },
  ]
}

/** "8 hours, hard freeze." — the shape `1c` writes it in. */
function describeDuration(restriction: RestrictionSummary): string {
  const hours = Math.round(
    (new Date(restriction.endsAt).getTime() - new Date(restriction.startsAt).getTime()) / 3_600_000,
  )
  if (hours < 24) return `${hours} ${hours === 1 ? 'hour' : 'hours'}, hard freeze.`
  const days = Math.round(hours / 24)
  return `${days} ${days === 1 ? 'day' : 'days'}, hard freeze.`
}

/** "next starts in 17 days" — the Scheduled metric's caption. */
export function nextStart(upcoming: RestrictionSummary[], now: Date = new Date()): string | null {
  const next = upcoming
    .map((restriction) => restriction.startsAt)
    .filter((startsAt) => new Date(startsAt) > now)
    .sort()[0]
  if (!next) return null

  const hours = Math.round((new Date(next).getTime() - now.getTime()) / 3_600_000)
  if (hours < 1) return 'next starts within the hour'
  if (hours < 48) return `next starts in ${hours} ${hours === 1 ? 'hour' : 'hours'}`
  return `next starts in ${Math.round(hours / 24)} days`
}

/** "One active restriction. Two scheduled." — the sentence under the page title. */
export function countsSentence(active: number, scheduled: number): string {
  const word = (n: number, singular: string) =>
    `${n === 0 ? 'No' : n} ${n === 1 ? singular : `${singular}s`}`
  return `${word(active, 'active restriction')}. ${
    scheduled === 0 ? 'None scheduled' : `${scheduled} scheduled`
  }.`
}
