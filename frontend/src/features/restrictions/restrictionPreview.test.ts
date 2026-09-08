import { describe, expect, test } from 'vitest'
import {
  matchSummary,
  scopesCanCollide,
  windowDuration,
  windowsOverlap,
} from './restrictionPreview'
import type { ApplicationSummary, CatalogEntry, RestrictionScope } from '../../types/api'

const stamps = { createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' }
const team = (id: number, name: string): CatalogEntry => ({ id, name, ...stamps })
const app = (id: number, name: string, teamIds: number[] = []): ApplicationSummary => ({
  id,
  name,
  teamIds,
  ...stamps,
})

const teams = [team(1, 'Payments'), team(2, 'Platform')]
const applications = [
  app(10, 'payments-api', [1]),
  app(11, 'ledger-service', [1]),
  app(12, 'checkout-web', [2]),
  app(13, 'docs-site', []),
]
const environments = [team(20, 'production'), team(21, 'staging')]

const scope = (over: Partial<RestrictionScope> = {}): RestrictionScope => ({
  teamIds: [],
  applicationIds: [],
  environmentIds: [],
  ...over,
})

describe('windowDuration', () => {
  test.each([
    ['2026-12-11T22:00', '2026-12-12T06:00', '8 hours'],
    ['2026-12-11T22:00', '2026-12-11T22:45', '45 minutes'],
    ['2026-12-11T22:00', '2026-12-11T23:00', '1 hour'],
    ['2026-12-01T00:00', '2026-12-08T00:00', '7 days'],
  ])('%s to %s reads as "%s"', (from, to, expected) => {
    expect(windowDuration(from, to)).toBe(expected)
  })

  test('is null until both ends are set', () => {
    expect(windowDuration('', '2026-12-12T06:00')).toBeNull()
    expect(windowDuration('2026-12-11T22:00', '')).toBeNull()
  })

  test('is null for a window that ends before it starts', () => {
    // The form already says so as a field error; repeating it as "-8 hours" would be
    // noise beside the real message.
    expect(windowDuration('2026-12-12T06:00', '2026-12-11T22:00')).toBeNull()
  })
})

describe('matchSummary', () => {
  test('resolves each dimension to names', () => {
    const summary = matchSummary(
      { teamIds: [1], applicationIds: [10, 11], environmentIds: [20] },
      teams,
      applications,
      environments,
    )

    expect(summary.teams).toEqual(['Payments'])
    expect(summary.applications).toEqual(['payments-api', 'ledger-service'])
    expect(summary.environments).toEqual(['production'])
  })

  test('counts the applications the scope can actually match', () => {
    // Both named applications belong to Payments, so both survive the AND.
    const summary = matchSummary(
      { teamIds: [1], applicationIds: [10, 11], environmentIds: [] },
      teams,
      applications,
      environments,
    )

    expect(summary.matched).toBe(2)
    expect(summary.total).toBe(4)
  })

  test('a team and an application that do not intersect match nothing', () => {
    // The consequence of AND-across-dimensions that FZ-020 documented and nothing showed.
    // checkout-web is Platform's; naming it alongside team Payments matches no deployment.
    const summary = matchSummary(
      { teamIds: [1], applicationIds: [12], environmentIds: [] },
      teams,
      applications,
      environments,
    )

    expect(summary.matched).toBe(0)
  })

  test('an empty dimension is a wildcard, so a team alone matches all its applications', () => {
    const summary = matchSummary(
      { teamIds: [1], applicationIds: [], environmentIds: [] },
      teams,
      applications,
      environments,
    )

    expect(summary.matched).toBe(2)
  })

  test('environments do not narrow the application count', () => {
    // They constrain where a deployment goes, not which application it is.
    const summary = matchSummary(
      { teamIds: [], applicationIds: [], environmentIds: [20] },
      teams,
      applications,
      environments,
    )

    expect(summary.matched).toBe(4)
  })

  test('reports an untouched scope as empty', () => {
    const summary = matchSummary(
      { teamIds: [], applicationIds: [], environmentIds: [] },
      teams,
      applications,
      environments,
    )

    expect(summary.empty).toBe(true)
  })
})

describe('scopesCanCollide', () => {
  test('two wildcards collide', () => {
    expect(scopesCanCollide(scope(), scope())).toBe(true)
  })

  test('a wildcard collides with anything', () => {
    expect(scopesCanCollide(scope(), scope({ environmentIds: [20] }))).toBe(true)
  })

  test('the same environment collides', () => {
    expect(
      scopesCanCollide(scope({ environmentIds: [20] }), scope({ environmentIds: [20, 21] })),
    ).toBe(true)
  })

  test('different environments do not', () => {
    expect(
      scopesCanCollide(scope({ environmentIds: [20] }), scope({ environmentIds: [21] })),
    ).toBe(false)
  })

  test('one dimension with no common ground is enough to keep them apart', () => {
    // Same environment, different teams: no deployment satisfies both.
    expect(
      scopesCanCollide(
        scope({ teamIds: [1], environmentIds: [20] }),
        scope({ teamIds: [2], environmentIds: [20] }),
      ),
    ).toBe(false)
  })

  test('every dimension must find common ground, not just one', () => {
    expect(
      scopesCanCollide(
        scope({ teamIds: [1], applicationIds: [10] }),
        scope({ teamIds: [1], applicationIds: [12] }),
      ),
    ).toBe(false)
  })
})

describe('windowsOverlap', () => {
  test('windows that share time overlap', () => {
    expect(windowsOverlap('2026-12-01', '2026-12-05', '2026-12-03', '2026-12-08')).toBe(true)
  })

  test('windows that only touch do not', () => {
    // Half-open: one ends exactly as the other starts, so no instant is in both.
    expect(windowsOverlap('2026-12-01', '2026-12-05', '2026-12-05', '2026-12-08')).toBe(false)
  })

  test('a window entirely inside another overlaps', () => {
    expect(windowsOverlap('2026-12-01', '2026-12-31', '2026-12-10', '2026-12-11')).toBe(true)
  })

  test('separate windows do not', () => {
    expect(windowsOverlap('2026-12-01', '2026-12-05', '2026-12-20', '2026-12-25')).toBe(false)
  })
})
