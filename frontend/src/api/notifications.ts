import { apiRequest } from './client'
import type { NotificationEvent, NotificationEventRecord } from '../types/api'

/**
 * What was announced and whether each channel accepted it (`FZ-115`).
 *
 * Administrators only — a delivery record names the destination and the error it
 * returned, which is configuration a member cannot see in the first place.
 */
export function listNotifications(
  token: string | null,
  signal?: AbortSignal,
): Promise<NotificationEventRecord[]> {
  return apiRequest<NotificationEventRecord[]>('/api/notifications', { token, signal })
}

/**
 * Sends one announcement again (`FZ-119`).
 *
 * Requeues only the deliveries that failed — the channels that already accepted it are
 * left alone, so a fix for the one person who was not told does not become a duplicate
 * for everyone who was.
 */
export function retryNotification(
  token: string | null,
  restrictionId: number,
  event: NotificationEvent,
): Promise<{ requeued: number }> {
  return apiRequest<{ requeued: number }>('/api/notifications/retry', {
    method: 'POST',
    body: { restrictionId, event },
    token,
  })
}
