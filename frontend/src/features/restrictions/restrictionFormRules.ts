/**
 * Client-side mirrors of the backend's creation rules (01-domain.md, FZ-020).
 *
 * These exist **only** to give fast feedback before a round trip. They are not the source
 * of truth and must never be treated as such: the backend re-validates everything, and
 * its rejection is what actually decides (CLAUDE.md §5 — the frontend must not duplicate
 * domain rules as an independent source of truth). If the two ever disagree, the backend
 * is right and this file is the bug.
 */

export interface RestrictionFormValues {
  name: string
  description: string
  reason: string
  level: string
  startsAtLocal: string
  endsAtLocal: string
  teamIds: number[]
  applicationIds: number[]
  environmentIds: number[]
}

export type FieldErrors = Partial<Record<keyof RestrictionFormValues | 'scope', string>>

export function validateRestrictionForm(
  values: RestrictionFormValues,
  now: Date = new Date(),
): FieldErrors {
  const errors: FieldErrors = {}

  if (!values.name.trim()) errors.name = 'Give the restriction a name.'
  if (!values.reason.trim()) errors.reason = 'A reason is required — this is what engineers will read.'
  if (!values.startsAtLocal) errors.startsAtLocal = 'Choose when the restriction starts.'
  if (!values.endsAtLocal) errors.endsAtLocal = 'Choose when the restriction ends.'

  if (values.startsAtLocal && values.endsAtLocal) {
    const startsAt = new Date(values.startsAtLocal)
    const endsAt = new Date(values.endsAtLocal)

    if (!(startsAt.getTime() < endsAt.getTime())) {
      errors.endsAtLocal = 'The end must be after the start.'
    } else if (endsAt.getTime() <= now.getTime()) {
      // Domain invariant 2 forbids only a window entirely in the past; a start already
      // passed is allowed and simply activates on the next lifecycle pass.
      errors.endsAtLocal = 'This restriction is entirely in the past.'
    }
  }

  const scopeTargets =
    values.teamIds.length + values.applicationIds.length + values.environmentIds.length
  if (scopeTargets === 0) {
    errors.scope = 'Select at least one team, application or environment.'
  }

  return errors
}
