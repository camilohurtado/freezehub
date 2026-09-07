import type { RestrictionSummary } from '../../types/api'
import { RestrictionCard } from './RestrictionCard'
import { useDashboardRestrictions } from './useDashboardRestrictions'
import styles from './DashboardPage.module.css'

/**
 * Answers 00-product.md's first question: "what deployment restrictions are active or
 * upcoming?" Active first, because that is what blocks a deploy right now.
 */
export function DashboardPage() {
  const { data, isPending, isError, error, refetch } = useDashboardRestrictions()

  if (isPending) {
    return (
      <main className={styles.page}>
        <h1 className={styles.title}>Dashboard</h1>
        <p className={styles.state} role="status">
          Loading restrictions…
        </p>
      </main>
    )
  }

  if (isError) {
    return (
      <main className={styles.page}>
        <h1 className={styles.title}>Dashboard</h1>
        <div className={styles.state} role="alert">
          <p className={styles.errorText}>Could not load restrictions. {error.message}</p>
          <button className={`btn btn-secondary ${styles.retry}`} type="button" onClick={() => refetch()}>
            Try again
          </button>
        </div>
      </main>
    )
  }

  const nothingAtAll =
    data.active.length === 0 && data.upcoming.length === 0 && data.recentlyCompleted.length === 0

  return (
    <main className={styles.page}>
      <h1 className={styles.title}>Dashboard</h1>

      {nothingAtAll ? (
        <p className={styles.state}>
          No deployment restrictions yet. Nothing is blocking deploys right now.
        </p>
      ) : (
        <>
          <Group
            heading="Active now"
            emptyText="Nothing is active. Deploys are not being blocked."
            restrictions={data.active}
          />
          <Group
            heading="Upcoming"
            emptyText="Nothing scheduled."
            restrictions={data.upcoming}
          />
          <Group
            heading="Recently completed"
            emptyText="Nothing has finished yet."
            restrictions={data.recentlyCompleted}
          />
        </>
      )}
    </main>
  )
}

function Group({
  heading,
  emptyText,
  restrictions,
}: {
  heading: string
  emptyText: string
  restrictions: RestrictionSummary[]
}) {
  // Slugged, because an id containing spaces silently breaks aria-labelledby: it is
  // whitespace-separated, so the section would lose its accessible name and stop being
  // exposed as a landmark at all.
  const headingId = `group-${heading.toLowerCase().replace(/\s+/g, '-')}`

  return (
    <section className={styles.group} aria-labelledby={headingId}>
      <h2 className={styles.groupHeading} id={headingId}>
        {heading}
        <span className={styles.count}>{restrictions.length}</span>
      </h2>

      {restrictions.length === 0 ? (
        <p className={styles.groupEmpty}>{emptyText}</p>
      ) : (
        <ul className={styles.list}>
          {restrictions.map((restriction) => (
            <RestrictionCard key={restriction.id} restriction={restriction} />
          ))}
        </ul>
      )}
    </section>
  )
}
