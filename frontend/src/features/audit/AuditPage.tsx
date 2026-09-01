import { useSearchParams } from 'react-router'
import { ApiError } from '../../api/client'
import { formatInstant } from '../../utils/datetime'
import { describeAction, describeDetails } from './auditWording'
import { useAuditTrail } from './useAuditTrail'
import type { AuditResourceType } from '../../types/api'
import styles from './AuditPage.module.css'

const FILTERS: Array<{ value: '' | AuditResourceType; label: string }> = [
  { value: '', label: 'Everything' },
  { value: 'RESTRICTION', label: 'Restrictions' },
  { value: 'APPLICATION', label: 'Applications' },
  { value: 'TEAM', label: 'Teams' },
  { value: 'ENVIRONMENT', label: 'Environments' },
  { value: 'API_KEY', label: 'API keys' },
  { value: 'ORGANIZATION', label: 'Settings' },
  { value: 'POLICY', label: 'Refusals' },
]

const KNOWN = new Set(FILTERS.map((option) => option.value))

/**
 * Who changed what, and when (`FZ-039`, the screen for `FZ-060`).
 *
 * The companion to the deployment console: that one shows a pipeline being refused, this
 * one shows why the rules it was judged against look the way they do. Renaming an
 * application turns every pipeline using the old name into a refusal (`D-14`), and this
 * is where that connection is visible.
 *
 * Administrator only, matching the API — the trail names who did what.
 */
export function AuditPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const requested = searchParams.get('resourceType') ?? ''
  const selected = (KNOWN.has(requested as AuditResourceType | '') ? requested : '') as
    | ''
    | AuditResourceType

  const { data, isPending, isError, error, refetch, fetchNextPage, hasNextPage, isFetchingNextPage } =
    useAuditTrail(selected || undefined)

  const events = data?.pages.flat() ?? []
  const forbidden = error instanceof ApiError && error.status === 403

  function select(value: string) {
    const params = new URLSearchParams()
    if (value) params.set('resourceType', value)
    setSearchParams(params, { replace: true })
  }

  return (
    <main className={styles.page}>
      <h1 className={styles.title}>Audit</h1>
      <p className={styles.description}>
        Every change to the rules deployments are judged against — who made it, when, and
        what it was before. Entries are never edited or removed.
      </p>

      <fieldset className={styles.filters}>
        <legend className={styles.legend}>Show</legend>
        {FILTERS.map((option) => (
          <button
            key={option.value || 'all'}
            type="button"
            aria-pressed={selected === option.value}
            className={selected === option.value ? styles.filterActive : styles.filter}
            onClick={() => select(option.value)}
          >
            {option.label}
          </button>
        ))}
      </fieldset>

      {forbidden && (
        <p className={styles.state} role="status">
          Only an administrator can read the audit trail.
        </p>
      )}

      {isPending && (
        <p className={styles.state} role="status">
          Loading the audit trail…
        </p>
      )}

      {isError && !forbidden && (
        <div className={styles.state} role="alert">
          <p className={styles.errorText}>Could not load the audit trail. {error.message}</p>
          <button className={styles.retry} type="button" onClick={() => refetch()}>
            Try again
          </button>
        </div>
      )}

      {!isPending && !isError && events.length === 0 && (
        <p className={styles.state}>Nothing recorded yet for this filter.</p>
      )}

      {events.length > 0 && (
        <ul className={styles.list} aria-label="Audit trail">
          {events.map((event) => {
            const details = describeDetails(event.details)
            return (
              <li key={event.id} className={styles.row}>
                <span className={styles.action}>{describeAction(event)}</span>
                {/* Nobody did the system's work; saying a name here would be a fiction. */}
                {event.actorType === 'SYSTEM' ? (
                  <span className={styles.system}>automatic</span>
                ) : (
                  <span className={styles.actor}>{event.actorLabel}</span>
                )}
                <span className={styles.when}>{formatInstant(event.occurredAt)}</span>

                {details.length > 0 && (
                  <ul className={styles.details}>
                    {details.map((line) => (
                      <li key={line} className={styles.detail}>
                        {line}
                      </li>
                    ))}
                  </ul>
                )}
              </li>
            )
          })}
        </ul>
      )}

      {hasNextPage && (
        <button
          className={styles.more}
          type="button"
          disabled={isFetchingNextPage}
          onClick={() => fetchNextPage()}
        >
          {isFetchingNextPage ? 'Loading…' : 'Load older'}
        </button>
      )}
    </main>
  )
}
