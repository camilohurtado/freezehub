import { describe, expect, test } from 'vitest'
import {
  blockedEnvironments,
  countsSentence,
  describeEnvironments,
  nextStart,
  statusLine,
  thenWhat,
} from './dashboardSentences'
import type { RestrictionDetail, RestrictionSummary } from '../../types/api'

const NOW = new Date('2026-11-24T14:32:00Z')

function summary(over: Partial<RestrictionSummary> = {}): RestrictionSummary {
  return {
    id: 1,
    name: 'Black Friday Freeze',
    reason: 'Revenue-critical trading period',
    type: 'DEPLOYMENT_FREEZE',
    level: 'HARD_FREEZE',
    status: 'ACTIVE',
    startsAt: '2026-11-24T18:00:00Z',
    endsAt: '2026-12-02T09:00:00Z',
    createdBy: 1,
    createdAt: '2026-11-03T10:12:00Z',
    updatedAt: '2026-11-18T16:40:00Z',
    ...over,
  }
}

function detail(over: Partial<RestrictionDetail> = {}): RestrictionDetail {
  return {
    ...summary(over),
    description: null,
    scope: { teamIds: [], applicationIds: [], environmentIds: [] },
    ...over,
  }
}

const environments = new Map([
  [1, 'production'],
  [2, 'staging'],
  [3, 'canary'],
])

describe('blockedEnvironments', () => {
  test('names the environments the freezes actually cover', () => {
    const blocking = [detail({ scope: { teamIds: [], applicationIds: [], environmentIds: [1, 2] } })]
    expect(blockedEnvironments(blocking, environments)).toEqual(['production', 'staging'])
  })

  test('an empty dimension is a wildcard, not an empty set', () => {
    // FZ-020's rule. Resolving it to today's environment list would be a narrower claim
    // than the restriction makes, and would quietly stop being true the day one is added.
    const blocking = [detail({ scope: { teamIds: [], applicationIds: [], environmentIds: [] } })]
    expect(blockedEnvironments(blocking, environments)).toBe('every')
  })

  test('one wildcard freeze wins over another that names environments', () => {
    const blocking = [
      detail({ id: 1, scope: { teamIds: [], applicationIds: [], environmentIds: [1] } }),
      detail({ id: 2, scope: { teamIds: [], applicationIds: [], environmentIds: [] } }),
    ]
    expect(blockedEnvironments(blocking, environments)).toBe('every')
  })

  test('the union across freezes, de-duplicated', () => {
    const blocking = [
      detail({ id: 1, scope: { teamIds: [], applicationIds: [], environmentIds: [1] } }),
      detail({ id: 2, scope: { teamIds: [], applicationIds: [], environmentIds: [1, 2] } }),
    ]
    expect(blockedEnvironments(blocking, environments)).toEqual(['production', 'staging'])
  })

  test('an id with no name yet is skipped rather than printed as a number', () => {
    const blocking = [detail({ scope: { teamIds: [], applicationIds: [], environmentIds: [9] } })]
    expect(blockedEnvironments(blocking, environments)).toEqual([])
  })
})

describe('describeEnvironments', () => {
  test.each([
    [['production'], 'production'],
    [['production', 'staging'], 'production and staging'],
    [['production', 'staging', 'canary'], 'production, staging and canary'],
  ])('%s reads as "%s"', (names, expected) => {
    expect(describeEnvironments(names)).toBe(expected)
  })

  test('the wildcard reads as a claim about all of them', () => {
    expect(describeEnvironments('every')).toBe('every environment')
  })
})

describe('statusLine', () => {
  test('says what is blocked and until when', () => {
    const blocking = [detail({ scope: { teamIds: [], applicationIds: [], environmentIds: [1, 2] } })]
    const line = statusLine(blocking, environments)

    expect(line.blocking).toBe(true)
    expect(line.sentence).toBe('Deploys are blocked in production and staging')
    expect(line.until).toBe('2026-12-02T09:00:00Z')
  })

  test('takes the latest end, not the earliest', () => {
    // While either freeze runs, deploys are still blocked. The earliest end would promise
    // a reopening that does not happen.
    const blocking = [
      detail({ id: 1, endsAt: '2026-12-02T09:00:00Z' }),
      detail({ id: 2, endsAt: '2026-12-09T09:00:00Z' }),
    ]
    expect(statusLine(blocking, environments).until).toBe('2026-12-09T09:00:00Z')
  })

  test('says so plainly when nothing is blocking', () => {
    const line = statusLine([], environments)
    expect(line.blocking).toBe(false)
    expect(line.sentence).toBe('No restriction is blocking deploys.')
    expect(line.until).toBeNull()
  })
})

