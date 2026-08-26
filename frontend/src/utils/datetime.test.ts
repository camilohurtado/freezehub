import { describe, expect, test } from 'vitest'
import { formatInstant, localInputToUtcIso, utcIsoToLocalInput } from './datetime'

/**
 * The suite runs at UTC-5 (see vite.config.ts). That is the whole point: on a UTC machine
 * a missing conversion is indistinguishable from a correct one, so these assertions would
 * pass against a broken implementation.
 */
describe('datetime', () => {
  test('the suite is running in a non-UTC zone', () => {
    // Guards the guard: if this ever becomes UTC, the assertions below stop proving
    // anything and would need rethinking rather than silently weakening.
    expect(new Date('2026-11-27T09:00:00Z').getTimezoneOffset()).not.toBe(0)
  })

  test('converts a zoneless local input to the correct UTC instant', () => {
    // 09:00 at UTC-5 is 14:00 UTC. Returning "2026-11-27T09:00:00.000Z" would mean the
    // value was passed through without conversion.
    expect(localInputToUtcIso('2026-11-27T09:00')).toBe('2026-11-27T14:00:00.000Z')
  })

  test('does not shift an instant that is already midnight local', () => {
    expect(localInputToUtcIso('2026-12-02T00:00')).toBe('2026-12-02T05:00:00.000Z')
  })

  test('round-trips through the input format without drift', () => {
    const iso = '2026-11-27T14:00:00.000Z'
    expect(localInputToUtcIso(utcIsoToLocalInput(iso))).toBe(iso)
  })

  test('renders a UTC instant as local wall-clock time for the input', () => {
    // Not a slice of the ISO string, which would show 14:00 and label it local.
    expect(utcIsoToLocalInput('2026-11-27T14:00:00.000Z')).toBe('2026-11-27T09:00')
  })

  test('rejects an unparseable value rather than silently producing a wrong instant', () => {
    expect(() => localInputToUtcIso('not-a-date')).toThrow()
  })

  test('formats an instant in local time and always names the zone', () => {
    const formatted = formatInstant('2026-11-27T14:00:00.000Z')
    expect(formatted).toContain('09:00')
    expect(formatted).toMatch(/GMT|UTC/)
  })

  test('returns the raw value rather than "Invalid Date" when it cannot be parsed', () => {
    expect(formatInstant('nonsense')).toBe('nonsense')
  })
})
