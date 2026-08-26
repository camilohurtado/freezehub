import { Link } from 'react-router'
import type { RestrictionSummary } from '../../types/api'
import { LevelBadge } from '../../components/Badges'
import { formatInstant } from '../../utils/datetime'
import styles from './RestrictionCard.module.css'

/**
 * One restriction in a dashboard group.
 *
 * Level is shown prominently: whether a freeze *blocks* a deployment or merely warns is
 * the single most consequential thing an engineer needs from a glance
 * (00-product.md — ADVISORY vs HARD_FREEZE).
 */
export function RestrictionCard({ restriction }: { restriction: RestrictionSummary }) {
  return (
    <li className={styles.card}>
      <div className={styles.header}>
        <Link className={styles.name} to={`/restrictions/${restriction.id}`}>
          {restriction.name}
        </Link>
        <LevelBadge level={restriction.level} />
      </div>

      <p className={styles.reason}>{restriction.reason}</p>

      <dl className={styles.window}>
        <dt className={styles.term}>From</dt>
        <dd className={styles.value}>{formatInstant(restriction.startsAt)}</dd>
        <dt className={styles.term}>Until</dt>
        <dd className={styles.value}>{formatInstant(restriction.endsAt)}</dd>
      </dl>
    </li>
  )
}
