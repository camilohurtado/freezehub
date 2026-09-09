import { useSearchParams } from 'react-router'
import { formatShort } from '../../utils/datetime'
import { useDeploymentChecks } from './useDeploymentChecks'
import { useDeploymentCheckSummary } from '../dashboard/useDashboard'
import { checkTotals } from '../dashboard/dashboardSentences'
import { ChecksChart } from './ChecksChart'
import { DeployCheck } from './DeployCheck'
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

  // The chart and the figures describe the whole fortnight, so they do not move when the
  // list below them is filtered — the filter is about which rows to read, not about what
  // happened.
  const summary = useDeploymentCheckSummary()
  const fortnight = checkTotals(summary.data?.daily ?? [])

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

      {/*
        * The question first, then its history. Somebody arriving here wants to know
        * whether they are blocked now more often than they want a fortnight's chart, and
        * the answer is one they have to ask for rather than one the page can already know
        * (`FZ-120`).
        */}
      <DeployCheck />

      {summary.data && <ChecksChart days={summary.data.daily} />}

      {summary.data && (
        <div className={styles.figures}>
          <Figure value={fortnight.checks} label="checks in 14 days" />
          <Figure
            value={fortnight.refused}
            label={
              fortnight.refusedShare === null
                ? 'refused'
                : `refused (${fortnight.refusedShare}%)`
            }
            alarming={fortnight.refused > 0}
          />
          <Figure value={summary.data.applications.seen} label="pipelines asking" />
          <Figure
            value={summary.data.unregistered}
            label="refused as unregistered"
            alarming={summary.data.unregistered > 0}
          />
        </div>
      )}

      <div className={styles.filters}>
        <span className={styles.filterLabel} id="show-label">
          Show
        </span>
        <div className="seg" role="radiogroup" aria-labelledby="show-label">
          {FILTERS.map((option) => (
            <label className="seg-opt" key={option.value || 'all'}>
              <input
                type="radio"
                name="decision"
                checked={(decision ?? '') === option.value}
                onChange={() => select(option.value)}
              />
              {option.label}
            </label>
          ))}
        </div>
        <span className={styles.filterHint}>Filter is in the URL — send this view to anyone.</span>
      </div>

      {isPending && (
        <p className={styles.state} role="status">
          Loading deployment checks…
        </p>
      )}

      {isError && (
        <div className={styles.state} role="alert">
          <p className={styles.errorText}>Could not load deployment checks. {error.message}</p>
          <button className="btn btn-secondary" type="button" onClick={() => refetch()}>
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
        <div className={styles.scroller}>
          <table className="table" aria-label="Deployment checks">
            <thead>
              <tr>
                <th scope="col">Decision</th>
                <th scope="col">Target</th>
                <th scope="col">Asked by</th>
                <th scope="col">Ref</th>
                <th scope="col">When</th>
                <th scope="col">Why</th>
              </tr>
            </thead>
            <tbody>
              {checks.map((check) => {
                const reason = refusalReason(check)
                const refused = check.decision === 'BLOCK'
                return (
                  <tr key={check.id}>
                    <td>
                      <span className={refused ? 'tag tag-accent-2' : 'tag tag-neutral'}>
                        {refused ? 'Refused' : 'Allowed'}
                      </span>
                    </td>
                    <td>
                      <span className="mono">
                        {check.application} → {check.environment}
                      </span>
                    </td>
                    {/* The person, when the pipeline told us. `checkedBy` is only the credential. */}
                    <td className={styles.meta}>{check.actor ?? check.checkedBy}</td>
                    <td>
                      {check.source ? (
                        <a
                          className="mono"
                          href={check.source}
                          rel="noreferrer noopener"
                          target="_blank"
                        >
                          {check.reference ? check.reference.slice(0, 12) : 'run'}
                        </a>
                      ) : (
                        <span className="mono">
                          {check.reference ? check.reference.slice(0, 12) : '—'}
                        </span>
                      )}
                    </td>
                    <td className={styles.when}>{formatShort(check.checkedAt)}</td>
                    <td className={reason ? styles.why : styles.whyQuiet}>
                      {reason ?? 'Out of scope'}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}

      {hasNextPage && (
        <button
          className={`btn btn-secondary `}
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

function Figure({
  value,
  label,
  alarming = false,
}: {
  value: number
  label: string
  alarming?: boolean
}) {
  return (
    <div>
      <div className={alarming ? styles.figureValueAlarming : styles.figureValue}>{value}</div>
      <div className={styles.figureLabel}>{label}</div>
    </div>
  )
}
