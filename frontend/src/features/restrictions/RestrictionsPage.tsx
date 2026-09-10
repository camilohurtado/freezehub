import { Link, useSearchParams } from 'react-router'
import { LevelBadge, StatusBadge } from '../../components/Badges'
import { formatInstant } from '../../utils/datetime'
import { ALL_STATUSES, parseStatuses, useRestrictionList } from './useRestrictionList'
import type { RestrictionStatus } from '../../types/api'
import styles from './RestrictionsPage.module.css'

/**
 * Browsing and filtering restrictions.
 *
 * Filter state lives in the URL, not component state, so a filtered view can be linked
 * to, bookmarked and survives a reload (05-frontend.md).
 */
export function RestrictionsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const selected = parseStatuses(searchParams.getAll('status'))
  const { data, isPending, isError, error, refetch } = useRestrictionList(selected)

  function toggleStatus(status: RestrictionStatus) {
    const next = selected.includes(status)
      ? selected.filter((value) => value !== status)
      : [...selected, status]

    const params = new URLSearchParams()
    next.forEach((value) => params.append('status', value))
    setSearchParams(params, { replace: true })
  }

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>Restrictions</h1>
        <Link className={styles.newButton} to="/restrictions/new">
          New restriction
        </Link>
      </div>

      {/*
        * A group rather than a fieldset (FZ-134). The box a fieldset draws is Industry's,
        * and the accessible name a legend gives is the only thing that was load-bearing —
        * `role="group"` with `aria-labelledby` keeps that and nothing else.
        */}
      <div className={styles.filters} role="group" aria-labelledby="filter-by-status">
        <span className={styles.filterLabel} id="filter-by-status">
          Filter by status
        </span>
        {ALL_STATUSES.map((status) => {
          const on = selected.includes(status)
          return (
            <label
              key={status}
              className={`tag ${on ? 'tag-accent' : 'tag-outline'} ${styles.chip}`}
            >
              <input type="checkbox" checked={on} onChange={() => toggleStatus(status)} />
              {status.toLowerCase()}
            </label>
          )
        })}
        {selected.length > 0 && (
          <button
            className={styles.clear}
            type="button"
            onClick={() => setSearchParams(new URLSearchParams(), { replace: true })}
          >
            Clear
          </button>
        )}
      </div>

      {isPending && (
        <p className={styles.state} role="status">
          Loading restrictions…
        </p>
      )}

      {isError && (
        <div className={styles.state} role="alert">
          <p className={styles.errorText}>Could not load restrictions. {error.message}</p>
          <button className={styles.retry} type="button" onClick={() => refetch()}>
            Try again
          </button>
        </div>
      )}

      {!isPending && !isError && data.length === 0 && (
        <p className={styles.state}>
          {selected.length > 0
            ? 'No restrictions match this filter.'
            : 'No restrictions yet.'}
        </p>
      )}

      {!isPending && !isError && data.length > 0 && (
        <div className={styles.tableWrap}>
          {/* The system's own table (FZ-134), as the checks console has drawn one since
              `FZ-071`. It was a local copy that boxed itself. */}
          <table className="table">
            <caption className={styles.caption}>
              {data.length} restriction{data.length === 1 ? '' : 's'}, soonest start first
            </caption>
            <thead className={styles.head}>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">Status</th>
                <th scope="col">Level</th>
                <th scope="col">Starts</th>
                <th scope="col">Ends</th>
              </tr>
            </thead>
            <tbody>
              {data.map((restriction) => (
                <tr key={restriction.id}>
                  <td>
                    <Link className={styles.name} to={`/restrictions/${restriction.id}`}>
                      {restriction.name}
                    </Link>
                    <span className={styles.reason}>{restriction.reason}</span>
                  </td>
                  <td>
                    <StatusBadge status={restriction.status} />
                  </td>
                  <td>
                    <LevelBadge level={restriction.level} />
                  </td>
                  <td className={styles.when}>{formatInstant(restriction.startsAt)}</td>
                  <td className={styles.when}>{formatInstant(restriction.endsAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </main>
  )
}
