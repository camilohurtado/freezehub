import type {
  IntegrationType,
  NotificationDelivery,
  NotificationEvent,
  NotificationEventRecord,
} from '../../types/api'

/**
 * How an announcement and its deliveries read on the page (`1g`, FZ-115).
 *
 * Pure, because these are the sentences the screen asserts rather than displays — most of
 * all the failure banner, which claims somebody was not told.
 */

const EVENT_TITLE: Record<NotificationEvent, string> = {
  SCHEDULED: 'Restriction scheduled',
  STARTING_SOON: 'Starting soon',
  ACTIVATED: 'Restriction activated',
  COMPLETED: 'Restriction completed',
  CANCELLED: 'Restriction cancelled',
}

export function eventTitle(event: NotificationEvent): string {
  return EVENT_TITLE[event]
}

const CHANNEL_LABEL: Record<IntegrationType, string> = {
  SLACK: 'Slack',
  EMAIL: 'Email',
  WEBHOOK: 'Webhook',
}

/** A channel whose integration has since been deleted still had something sent to it. */
export function channelLabel(channel: IntegrationType | null): string {
  return channel === null ? 'Removed channel' : CHANNEL_LABEL[channel]
}

/**
 * What one delivery did, in the words `1g` uses.
 *
 * A failure names the reason where there is one — "was Slack told?" is answered by
 * delivered or failed, but "why not?" is the question immediately after it.
 */
export function deliveryOutcome(delivery: NotificationDelivery): string {
  if (delivery.status === 'SENT') return 'delivered'
  if (delivery.status === 'PENDING') {
    return delivery.attempts === 0 ? 'queued' : `retrying · attempt ${delivery.attempts}`
  }
  return delivery.lastError ? `failed · ${delivery.lastError}` : 'failed'
}

/**
 * The line above the list: what is wrong right now, or nothing at all.
 *
 * Only failures count. A pending delivery is the outbox working — announcing it as a
 * problem would cry wolf every time a restriction changes.
 */
export function failureSummary(events: NotificationEventRecord[]): string | null {
  const failed = events.flatMap((event) =>
    event.deliveries
      .filter((delivery) => delivery.status === 'FAILED')
      .map((delivery) => ({ event, delivery })),
  )
  if (failed.length === 0) return null

  return failed.length === 1 ? '1 delivery failed' : `${failed.length} deliveries failed`
}

/** The first failure, named — the one the banner describes in full. */
export function firstFailure(
  events: NotificationEventRecord[],
): { event: NotificationEventRecord; delivery: NotificationDelivery } | null {
  for (const event of events) {
    const delivery = event.deliveries.find((each) => each.status === 'FAILED')
    if (delivery) return { event, delivery }
  }
  return null
}
