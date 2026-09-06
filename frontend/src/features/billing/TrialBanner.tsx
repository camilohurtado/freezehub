import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { getSubscription } from '../../api/billing'
import { useAuth } from '../auth/authContext'
import type { Subscription } from '../../types/api'
import styles from './TrialBanner.module.css'

/** Late enough to be urgent, early enough to act on. */
const WARN_WITHIN_DAYS = 5

/**
 * Says a trial is running out, or that the organization is suspended (`FZ-085`).
 *
 * Shown to **every member**, not only administrators. The person who notices a trial
 * ending is rarely the person who signs, and an engineer who finds out by having a
 * creation refused finds out too late to do anything about it.
 *
 * Silent the rest of the time. A banner that is always there is one nobody reads.
 */
export function TrialBanner() {
  const { token } = useAuth()

  const subscription = useQuery<Subscription>({
    queryKey: ['subscription'],
    queryFn: ({ signal }) => getSubscription(token, signal),
    // Failing to load this must never block the app: without a banner the product still
    // works, and with a broken one it looks like the product is broken.
    retry: false,
  })

  const plan = subscription.data
  if (!plan) return null

  if (plan.status === 'SUSPENDED' || plan.status === 'CANCELLED') {
    return (
      <div className={styles.urgent} role="status">
        <span>
          This organization is <strong>suspended</strong>. Your freezes are still enforced,
          but nothing can be changed.
        </span>
        <Link to="/settings">Billing</Link>
      </div>
    )
  }

  if (plan.status === 'PAST_DUE') {
    return (
      <div className={styles.urgent} role="status">
        <span>A payment did not go through. Nothing has changed yet.</span>
        <Link to="/settings">Update payment</Link>
      </div>
    )
  }

  const daysLeft = plan.trialDaysRemaining
  if (plan.status !== 'TRIALING' || daysLeft === null || daysLeft > WARN_WITHIN_DAYS) {
    return null
  }

  return (
    <div className={styles.warning} role="status">
      <span>
        {daysLeft === 0
          ? 'Your trial ends today.'
          : `Your trial ends in ${daysLeft} ${daysLeft === 1 ? 'day' : 'days'}.`}
      </span>
      <Link to="/settings">Choose a plan</Link>
    </div>
  )
}
