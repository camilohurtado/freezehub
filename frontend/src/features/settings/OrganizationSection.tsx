import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { ApiError } from '../../api/client'
import { getOrganization, updateOrganizationSettings } from '../../api/organization'
import { useAuth } from '../auth/authContext'
import type { Organization } from '../../types/api'
import styles from './SettingsPage.module.css'

/** Minutes, as the API takes them, offered as the units people actually think in. */
const LEAD_TIME_CHOICES = [
  { minutes: 60, label: '1 hour' },
  { minutes: 240, label: '4 hours' },
  { minutes: 720, label: '12 hours' },
  { minutes: 1440, label: '1 day' },
  { minutes: 4320, label: '3 days' },
  { minutes: 10080, label: '1 week' },
]

function describe(minutes: number): string {
  return LEAD_TIME_CHOICES.find((choice) => choice.minutes === minutes)?.label ?? `${minutes} minutes`
}

/**
 * The organization's own settings (`FZ-047`), which until now existed only in the API.
 *
 * A fixed set of choices rather than a free number field: the backend accepts anything
 * from 1 minute to 30 days, but "how much warning does the team want" is a question with
 * about six sensible answers, and a minutes box invites someone to type 90000 and get a
 * validation error for their trouble. A value set outside the UI is still shown.
 */
export function OrganizationSection() {
  const { token } = useAuth()
  const queryClient = useQueryClient()

  // Null means "not touched yet", so the value shown falls through to the server's.
  // Derived during render rather than synchronised in an effect: an effect here would
  // render once with the wrong value and again with the right one, for no benefit.
  const [chosen, setChosen] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  const organization = useQuery<Organization>({
    queryKey: ['organization'],
    queryFn: ({ signal }) => getOrganization(token, signal),
  })

  const save = useMutation({
    mutationFn: (minutes: number) => updateOrganizationSettings(token, minutes),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['organization'] })
    },
  })

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (leadTime === null) return
    setError(null)
    setSaved(false)
    try {
      await save.mutateAsync(leadTime)
      setSaved(true)
    } catch (caught) {
      setError(
        caught instanceof ApiError ? caught.message : 'Something went wrong. Please try again.',
      )
    }
  }

  const forbidden = save.error instanceof ApiError && save.error.status === 403
  const current = organization.data?.startingSoonLeadTimeMinutes
  const leadTime = chosen ?? current ?? null

  // The server's value may not be one of the offered choices, and must not be dropped.
  const choices = current !== undefined && !LEAD_TIME_CHOICES.some((c) => c.minutes === current)
    ? [...LEAD_TIME_CHOICES, { minutes: current, label: describe(current) }].sort((a, b) => a.minutes - b.minutes)
    : LEAD_TIME_CHOICES

  return (
    <section className={styles.section} aria-labelledby="organization-heading">
      <h2 className={styles.sectionHeading} id="organization-heading">
        Advance warning
      </h2>
      <p className={styles.sectionDescription}>
        How far ahead of a freeze everyone is told it is coming, so there is still time to
        merge or deploy before it begins.
      </p>

      {organization.isPending && (
        <p className={styles.state} role="status">
          Loading settings…
        </p>
      )}

      {organization.isError && (
        <p className={styles.actionError} role="alert">
          Could not load settings. {(organization.error as Error).message}
        </p>
      )}

      {error && !forbidden && (
        <p className={styles.actionError} role="alert">
          {error}
        </p>
      )}

      {forbidden && (
        <p className={styles.state} role="status">
          Only an administrator can change how much warning is given.
        </p>
      )}

      {saved && !error && (
        <p className={styles.state} role="status">
          Saved. Freezes will be announced {describe(leadTime ?? 0)} before they start.
        </p>
      )}

      {organization.data && leadTime !== null && (
        <form className={styles.createForm} onSubmit={submit}>
          <label className={styles.label} htmlFor="lead-time">
            Announce a freeze this far ahead
          </label>
          <select
            id="lead-time"
            className={styles.input}
            value={leadTime}
            onChange={(event) => {
              setSaved(false)
              setChosen(Number(event.target.value))
            }}
          >
            {choices.map((choice) => (
              <option key={choice.minutes} value={choice.minutes}>
                {choice.label}
              </option>
            ))}
          </select>
          <button
            className={styles.primary}
            type="submit"
            disabled={save.isPending || leadTime === current}
          >
            {save.isPending ? 'Saving…' : 'Save'}
          </button>
        </form>
      )}
    </section>
  )
}
