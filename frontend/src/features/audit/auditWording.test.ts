import { describe, expect, test } from 'vitest'
import { describeAction, describeDetails } from './auditWording'
import type { AuditEvent } from '../../types/api'

const event = (overrides: Partial<AuditEvent> = {}): AuditEvent => ({
  id: 1,
  actorType: 'USER',
  actorId: 1,
  actorLabel: 'dev@acme.test',
  action: 'RESTRICTION_CREATED',
  resourceType: 'RESTRICTION',
  resourceId: 7,
  details: null,
  occurredAt: '2026-09-01T10:00:00Z',
  ...overrides,
})

describe('describeAction', () => {
  test('composes the kind of thing into the sentence', () => {
    // The backend stores three catalog actions and lets resourceType say which kind
    // (FZ-072), so the wording is assembled here rather than there being nine names.
    expect(describeAction(event({ action: 'CATALOG_RENAMED', resourceType: 'APPLICATION' })))
      .toBe('Renamed application')
    expect(describeAction(event({ action: 'CATALOG_RENAMED', resourceType: 'ENVIRONMENT' })))
      .toBe('Renamed environment')
    expect(describeAction(event({ action: 'CATALOG_DELETED', resourceType: 'TEAM' })))
      .toBe('Removed team')
  })

  test('reads as English for the actions that are not about the catalog', () => {
    expect(describeAction(event({ action: 'API_KEY_ISSUED', resourceType: 'API_KEY' })))
      .toBe('Issued an API key')
    expect(describeAction(event({ action: 'RESTRICTION_ACTIVATED' }))).toBe('Restriction took effect')
  })

  test('degrades readably for an action it has never heard of', () => {
    // A backend that learns a new action must not render as a blank row.
    expect(describeAction(event({ action: 'SOMETHING_NEW_HAPPENED' })))
      .toBe('something new happened')
  })
})

describe('describeDetails', () => {
  test('renders a before-and-after change as one', () => {
    // The entry that answers "why did every deploy start being refused?"
    expect(describeDetails({ name: { from: 'payments-api', to: 'payments-service' } }))
      .toEqual(['name: payments-api → payments-service'])
  })

  test('renders a flat object as plain fields', () => {
    // Both shapes reach this: a diff from an edit, and a flat object from everything else.
    expect(describeDetails({ name: 'gitlab-ci', keyPrefix: 'fzh_abc123' }))
      .toEqual(['name: gitlab-ci', 'keyPrefix: fzh_abc123'])
  })

  test('says "nothing" rather than showing an empty value', () => {
    // A field cleared reads as a change, not as a blank half of an arrow.
    expect(describeDetails({ description: { from: 'Peak trading', to: null } }))
      .toEqual(['description: Peak trading → nothing'])
    expect(describeDetails({ teamIds: { from: [], to: ['1', '2'] } }))
      .toEqual(['teamIds: nothing → 1, 2'])
  })

  test('has nothing to say when there are no details', () => {
    expect(describeDetails(null)).toEqual([])
  })
})
