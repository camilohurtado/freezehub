import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { useApplications, useEnvironments, useTeams } from '../catalog/useCatalog'
import { localInputToUtcIso } from '../../utils/datetime'
import { ScopeChips } from './ScopeChips'
import { matchSummary, windowDuration } from './restrictionPreview'
import { useOverlappingRestrictions } from './useOverlappingRestrictions'
import {
  validateRestrictionForm,
  type FieldErrors,
  type RestrictionFormValues,
} from './restrictionFormRules'
import type { CreateRestrictionBody, RestrictionLevel } from '../../types/api'
import styles from './CreateRestrictionPage.module.css'

/**
 * The restriction form, shared by create (FZ-033) and edit (FZ-034), set as `1e` draws it
 * (FZ-108).
 *
 * Update is a full replacement server-side, so both carry exactly the same fields and the
 * same rules — one component rather than two that would drift.
 *
 * The right-hand column is the part `1e` adds: what this restriction will match, and what
 * happens when it is created, both answered while you type rather than after you submit.
 */
export function RestrictionForm({
  initialValues,
  submitLabel,
  submitting,
  submitError,
  onSubmit,
  cancelTo,
  excludeFromOverlap,
}: {
  initialValues: RestrictionFormValues
  submitLabel: string
  submitting: boolean
  submitError: string | null
  onSubmit: (body: CreateRestrictionBody) => Promise<void>
  cancelTo: string
  /** The restriction being edited, so it does not report overlapping itself. */
  excludeFromOverlap?: number
}) {
  const [values, setValues] = useState<RestrictionFormValues>(initialValues)
  const [errors, setErrors] = useState<FieldErrors>({})

  const teams = useTeams()
  const applications = useApplications()
  const environments = useEnvironments()
  const { overlaps, checked } = useOverlappingRestrictions(values, excludeFromOverlap)

  function update<K extends keyof RestrictionFormValues>(key: K, value: RestrictionFormValues[K]) {
    setValues((current) => ({ ...current, [key]: value }))
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()

    const found = validateRestrictionForm(values)
    setErrors(found)
    if (Object.keys(found).length > 0) return

    await onSubmit({
      name: values.name.trim(),
      description: values.description.trim() ? values.description.trim() : null,
      reason: values.reason.trim(),
      level: values.level as RestrictionLevel,
      // The inputs are zoneless local time; the API stores instants in UTC.
      startsAt: localInputToUtcIso(values.startsAtLocal),
      endsAt: localInputToUtcIso(values.endsAtLocal),
      scope: {
        teamIds: values.teamIds,
        applicationIds: values.applicationIds,
        environmentIds: values.environmentIds,
      },
    })
  }

  const catalogLoaded = !teams.isPending && !applications.isPending && !environments.isPending
  const catalogEmpty =
    (teams.data?.length ?? 0) === 0 &&
    (applications.data?.length ?? 0) === 0 &&
    (environments.data?.length ?? 0) === 0

  const summary = matchSummary(values, teams.data ?? [], applications.data ?? [], environments.data ?? [])
  const duration = windowDuration(values.startsAtLocal, values.endsAtLocal)
  const blocking = values.level === 'HARD_FREEZE'

  return (
    <>
      {catalogLoaded && catalogEmpty && (
        <p className={styles.notice} role="status">
          There is nothing to scope a restriction to yet. Add a team, application or
          environment in the <Link to="/catalog">catalog</Link> first.
        </p>
      )}

      <div className={styles.columns}>
        <form className={styles.form} onSubmit={handleSubmit} noValidate>
          <div className="field">
            <label htmlFor="name">Name</label>
            <input
              id="name"
              className="input"
              value={values.name}
              onChange={(event) => update('name', event.target.value)}
              placeholder="Black Friday Freeze"
            />
          </div>
          {errors.name && <p className={styles.fieldError}>{errors.name}</p>}

          <div className="field">
            <label htmlFor="reason">Reason</label>
            <input
              id="reason"
              className="input"
              value={values.reason}
              onChange={(event) => update('reason', event.target.value)}
              placeholder="Revenue-critical trading period"
            />
          </div>
          {errors.reason && <p className={styles.fieldError}>{errors.reason}</p>}

          <div className="field">
            <label htmlFor="description">
              Description <span className={styles.optional}>optional</span>
            </label>
            <textarea
              id="description"
              className="input"
              rows={3}
              value={values.description}
              onChange={(event) => update('description', event.target.value)}
            />
          </div>

          {/*
            * A segmented control rather than a dropdown: there are two values, this is the
            * most consequential field on the form, and a <select> hides one of them.
            */}
          <div className="field">
            <label id="level-label">Level</label>
            <div className="seg" role="radiogroup" aria-labelledby="level-label">
              <label className="seg-opt">
                <input
                  type="radio"
                  name="level"
                  value="HARD_FREEZE"
                  checked={blocking}
                  onChange={() => update('level', 'HARD_FREEZE')}
                />
                Hard freeze
              </label>
              <label className="seg-opt">
                <input
                  type="radio"
                  name="level"
                  value="ADVISORY"
                  checked={!blocking}
                  onChange={() => update('level', 'ADVISORY')}
                />
                Advisory
              </label>
            </div>
            <p className={styles.hint}>
              Hard freeze blocks matching deployments. Advisory warns but allows them.
            </p>
          </div>

          <div className={styles.window}>
            <div className="field">
              <label htmlFor="startsAt">Starts</label>
              <input
                id="startsAt"
                className="input"
                type="datetime-local"
                value={values.startsAtLocal}
                onChange={(event) => update('startsAtLocal', event.target.value)}
              />
              {errors.startsAtLocal && <p className={styles.fieldError}>{errors.startsAtLocal}</p>}
            </div>
            <div className="field">
              <label htmlFor="endsAt">Ends</label>
              <input
                id="endsAt"
                className="input"
                type="datetime-local"
                value={values.endsAtLocal}
                onChange={(event) => update('endsAtLocal', event.target.value)}
              />
              {errors.endsAtLocal && <p className={styles.fieldError}>{errors.endsAtLocal}</p>}
            </div>
          </div>
          <p className={styles.hint}>
            Times are in your local zone and stored in UTC.{duration ? ` ${duration}.` : ''}
          </p>

          <section className={styles.scope} aria-labelledby="scope-heading">
            <div className={styles.scopeLabel} id="scope-heading">
              Scope
            </div>
            <p className={styles.scopeHint}>
              A deployment matches when it satisfies <strong>every</strong> dimension you set.
              Leave a dimension empty to mean “any”.
            </p>

            <ScopeChips
              label="Teams"
              options={teams.data ?? []}
              selected={values.teamIds}
              onChange={(ids) => update('teamIds', ids)}
              loading={teams.isPending}
            />
            <ScopeChips
              label="Applications"
              options={applications.data ?? []}
              selected={values.applicationIds}
              onChange={(ids) => update('applicationIds', ids)}
              loading={applications.isPending}
            />
            <ScopeChips
              label="Environments"
              options={environments.data ?? []}
              selected={values.environmentIds}
              onChange={(ids) => update('environmentIds', ids)}
              loading={environments.isPending}
            />
            {errors.scope && <p className={styles.fieldError}>{errors.scope}</p>}
          </section>

          {submitError && (
            <p className={styles.submitError} role="alert">
              {submitError}
            </p>
          )}

          <div className={styles.actions}>
            <button className="btn btn-primary" type="submit" disabled={submitting}>
              {submitting ? 'Saving…' : submitLabel}
            </button>
            <Link className={`btn btn-ghost ${styles.cancel}`} to={cancelTo}>
              Cancel
            </Link>
          </div>
        </form>

        <aside className={styles.aside}>
          <div className={blocking ? styles.willMatchBlocking : styles.willMatch}>
            <div className={blocking ? styles.willMatchLabelBlocking : styles.scopeLabel}>
              This will match
            </div>
            <p className={styles.willMatchText}>
              {summary.empty ? (
                'Nothing yet — choose a team, application or environment below.'
              ) : (
                <>
                  Deployments
                  {summary.applications.length > 0 && (
                    <> of {joinStrong(summary.applications, 'or')}</>
                  )}
                  {summary.teams.length > 0 && <>, owned by {joinStrong(summary.teams, 'or')}</>}
                  {summary.environments.length > 0 && (
                    <>, going to {joinStrong(summary.environments, 'or')}</>
                  )}
                  {' — '}
                  <span className={styles.figures}>
                    {summary.matched} of {summary.total}
                  </span>{' '}
                  {summary.total === 1 ? 'application' : 'applications'}.
                  {' Everything else deploys as normal.'}
                </>
              )}
            </p>
            {!summary.empty && summary.matched === 0 && (
              <p className={styles.willMatchWarning}>
                No application satisfies every dimension at once, so this would match nothing
                today. That is allowed — team membership changes — but check it is what you meant.
              </p>
            )}
          </div>

          <div className={styles.onCreate}>
            <div className={styles.scopeLabel}>On create</div>
            <ul className={styles.onCreateList}>
              <li>
                Status becomes <span className="mono">SCHEDULED</span>
              </li>
              <li>{blocking ? 'Matching deployments are refused' : 'Matching deployments are reported, not refused'}</li>
              <li>Everyone on your notification channels is told</li>
            </ul>
          </div>

          {/*
            * Advisory, never blocking. Overlaps are deliberately allowed (FZ-020) — a hard
            * freeze and an advisory over one weekend is normal — so this reports and gets
            * out of the way.
            */}
          {checked && (
            <div className={overlaps.length === 0 ? styles.overlapClear : styles.overlapWarning}>
              {overlaps.length === 0 ? (
                <p className={styles.overlapText}>No overlap with an existing restriction.</p>
              ) : (
                <>
                  <p className={styles.overlapText}>
                    {overlaps.length === 1 ? 'One restriction overlaps' : `${overlaps.length} restrictions overlap`}{' '}
                    this window:
                  </p>
                  <ul className={styles.overlapList}>
                    {overlaps.map((overlap) => (
                      <li key={overlap.id}>
                        <Link to={`/restrictions/${overlap.id}`}>{overlap.name}</Link>{' '}
                        {overlap.sharesScope
                          ? '— and could match the same deployments.'
                          : '— but its scope cannot match the same deployment.'}
                      </li>
                    ))}
                  </ul>
                  <p className={styles.overlapText}>
                    Overlapping is allowed. This is a warning, not a refusal.
                  </p>
                </>
              )}
            </div>
          )}
        </aside>
      </div>
    </>
  )
}

/** "a, b or c", with each name in bold — the shape `1e` writes the sentence in. */
function joinStrong(names: string[], conjunction: string) {
  return names.map((name, index) => (
    <span key={name}>
      {index > 0 && (index === names.length - 1 ? ` ${conjunction} ` : ', ')}
      <strong>{name}</strong>
    </span>
  ))
}
