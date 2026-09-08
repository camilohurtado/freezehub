import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router'
import { listNotifications, retryNotification } from '../../api/notifications'
import { useAuth } from '../auth/authContext'
import { ApiError } from '../../api/client'
import { formatShort } from '../../utils/datetime'
import {
  channelLabel,
  deliveryOutcome,
  eventTitle,
  failureSummary,
  firstFailure,
} from './notificationWording'
import type { NotificationEventRecord } from '../../types/api'
import styles from './NotificationsPage.module.css'

const FILTERS = [
  { value: '', label: 'All events' },
  { value: 'failed', label: 'Failed' },
  { value: 'starting-soon', label: 'Starting soon' },
] as const

/**
 * What FreezeHub announced, and whether each channel accepted it (`1g`, FZ-115).
 *
 * The question this answers is the one asked after a freeze goes wrong: *was Slack
 * actually told?* Grouped by event with the channels beside it, so a gap is visible
 * rather than derivable from three near-identical rows.
 */
export function NotificationsPage() {
  const { token } = useAuth()
  const [searchParams, setSearchParams] = useSearchParams()
  const filter = searchParams.get('show') ?? ''

  const queryClient = useQueryClient()

  const notifications = useQuery<NotificationEventRecord[]>({
    queryKey: ['notifications'],
    queryFn: ({ signal }) => listNotifications(token, signal),
  })

  /*
   * Nothing is sent here. The retry puts the failed deliveries back in front of the
   * dispatcher, which is why the button says what it does rather than "Resend" — the
   * announcement goes out on the outbox's next pass, not on this click.
   */
  const retry = useMutation({
    mutationFn: (event: NotificationEventRecord) =>
      retryNotification(token, event.restrictionId, event.event),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['notifications'] }),
  })

  function select(value: string) {
    const params = new URLSearchParams(searchParams)
    if (value) params.set('show', value)
    else params.delete('show')
    setSearchParams(params, { replace: true })
  }

  const all = notifications.data ?? []
  // The banner counts every failure, not the filtered ones: hiding the problem behind the
  // filter that hides it would be the one thing this screen must not do.
  const summary = failureSummary(all)
  const failure = firstFailure(all)

  const events = all.filter((event) => {
    if (filter === 'failed') return event.deliveries.some((d) => d.status === 'FAILED')
    if (filter === 'starting-soon') return event.event === 'STARTING_SOON'
    return true
  })

  if (notifications.isPending) {
    return (
      <main className={styles.page}>
        <h1 className={styles.title}>Notifications</h1>
        <p className={styles.state} role="status">
          Loading notifications…
        </p>
      </main>
    )
  }

  if (notifications.isError) {
    const forbidden =
      notifications.error instanceof ApiError && notifications.error.status === 403
    return (
      <main className={styles.page}>
        <h1 className={styles.title}>Notifications</h1>
        <div className={styles.state} role="alert">
          <p>
            {forbidden
              ? 'Only an administrator can see delivery history — it names the channels announcements were sent to.'
              : `Could not load notifications. ${notifications.error.message}`}
          </p>
        </div>
      </main>
    )
  }

  return (
    <main className={styles.page}>
      <div className={styles.masthead}>
        <h1 className={styles.title}>Notifications</h1>
        <Link to="/settings?section=integrations">Manage channels</Link>
      </div>
      <p className={styles.description}>
        Lifecycle events FreezeHub sent, and whether each channel accepted them. A failure
        here means somebody was not told.
      </p>

      {summary && failure && (
        <div className={styles.failureBanner} role="status">
          <span className={styles.failureCount}>{summary}</span>
          <span className={styles.failureDetail}>
            {channelLabel(failure.delivery.channel)}{' '}
            {failure.delivery.lastError ? (
              <>
                returned <span className="mono">{failure.delivery.lastError}</span>
              </>
            ) : (
              'did not accept'
            )}{' '}
            on “{eventTitle(failure.event.event).toLowerCase()}”.
          </span>
          <button
            className={`btn btn-primary ${styles.retry}`}
            type="button"
            disabled={retry.isPending}
            onClick={() => retry.mutate(failure.event)}
          >
            {retry.isPending ? 'Queueing…' : 'Try again'}
          </button>
        </div>
      )}

      {retry.isSuccess && (
        <p className={styles.retryResult} role="status">
          {retry.data.requeued === 0
            ? 'Nothing was left to retry — it may have gone through already.'
            : `${retry.data.requeued} ${retry.data.requeued === 1 ? 'delivery' : 'deliveries'} queued. The next dispatch will try again.`}
        </p>
      )}

      {retry.isError && (
        <p className={styles.retryResult} role="alert">
          Could not queue the retry. {retry.error.message}
        </p>
      )}

      <div className={styles.filters}>
        <div className="seg" role="radiogroup" aria-label="Show">
          {FILTERS.map((option) => (
            <label className="seg-opt" key={option.value || 'all'}>
              <input
                type="radio"
                name="show"
                checked={filter === option.value}
                onChange={() => select(option.value)}
              />
              {option.label}
            </label>
          ))}
        </div>
      </div>

      {events.length === 0 ? (
        <p className={styles.state}>
          {all.length === 0
            ? 'Nothing has been announced yet. Events appear here once a restriction is scheduled, starts or ends.'
            : 'No events match this filter.'}
        </p>
      ) : (
        <ul className={styles.list} aria-label="Notifications">
          {events.map((event) => (
            <li className={styles.event} key={`${event.restrictionId}-${event.event}`}>
              <span className={styles.when}>{formatShort(event.occurredAt)}</span>

              <div>
                <div className={styles.eventTitle}>{eventTitle(event.event)}</div>
                <div className={styles.eventDetail}>
                  <Link to={`/restrictions/${event.restrictionId}`}>{event.restrictionName}</Link>
                </div>
              </div>

              <div className={styles.deliveries}>
                {event.deliveries.map((delivery) => (
                  <span className={styles.delivery} key={delivery.integrationId}>
                    <span>{channelLabel(delivery.channel)}</span>
                    <span
                      className={
                        delivery.status === 'FAILED' ? styles.outcomeFailed : styles.outcome
                      }
                    >
                      {deliveryOutcome(delivery)}
                    </span>
                  </span>
                ))}
              </div>
            </li>
          ))}
        </ul>
      )}
    </main>
  )
}
