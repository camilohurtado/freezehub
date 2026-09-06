import { apiRequest } from './client'
import type { Subscription } from '../types/api'

/**
 * The plan, its limits, and how much of each is used (`FZ-085`).
 *
 * Readable by any member, unlike the rest of `/api/billing` — the trial banner has to
 * reach everyone, not only whoever pays.
 */
export function getSubscription(
  token: string | null,
  signal?: AbortSignal,
): Promise<Subscription> {
  return apiRequest<Subscription>('/api/billing/subscription', { token, signal })
}

/** Administrator only. Returns a Stripe-hosted URL to send the browser to. */
export function createCheckoutSession(
  token: string | null,
  plan: string,
  period: 'MONTHLY' | 'ANNUAL',
): Promise<{ url: string }> {
  return apiRequest<{ url: string }>('/api/billing/checkout-session', {
    method: 'POST',
    body: { plan, period },
    token,
  })
}

/** Administrator only. Stripe's own portal: cards, invoices, cancellation. */
export function createPortalSession(token: string | null): Promise<{ url: string }> {
  return apiRequest<{ url: string }>('/api/billing/portal-session', {
    method: 'POST',
    token,
  })
}
