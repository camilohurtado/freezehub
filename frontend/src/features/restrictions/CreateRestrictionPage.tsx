import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { ApiError } from '../../api/client'
import { createRestriction } from '../../api/restrictions'
import { useAuth } from '../auth/authContext'
import { useApplications, useEnvironments, useTeams } from '../catalog/useCatalog'
import { localInputToUtcIso } from '../../utils/datetime'
import {
  validateRestrictionForm,
  type FieldErrors,
  type RestrictionFormValues,
} from './restrictionFormRules'
import type { CreateRestrictionBody, RestrictionLevel } from '../../types/api'
import styles from './CreateRestrictionPage.module.css'

const EMPTY: RestrictionFormValues = {
  name: '',
  description: '',
  reason: '',
  level: 'HARD_FREEZE',
  startsAtLocal: '',
  endsAtLocal: '',
  teamIds: [],
  applicationIds: [],
  environmentIds: [],
}

function selectedIds(select: HTMLSelectElement): number[] {
  return Array.from(select.selectedOptions, (option) => Number(option.value))
}

export function CreateRestrictionPage() {
  const [values, setValues] = useState<RestrictionFormValues>(EMPTY)
  const [errors, setErrors] = useState<FieldErrors>({})
  const [submitError, setSubmitError] = useState<string | null>(null)

  const { token } = useAuth()
  const queryClient = useQueryClient()
  const navigate = useNavigate()

  const teams = useTeams()
  const applications = useApplications()
  const environments = useEnvironments()

  const create = useMutation({
    mutationFn: (body: CreateRestrictionBody) => createRestriction(token, body),
    onSuccess: () => {
      // Invalidated rather than patched: the dashboard and list must reflect the new
      // restriction, and the server decided its id, status and timestamps.
      void queryClient.invalidateQueries({ queryKey: ['restrictions'] })
      navigate('/restrictions')
    },
  })

  function update<K extends keyof RestrictionFormValues>(key: K, value: RestrictionFormValues[K]) {
    setValues((current) => ({ ...current, [key]: value }))
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setSubmitError(null)

    const found = validateRestrictionForm(values)
    setErrors(found)
    if (Object.keys(found).length > 0) return

    try {
      await create.mutateAsync({
        name: values.name.trim(),
        description: values.description.trim() ? values.description.trim() : null,
        reason: values.reason.trim(),
        level: values.level as RestrictionLevel,
        // The conversion this whole story hinges on: the input is zoneless local time.
        startsAt: localInputToUtcIso(values.startsAtLocal),
        endsAt: localInputToUtcIso(values.endsAtLocal),
        scope: {
          teamIds: values.teamIds,
          applicationIds: values.applicationIds,
          environmentIds: values.environmentIds,
        },
      })
    } catch (caught) {
      // The backend is authoritative; whatever it says is shown rather than swallowed.
      setSubmitError(
        caught instanceof ApiError
          ? caught.message
          : 'Could not create the restriction. Please try again.',
      )
    }
  }

  const catalogEmpty =
    (teams.data?.length ?? 0) === 0 &&
    (applications.data?.length ?? 0) === 0 &&
    (environments.data?.length ?? 0) === 0
  const catalogLoaded = !teams.isPending && !applications.isPending && !environments.isPending

  return (
    <main className={styles.page}>
      <h1 className={styles.title}>New restriction</h1>

      {catalogLoaded && catalogEmpty && (
        <p className={styles.notice} role="status">
          There is nothing to scope a restriction to yet. Add a team, application or
          environment in the <Link to="/catalog">catalog</Link> first.
        </p>
      )}

      <form className={styles.form} onSubmit={handleSubmit} noValidate>
        <label className={styles.label} htmlFor="name">
          Name
        </label>
        <input
          id="name"
          className={styles.input}
          value={values.name}
          onChange={(event) => update('name', event.target.value)}
          placeholder="Black Friday Freeze"
        />
        {errors.name && <p className={styles.fieldError}>{errors.name}</p>}

        <label className={styles.label} htmlFor="reason">
          Reason
        </label>
        <input
          id="reason"
          className={styles.input}
          value={values.reason}
          onChange={(event) => update('reason', event.target.value)}
          placeholder="Revenue-critical trading period"
        />
        {errors.reason && <p className={styles.fieldError}>{errors.reason}</p>}

        <label className={styles.label} htmlFor="description">
          Description <span className={styles.optional}>optional</span>
        </label>
        <textarea
          id="description"
          className={styles.textarea}
          rows={3}
          value={values.description}
          onChange={(event) => update('description', event.target.value)}
        />

        <label className={styles.label} htmlFor="level">
          Level
        </label>
        <select
          id="level"
          className={styles.input}
          value={values.level}
          onChange={(event) => update('level', event.target.value)}
        >
          <option value="HARD_FREEZE">Hard freeze — blocks matching deployments</option>
          <option value="ADVISORY">Advisory — warns but allows deployments</option>
        </select>

        <div className={styles.window}>
          <div>
            <label className={styles.label} htmlFor="startsAt">
              Starts
            </label>
            <input
              id="startsAt"
              className={styles.input}
              type="datetime-local"
              value={values.startsAtLocal}
              onChange={(event) => update('startsAtLocal', event.target.value)}
            />
            {errors.startsAtLocal && <p className={styles.fieldError}>{errors.startsAtLocal}</p>}
          </div>
          <div>
            <label className={styles.label} htmlFor="endsAt">
              Ends
            </label>
            <input
              id="endsAt"
              className={styles.input}
              type="datetime-local"
              value={values.endsAtLocal}
              onChange={(event) => update('endsAtLocal', event.target.value)}
            />
            {errors.endsAtLocal && <p className={styles.fieldError}>{errors.endsAtLocal}</p>}
          </div>
        </div>
        <p className={styles.hint}>
          Times are in your local zone and stored in UTC.
        </p>

        <fieldset className={styles.scope}>
          <legend className={styles.legend}>Scope</legend>
          <p className={styles.hint}>
            A deployment matches when it satisfies <strong>every</strong> dimension you set.
            Leave a dimension empty to mean “any”.
          </p>

          <div className={styles.scopeGrid}>
            <div>
              <label className={styles.label} htmlFor="teams">
                Teams
              </label>
              <select
                id="teams"
                className={styles.multi}
                multiple
                size={5}
                value={values.teamIds.map(String)}
                onChange={(event) => update('teamIds', selectedIds(event.target))}
              >
                {(teams.data ?? []).map((team) => (
                  <option key={team.id} value={team.id}>
                    {team.name}
                  </option>
                ))}
              </select>
            </div>

            <div>
              <label className={styles.label} htmlFor="applications">
                Applications
              </label>
              <select
                id="applications"
                className={styles.multi}
                multiple
                size={5}
                value={values.applicationIds.map(String)}
                onChange={(event) => update('applicationIds', selectedIds(event.target))}
              >
                {(applications.data ?? []).map((application) => (
                  <option key={application.id} value={application.id}>
                    {application.name}
                  </option>
                ))}
              </select>
            </div>

            <div>
              <label className={styles.label} htmlFor="environments">
                Environments
              </label>
              <select
                id="environments"
                className={styles.multi}
                multiple
                size={5}
                value={values.environmentIds.map(String)}
                onChange={(event) => update('environmentIds', selectedIds(event.target))}
              >
                {(environments.data ?? []).map((environment) => (
                  <option key={environment.id} value={environment.id}>
                    {environment.name}
                  </option>
                ))}
              </select>
            </div>
          </div>
          {errors.scope && <p className={styles.fieldError}>{errors.scope}</p>}
        </fieldset>

        {submitError && (
          <p className={styles.submitError} role="alert">
            {submitError}
          </p>
        )}

        <div className={styles.actions}>
          <button className={styles.primary} type="submit" disabled={create.isPending}>
            {create.isPending ? 'Creating…' : 'Create restriction'}
          </button>
          <Link className={styles.cancel} to="/restrictions">
            Cancel
          </Link>
        </div>
      </form>
    </main>
  )
}
