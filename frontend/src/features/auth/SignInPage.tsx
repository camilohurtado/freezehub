import { useState, type FormEvent } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { apiRequest, ApiError } from '../../api/client'
import type { DevSignInResponse } from '../../types/api'
import { useAuth } from './authContext'
import { Blueprint } from '../../components/Blueprint'
import styles from './SignInPage.module.css'

/**
 * Development sign-in, backed by the local-profile-only token endpoint (FZ-035).
 *
 * This is a temporary affordance: at FZ-063 it is replaced by a redirect to the Cognito
 * Hosted UI, and the endpoint behind it stops existing.
 */
export function SignInPage() {
  const [email, setEmail] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const { signIn } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()

  const returnTo = (location.state as { from?: string } | null)?.from ?? '/dashboard'

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setSubmitting(true)

    try {
      const response = await apiRequest<DevSignInResponse>('/api/dev/token', {
        method: 'POST',
        body: { email },
      })
      signIn(response.token)
      navigate(returnTo, { replace: true })
    } catch (caught) {
      if (caught instanceof ApiError && caught.isNotFound) {
        setError('No user with that email. Create one before signing in.')
      } else if (caught instanceof ApiError) {
        setError(caught.message)
      } else {
        setError('Could not reach the API. Is the backend running?')
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main className={styles.page}>
      <Blueprint as="form" className={styles.card} onSubmit={handleSubmit}>
        <h1 className={styles.title}>FreezeHub</h1>
        <p className={styles.hint}>
          Development sign-in. Replaced by Cognito when the user pool exists.
        </p>

        <label className={styles.label} htmlFor="email">
          Email
        </label>
        <input
          id="email"
          className={styles.input}
          type="email"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          placeholder="dev@acme.test"
          required
        />

        {error && (
          <p className={styles.error} role="alert">
            {error}
          </p>
        )}

        <button className={`btn btn-primary btn-block ${styles.button}`} type="submit" disabled={submitting || !email}>
          {submitting ? 'Signing in…' : 'Sign in'}
        </button>
      </Blueprint>
    </main>
  )
}