describe('thenWhat', () => {
  test('is empty when nothing is happening', () => {
    expect(thenWhat([], [], NOW)).toEqual([])
  })

  test('says deploys reopen when the last freeze completes', () => {
    const active = [summary({ endsAt: '2026-12-02T09:00:00Z' })]
    const list = thenWhat(active, [], NOW)

    expect(list[0].sentence).toBe('Black Friday Freeze completes. Deploys reopen.')
    expect(list[list.length - 1].sentence).toBe('Clear from here.')
  })

  test('does not promise a reopening in the middle of an overlapping freeze', () => {
    // Overlaps are deliberately allowed (FZ-020), so this is a real case. The first
    // freeze ending changes nothing while the second is already running.
    const active = [summary({ id: 1, endsAt: '2026-12-02T09:00:00Z' })]
    const upcoming = [
      summary({
        id: 2,
        name: 'Core banking migration',
        status: 'SCHEDULED',
        startsAt: '2026-11-30T22:00:00Z',
        endsAt: '2026-12-06T06:00:00Z',
      }),
    ]

    const list = thenWhat(active, upcoming, NOW)
    const firstCompletion = list.find((t) => t.sentence.startsWith('Black Friday Freeze completes'))

    expect(firstCompletion?.sentence).toBe('Black Friday Freeze completes.')
    expect(firstCompletion?.sentence).not.toContain('Deploys reopen')
    // The later one does reopen them.
    expect(
      list.find((t) => t.sentence.startsWith('Core banking migration completes'))?.sentence,
    ).toBe('Core banking migration completes. Deploys reopen.')
  })

  test('an advisory says it blocks nothing', () => {
    const upcoming = [
      summary({
        id: 2,
        name: 'Year-end change window',
        status: 'SCHEDULED',
        level: 'ADVISORY',
        startsAt: '2026-12-20T00:00:00Z',
        endsAt: '2027-01-02T09:00:00Z',
      }),
    ]
    const list = thenWhat([], upcoming, NOW)
    expect(list[0].sentence).toBe('Year-end change window starts. Advisory only.')
  })

  test('a hard freeze says how long it lasts', () => {
    const upcoming = [
      summary({
        id: 2,
        name: 'Core banking migration',
        status: 'SCHEDULED',
        startsAt: '2026-12-11T22:00:00Z',
        endsAt: '2026-12-12T06:00:00Z',
      }),
    ]
    expect(thenWhat([], upcoming, NOW)[0].sentence).toBe(
      'Core banking migration starts. 8 hours, hard freeze.',
    )
  })

  test('is ordered by when things happen, not by what they are', () => {
    const active = [summary({ id: 1, endsAt: '2026-12-02T09:00:00Z' })]
    const upcoming = [
      summary({ id: 2, name: 'Later', status: 'SCHEDULED', startsAt: '2026-12-20T00:00:00Z', endsAt: '2026-12-21T00:00:00Z' }),
      summary({ id: 3, name: 'Sooner', status: 'SCHEDULED', startsAt: '2026-11-28T00:00:00Z', endsAt: '2026-11-29T00:00:00Z' }),
    ]

    const dates = thenWhat(active, upcoming, NOW).map((t) => t.at)
    expect(dates).toEqual([...dates].sort())
  })

  test('ends with "Clear from here." exactly once', () => {
    const list = thenWhat([summary()], [], NOW)
    expect(list.filter((t) => t.clear)).toHaveLength(1)
    expect(list[list.length - 1].clear).toBe(true)
  })
})

describe('nextStart', () => {
  test('counts days for anything further out than two', () => {
    const upcoming = [summary({ status: 'SCHEDULED', startsAt: '2026-12-11T14:32:00Z' })]
    expect(nextStart(upcoming, NOW)).toBe('next starts in 17 days')
  })

  test('counts hours when it is close', () => {
    const upcoming = [summary({ status: 'SCHEDULED', startsAt: '2026-11-25T02:32:00Z' })]
    expect(nextStart(upcoming, NOW)).toBe('next starts in 12 hours')
  })

  test('is null when nothing is scheduled', () => {
    expect(nextStart([], NOW)).toBeNull()
  })
})

describe('countsSentence', () => {
  test.each([
    [1, 2, '1 active restriction. 2 scheduled.'],
    [0, 0, 'No active restrictions. None scheduled.'],
    [2, 1, '2 active restrictions. 1 scheduled.'],
  ])('(%i active, %i scheduled) reads as "%s"', (active, scheduled, expected) => {
    expect(countsSentence(active, scheduled)).toBe(expected)
  })
})
