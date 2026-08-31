/**
 * Types mirroring the backend API. Kept deliberately close to the wire format rather
 * than reshaped, so a change on either side is obvious here first.
 */

export type RestrictionLevel = 'ADVISORY' | 'HARD_FREEZE'

export type RestrictionStatus = 'SCHEDULED' | 'ACTIVE' | 'COMPLETED' | 'CANCELLED'

export type RestrictionType = 'DEPLOYMENT_FREEZE'

/** Shape returned by `GET /api/restrictions` — no scope, by design (FZ-021). */
export interface RestrictionSummary {
  id: number
  name: string
  reason: string
  type: RestrictionType
  level: RestrictionLevel
  status: RestrictionStatus
  /** ISO-8601 UTC instant. */
  startsAt: string
  /** ISO-8601 UTC instant. */
  endsAt: string
  createdBy: number
  createdAt: string
  updatedAt: string
}

/** Scope, as the API expresses it: one list per dimension (01-domain.md). */
export interface RestrictionScope {
  teamIds: number[]
  applicationIds: number[]
  environmentIds: number[]
}

/** Full representation from `GET /api/restrictions/{id}` — summary plus scope (FZ-022). */
export interface RestrictionDetail extends RestrictionSummary {
  description: string | null
  scope: RestrictionScope
}

/**
 * `POST /api/restrictions` body. No `type` or `status`: both are server-controlled, so
 * the client does not get to state them (FZ-020).
 *
 * `startsAt`/`endsAt` are ISO-8601 **UTC** instants — never the zoneless value a
 * datetime-local input produces.
 */
export interface CreateRestrictionBody {
  name: string
  description?: string | null
  reason: string
  level: RestrictionLevel
  startsAt: string
  endsAt: string
  scope: RestrictionScope
}

/** Teams and environments: name plus timestamps (FZ-013, FZ-015). */
export interface CatalogEntry {
  id: number
  name: string
  createdAt: string
  updatedAt: string
}

/** Applications additionally carry their associated teams (FZ-014). */
export interface ApplicationSummary extends CatalogEntry {
  teamIds: number[]
}

/** Shape returned by `POST /api/dev/token` (local development only — FZ-035). */
export interface DevSignInResponse {
  token: string
  userId: number
  organizationId: number
  email: string
  role: 'ADMINISTRATOR' | 'MEMBER'
}

export interface CurrentUser {
  userId: number
  organizationId: number
  email: string
  role: 'ADMINISTRATOR' | 'MEMBER'
}

export type IntegrationType = 'SLACK' | 'EMAIL' | 'WEBHOOK'

/**
 * Note the absent `config`: the API never returns stored channel settings, because they
 * can hold a credential. `summary` identifies a destination without being enough to
 * reuse it.
 */
export interface Integration {
  id: number
  type: IntegrationType
  enabled: boolean
  summary: string
  createdAt: string
  updatedAt: string
}

/** A machine credential. Note the absence of the key itself — see `IssuedApiKey`. */
export interface ApiKey {
  id: number
  name: string
  keyPrefix: string
  createdBy: number
  createdAt: string
  revokedAt: string | null
  revoked: boolean
}

/** The creation response, and the only place the raw `key` ever appears. */
export interface IssuedApiKey {
  id: number
  name: string
  keyPrefix: string
  key: string
  createdBy: number
  createdAt: string
}

export interface Organization {
  id: number
  name: string
  startingSoonLeadTimeMinutes: number
}

/** A restriction as it was when a check happened — denormalised, immune to a later rename. */
export interface MatchedRestrictionSummary {
  id: number
  name: string
  level: RestrictionLevel
}

/**
 * A record that somebody asked whether they could deploy, and what they were told.
 *
 * A check, not a deployment: FreezeHub sees the question and nothing after it.
 */
export interface DeploymentCheck {
  id: number
  application: string
  environment: string
  decision: 'ALLOW' | 'BLOCK'
  blockedReason: 'RESTRICTION' | 'UNREGISTERED' | null
  matchedRestrictions: MatchedRestrictionSummary[] | null
  actor: string | null
  reference: string | null
  source: string | null
  /** The credential that asked — a pipeline, never a person. `actor` is the person. */
  checkedBy: string
  checkedAt: string
}
