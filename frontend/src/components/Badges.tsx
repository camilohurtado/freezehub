import type { RestrictionLevel, RestrictionStatus } from '../types/api'
import styles from './Badges.module.css'

/**
 * Shared badges. Extracted once the dashboard and the list both needed them, so the way
 * a level or status reads is defined in one place rather than drifting between pages.
 */

const LEVEL_TEXT: Record<RestrictionLevel, string> = {
  HARD_FREEZE: 'Blocks deploys',
  ADVISORY: 'Advisory',
}

const LEVEL_TITLE: Record<RestrictionLevel, string> = {
  HARD_FREEZE: 'Deployments matching this restriction are blocked',
  ADVISORY: 'Deployments are allowed, but this restriction is reported',
}

export function LevelBadge({ level }: { level: RestrictionLevel }) {
  return (
    <span
      className={level === 'HARD_FREEZE' ? styles.levelBlocking : styles.levelAdvisory}
      title={LEVEL_TITLE[level]}
    >
      {LEVEL_TEXT[level]}
    </span>
  )
}

const STATUS_CLASS: Record<RestrictionStatus, string> = {
  ACTIVE: styles.statusActive,
  SCHEDULED: styles.statusScheduled,
  COMPLETED: styles.statusCompleted,
  CANCELLED: styles.statusCancelled,
}

export function StatusBadge({ status }: { status: RestrictionStatus }) {
  return <span className={STATUS_CLASS[status]}>{status.toLowerCase()}</span>
}
