import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { useApplications, useEnvironments } from '../catalog/useCatalog'
import { useDeploymentPreview } from './useDeploymentChecks'
import { formatShort } from '../../utils/datetime'
import styles from './DeployCheck.module.css'

/**
 * "Can I deploy?" — `1c`'s check panel and `1k`'s field (`FZ-120`).
 *
 * On the checks console rather than the dashboard, by the operator's call: this is the
 * screen about the deployment gate, so the question and its history sit together. It also
 * puts the note that nothing is recorded exactly where it does the most work — beside the
 * list a person would otherwise expect their own check to appear in (`D-29`).
 *
 * The answer comes from the backend whole: decision, sentence and matched restrictions
 * are all rendered as sent. Nothing here decides anything, which is the point — the
 * product asking its own gate is only worth having if it cannot disagree with it.
 *
 * Free text rather than a pair of dropdowns, with the catalog offered as suggestions. A
 * dropdown could never produce the unregistered answer, and "my pipeline was blocked
 * because nobody registered `checkout-web`" is exactly the case somebody comes here to
 * reproduce.
 */
export function DeployCheck() {
  const [application, setApplication] = useState('')
  const [environment, setEnvironment] = useState('')
  const applications = useApplications()
  const environments = useEnvironments()
  const preview = useDeploymentPreview()

  const answer = preview.data
  const ready = application.trim() !== '' && environment.trim() !== ''

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    if (!ready) return
    preview.mutate({ application: application.trim(), environment: environment.trim() })
  }

  return (
    <section className={styles.section} aria-labelledby="deploy-check-heading">
      <h2 className={styles.heading} id="deploy-check-heading">
        Can I deploy?
      </h2>
      <p className={styles.note}>
        Asks the same gate a pipeline asks. Nothing is recorded, and nothing is enforced on
        the answer.
      </p>

      <form className={styles.form} onSubmit={onSubmit}>
        <div className={styles.field}>
          <label className={styles.label} htmlFor="check-application">
            Application
          </label>
          <input
            className="input"
            id="check-application"
            list="check-applications"
            value={application}
            placeholder="payments-api"
            onChange={(event) => setApplication(event.target.value)}
          />
          <datalist id="check-applications">
            {(applications.data ?? []).map((entry) => (
              <option key={entry.id} value={entry.name} />
            ))}
          </datalist>
        </div>

        <div className={styles.field}>
          <label className={styles.label} htmlFor="check-environment">
            Environment
          </label>
          <input
            className="input"
            id="check-environment"
            list="check-environments"
            value={environment}
            placeholder="production"
            onChange={(event) => setEnvironment(event.target.value)}
          />
          <datalist id="check-environments">
            {(environments.data ?? []).map((entry) => (
              <option key={entry.id} value={entry.name} />
            ))}
          </datalist>
        </div>

        <button className="btn btn-secondary" type="submit" disabled={!ready || preview.isPending}>
          {preview.isPending ? 'Evaluating…' : 'Evaluate'}
        </button>
      </form>

      <div aria-live="polite">
        {preview.isError && (
          <p className={styles.error} role="alert">
            Could not evaluate. {preview.error.message}
          </p>
        )}

        {answer && (
          <div className={styles.answer}>
            <div className={styles.verdictLine}>
              {/*
                * Magenta only on BLOCK. The system's one rule for colour: it claims a
                * deployment is being stopped, and an ALLOW stops nothing.
                */}
              <span
                className={answer.decision === 'BLOCK' ? styles.verdictBlock : styles.verdictAllow}
              >
                {answer.decision}
              </span>
              <span className={styles.evaluatedAt}>
                {answer.application} → {answer.environment} · evaluated{' '}
                {formatShort(answer.evaluatedAt)}
              </span>
            </div>

            <p className={styles.message}>{answer.message}</p>

            {answer.restrictions.length > 0 && (
              <ul className={styles.matched}>
                {answer.restrictions.map((restriction) => (
                  <li className={styles.match} key={restriction.id}>
                    <Link to={`/restrictions/${restriction.id}`}>{restriction.name}</Link>{' '}
                    <span
                      className={
                        restriction.level === 'HARD_FREEZE' ? 'tag tag-accent-2' : 'tag tag-neutral'
                      }
                    >
                      {restriction.level === 'HARD_FREEZE' ? 'Blocks deploys' : 'Advisory'}
                    </span>
                    <span className={styles.matchWindow}>until {formatShort(restriction.endsAt)}</span>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
      </div>

      <p className={styles.footnote}>
        Pipelines get the same answer from <span className="mono">POST /api/policy/evaluate</span>.
        Enforcement happens in the pipeline; FreezeHub is not told what happened next.
      </p>
    </section>
  )
}
