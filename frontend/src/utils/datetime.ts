/**
 * Presentation-side date handling.
 *
 * Instants are stored and compared in UTC; the time zone is purely presentational
 * (01-domain.md invariants 9 and 10). Nothing here adjusts a stored instant — it only
 * formats one.
 */

/**
 * Renders an ISO-8601 instant in the viewer's local zone, **with the zone shown**.
 *
 * The zone is not decoration: "the freeze starts at 09:00" is meaningless to a
 * distributed team without it.
 */
export function formatInstant(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso

  // Explicit components rather than dateStyle/timeStyle: Intl throws if timeZoneName is
  // combined with either of those, and the zone is not optional here.
  return new Intl.DateTimeFormat(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    timeZoneName: 'short',
  }).format(date)
}

/** Whole days from now until `iso`; negative once the instant has passed. */
export function daysUntil(iso: string, now: Date = new Date()): number {
  const target = new Date(iso)
  if (Number.isNaN(target.getTime())) return 0
  return Math.round((target.getTime() - now.getTime()) / 86_400_000)
}
