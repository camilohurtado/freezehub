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

/**
 * Converts a `<input type="datetime-local">` value to an ISO-8601 UTC instant.
 *
 * The input's value is **zoneless** — "2026-11-27T09:00" means 09:00 wherever the user
 * happens to be. Sending it as-is would shift every freeze window by the viewer's offset,
 * which is silently invisible on a UTC machine and wrong everywhere else.
 *
 * `new Date(value)` is what does the work: ECMAScript parses a date-time form with no
 * offset as *local* time, so `toISOString()` then yields the correct UTC instant.
 */
export function localInputToUtcIso(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    throw new Error(`Not a valid datetime-local value: ${value}`)
  }
  return date.toISOString()
}

/**
 * The inverse: renders a UTC instant as the zoneless local string the input expects.
 *
 * Built from local getters rather than by slicing the ISO string, which would show the
 * user a UTC wall-clock time labelled as their own.
 */
export function utcIsoToLocalInput(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''

  const pad = (value: number) => String(value).padStart(2, '0')
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}`
  )
}

/** Whole days from now until `iso`; negative once the instant has passed. */
export function daysUntil(iso: string, now: Date = new Date()): number {
  const target = new Date(iso)
  if (Number.isNaN(target.getTime())) return 0
  return Math.round((target.getTime() - now.getTime()) / 86_400_000)
}
