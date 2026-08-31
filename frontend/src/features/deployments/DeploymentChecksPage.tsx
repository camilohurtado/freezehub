import { useSearchParams } from 'react-router'
import { formatInstant } from '../../utils/datetime'
import { useDeploymentChecks } from './useDeploymentChecks'
import type { DeploymentCheck } from '../../types/api'
import styles from './DeploymentChecksPage.module.css'

const FILTERS = [
  { value: '', label: 'Everything' },
  { value: 'BLOCK', label: 'Refused' },
  { value: 'ALLOW', label: 'Allowed' },
] as const

/** Why it was refused, in the words someone reading this would use. */
function refusalReason(check: DeploymentCheck): string | null {
  if (check.decision !== 'BLOCK') return null

  if (check.blockedReason === 'UNREGISTERED') {
    return `${check.application} or ${check.environment} is not registered, so nothing could be evaluated`
  }

  const blocking = (check.matchedRestrictions ?? []).filter((r) => r.level === 'HARD_FREEZE')
  if (blocking.length === 0) return 'Blocked'
  return `Blocked by ${blocking.map((r) => r.name).join(', ')}`
}

/**
 * Every question the deployment gate was asked, and what it answered (`FZ-071`).
 *
 * **Checks, not deployments** — and the description says so on the page, not just in the
 * code. FreezeHub sees the question and nothing after it: a pipeline told *allowed* may
 * still fail on its own, and one told *refused* may deploy anyway, because enforcement
 * lives in the pipeline. Letting a reader believe otherwise would mislead them in exactly
 * the audit this screen exists to serve.
 *
 * Filter state lives in the URL, so "everything we refused" is a link someone can send.
 */
export function DeploymentChecksPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const decision = searchParams.get('decision') === 'BLOCK'
    ? 'BLOCK'
    : searchParams.get('decision') === 'ALLOW'
      ? 'ALLOW'
      : undefined

  const { data, isPending, isError, error, refetch, fetchNextPage, hasNextPage, isFetchingNextPage } =
    useDeploymentChecks({ decision })

  const checks = data?.pages.flat() ?? []

  function select(value: string) {
    const params = new URLSearchParams()
    if (value) params.set('decision', value)
    setSearchParams(params, { replace: true })
  }

  return (
    <main className={styles.page}>
      <h1 className={styles.title}>Deployment checks</h1>
      <p className={styles.description}>
        Every time a pipeline asked whether it could deploy, and what it was told. These
        are the questions, not the outcomes — FreezeHub is not told whether a deployment
        went ahead afterwards.
      </p>

      <fieldset className={styles.filters}>
        <legend className={styles.legend}>Show</legend>
        {FILTERS.map((option) => (
          <button
            key={option.value || 'all'}
            type="button"
            aria-pressed={(decision ?? '') === option.value}
            className={(decision ?? '') === option.value ? styles.filterActive : styles.filter}
            onClick={() => select(option.value)}
          >
            {option.label}
          </button>
        ))}
      </fieldset>

      {isPending && (
        <p className={styles.state} role="status">
          Loading deployment checks…
        </p>
      )}

      {isError && (
        <div className={styles.state} role="alert">
          <p className={styles.errorText}>Could not load deployment checks. {error.message}</p>
          <button className={styles.retry} type="button" onClick={() => refetch()}>
            Try again
          </button>
        </div>
      )}

      {!isPending && !isError && checks.length === 0 && (
        <p className={styles.state}>
          {decision === 'BLOCK'
            ? 'Nothing has been refused. No pipeline has been stopped by a freeze.'
            : 'No deployment checks yet. They appear here once a pipeline starts asking.'}
        </p>
      )}

      {checks.length > 0 && (
        <ul className={styles.list} aria-label="Deployment checks">
          {checks.map((check) => {
            const reason = refusalReason(check)
            return (
              <li key={check.id} className={styles.row}>
                <span className={check.decision === 'BLOCK' ? styles.decisionBlock : styles.decisionAllow}>
                  {check.decision === 'BLOCK' ? 'Refused' : 'Allowed'}
                </span>

                <span className={styles.target}>
                  {check.application} → {check.environment}
                </span>

                {/* The person, when the pipeline told us. `checkedBy` is only the credential. */}
                <span className={styles.meta}>{check.actor ?? check.checkedBy}</span>

                {check.reference && <span className={styles.reference}>{check.reference.slice(0, 12)}</span>}

                <span className={styles.meta}>{formatInstant(check.checkedAt)}</span>

                {check.source && (
                  <a className={styles.meta} href={check.source} rel="noreferrer noopener" target="_blank">
                    View run
                  </a>
                )}

                {reason && <span className={styles.blockedBy}>{reason}</span>}
              </li>
            )
          })}
        </ul>
      )}

      {hasNextPage && (
        <button
          className={styles.more}
          type="button"
          disabled={isFetchingNextPage}
          onClick={() => fetchNextPage()}
        >
          {isFetchingNextPage ? 'Loading…' : 'Load older'}
        </button>
      )}
    </main>
  )
}
