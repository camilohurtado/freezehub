import { apiRequest } from './client'
import type { NotificationEventRecord } from '../types/api'

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
