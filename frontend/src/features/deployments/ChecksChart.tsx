import styles from './DeploymentChecksPage.module.css'

export interface ChartDay {
  date: string
  allowed: number
  refused: number
}

/**
 * The fortnight of decisions, drawn above the list (`1f`, FZ-109).
 *
 * A refusal spike is a shape before it is a number, and the point of putting it here is
 * that it is visible before a single row is read.
 *
 * Bars are stacked and share one scale — the busiest day in the window — so a column's
 * height means the same thing in every column. Scaling each bar to its own total, which is
 * the easy mistake, would draw a quiet day and a busy one identically and make the chart a
 * decoration.
 */
export function ChecksChart({ days }: { days: ChartDay[] }) {
  const busiest = Math.max(...days.map((day) => day.allowed + day.refused), 0)
  const total = days.reduce((sum, day) => sum + day.allowed + day.refused, 0)
  const refused = days.reduce((sum, day) => sum + day.refused, 0)

  // A percentage of nothing is not zero, it is undefined — with no checks at all every
  // column is empty rather than full.
  const height = (value: number) => (busiest === 0 ? 0 : (value / busiest) * 100)

  return (
    <>
      <div className={styles.chartHead}>
        <span className={styles.chartLabel}>Checks per day · last 14 days</span>
        <span className={styles.legend}>
          <span className={styles.legendItem}>
            <span className={styles.swatchRefused} aria-hidden="true" />
            Refused
          </span>
          <span className={styles.legendItem}>
            <span className={styles.swatchAllowed} aria-hidden="true" />
            Allowed
          </span>
        </span>
      </div>

      <div
        className={styles.chart}
        role="img"
        aria-label={
          total === 0
            ? 'No checks in the last 14 days.'
            : `${total} checks over 14 days, ${refused} of them refused. Busiest day: ${busiest}.`
        }
      >
        {days.map((day) => (
          <div className={styles.column} key={day.date}>
            {/* Each column carries its own numbers, so the chart answers a hover as well
                as a glance. */}
            <div
              className={styles.bar}
              title={`${day.date}: ${day.allowed + day.refused} checks, ${day.refused} refused`}
            >
              <div
                className={styles.barRefused}
                style={{ height: `${height(day.refused)}%` }}
              />
              <div
                className={styles.barAllowed}
                style={{ height: `${height(day.allowed)}%` }}
              />
            </div>
          </div>
        ))}
      </div>

      <div className={styles.axis} aria-hidden="true">
        {days.map((day) => (
          <span key={day.date}>{day.date.slice(-2)}</span>
        ))}
      </div>
    </>
  )
}
