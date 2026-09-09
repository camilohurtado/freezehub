import { Link } from 'react-router'
import type { RestrictionSummary } from '../../types/api'
import { DeployCheck } from './DeployCheck'
import { RestrictionCard } from './RestrictionCard'
import { useDashboardRestrictions, useDeploymentCheckSummary } from './useDashboard'
import { useEnvironments } from '../catalog/useCatalog'
import {
  checkTotals,
  countsSentence,
  coverageCaption,
  statusLine,
  thenWhat,
} from './dashboardSentences'
import { formatShort, localZoneName } from '../../utils/datetime'
import styles from './DashboardPage.module.css'

/**
 * Answers 00-product.md's first question — "what deployment restrictions are active or
 * upcoming?" — and, since `FZ-106`, its follow-up: what happens next.
 *
 * Composed from the operator's chosen direction: `1a`'s grouped listings and completed
 * table, `1b`'s four metrics below them, and `1c`'s "Then what".
 */
export function DashboardPage() {
  const restrictions = useDashboardRestrictions()
  const summary = useDeploymentCheckSummary()
  const environments = useEnvironments()

  if (restrictions.isPending) {
    return (
      <main className={styles.page}>
        <h1 className={styles.title}>Dashboard</h1>
        <p className={styles.state} role="status">
          Loading restrictions…
        </p>
      </main>
    )
  }

  if (restrictions.isError) {
    return (
      <main className={styles.page}>
        <h1 className={styles.title}>Dashboard</h1>
        <div className={styles.state} role="alert">
          <p className={styles.errorText}>
            Could not load restrictions. {restrictions.error.message}
          </p>
          <button
            className={`btn btn-secondary ${styles.retry}`}
            type="button"
            onClick={() => restrictions.refetch()}
          >
            Try again
          </button>
        </div>
      </main>
    )
  }

  const data = restrictions.data
  const environmentNames = new Map((environments.data ?? []).map((e) => [e.id, e.name]))
  const status = statusLine(data.blocking, environmentNames)
  const transitions = thenWhat(data.active, data.upcoming)
  const totals = checkTotals(summary.data?.daily ?? [])
  const refusalsById = new Map(
    (summary.data?.refusalsByRestriction ?? []).map((row) => [row.restrictionId, row.refused]),
  )

  return (
    <main className={styles.page}>
      <div className={styles.masthead}>
        <div>
          <h1 className={styles.title}>Dashboard</h1>
          <p className={styles.counts}>
            {countsSentence(data.active.length, data.upcoming.length)} All times{' '}
            {localZoneName()}.
          </p>
        </div>
        <Link className="btn btn-primary" to="/restrictions/new">
          New restriction
        </Link>
      </div>

      {/*
        * The one status line on the screen, in the system's thick-thin rule pair. Magenta
        * only when something really is in force — the colour is the claim.
        */}
      <div className={styles.rail}>
        <span className={styles.railLabel}>{status.blocking ? 'In force now' : 'Right now'}</span>
        <span className={status.blocking ? styles.railBlocking : styles.railClear}>
          {status.sentence}
        </span>
        {status.until && (
          <span className={styles.railUntil}>until {formatShort(status.until)}</span>
        )}
      </div>

      {/*
        * The present beside the future. Upcoming used to sit under this as its own group
        * of cards, which meant every scheduled freeze appeared three times on one page —
        * once as a card, then as its start and its completion below (FZ-113).
        */}
      <div className={styles.columns}>
        <Group
          heading="Active now"
          emptyText="Nothing is active. Deploys are not being blocked."
          restrictions={data.active}
        />

        <section className={styles.group} aria-labelledby="then-what-heading">
          <h2 className={styles.groupHeading} id="then-what-heading">
            Then what
          </h2>
          {transitions.length === 0 ? (
            <p className={styles.groupEmpty}>
              Nothing scheduled. Deploys are clear from here.
            </p>
          ) : (
            <ol className={styles.transitions}>
              {transitions.map((transition, index) => (
                <li className={styles.transition} key={`${transition.at}-${index}`}>
                  <span className={styles.transitionAt}>
                    {transition.clear ? '' : formatShort(transition.at)}
                  </span>
                  <span className={transition.clear ? styles.transitionClear : undefined}>
                    {/*
                      * The name links. `1c` draws this line as plain text, but the
                      * Upcoming cards it replaced were the only route from here to a
                      * scheduled restriction — plain text would be a dead end.
                      */}
                    {transition.restrictionId !== null && transition.name !== null ? (
                      <>
                        <Link to={`/restrictions/${transition.restrictionId}`}>
                          {transition.name}
                        </Link>{' '}
                        {transition.detail}
                      </>
                    ) : (
                      transition.detail
                    )}
                  </span>
                </li>
              ))}
            </ol>
          )}
        </section>
      </div>

      {/*
        * `1c` puts the check under the status block, above everything counted or listed:
        * "am I blocked right now?" is the question somebody arrives with, and the answer
        * to it is one they have to ask for rather than one the page can already know.
        */}
      <DeployCheck />

      <section className={styles.group} aria-labelledby="metrics-heading">
        <h2 className={styles.groupHeading} id="metrics-heading">
          At a glance
        </h2>
        {/*
          * Three tiles, not four. `Active` restated the rail directly above it and
          * `Scheduled` restated the forward list beside it, so both went; nothing was
          * invented to fill the row (FZ-113).
          */}
        <div className={styles.metrics}>
          <Metric
            label="Checks · 14 days"
            value={summary.data ? totals.checks : undefined}
            caption={summary.data ? `${summary.data.today.total} today` : 'loading…'}
          />
          <Metric
            label="Refused"
            value={summary.data ? totals.refused : undefined}
            caption={
              !summary.data
                ? 'loading…'
                : totals.refusedShare === null
                  ? 'no checks yet'
                  : `${totals.refusedShare}% of checks`
            }
            alarming={totals.refused > 0}
          />
          <Metric
            label="Pipelines integrated"
            value={summary.data?.applications.seen}
            caption={
              summary.data
                ? coverageCaption(summary.data.applications.seen, summary.data.applications.total)
                : 'loading…'
            }
          />
        </div>
        {summary.isError && (
          <p className={styles.groupEmpty}>
            Check figures are unavailable. {summary.error.message}
          </p>
        )}
      </section>

      <section className={styles.group} aria-labelledby="completed-heading">
        <h2 className={styles.groupHeading} id="completed-heading">
          Recently completed
        </h2>
        <p className={styles.note}>What each restriction refused while it was in force.</p>
        {data.recentlyCompleted.length === 0 ? (
          <p className={styles.groupEmpty}>Nothing has finished yet.</p>
        ) : (
          <div className={styles.scroller}>
            <table className="table">
              <thead>
                <tr>
                  <th scope="col">Name</th>
                  <th scope="col">Level</th>
                  <th scope="col">Ran</th>
                  <th scope="col">Checks refused</th>
                </tr>
              </thead>
              <tbody>
                {data.recentlyCompleted.map((restriction) => (
                  <tr key={restriction.id}>
                    <td>
                      <Link to={`/restrictions/${restriction.id}`}>{restriction.name}</Link>
                    </td>
                    <td>
                      <span
                        className={
                          restriction.level === 'HARD_FREEZE'
                            ? 'tag tag-accent-2'
                            : 'tag tag-neutral'
                        }
                      >
                        {restriction.level === 'HARD_FREEZE' ? 'Blocks deploys' : 'Advisory'}
                      </span>
                    </td>
                    <td className={styles.ran}>
                      {formatShort(restriction.startsAt)} → {formatShort(restriction.endsAt)}
                    </td>
                    {/*
                      * Blank rather than 0 while the figures load: a nought is a claim that
                      * this freeze refused nothing, and it would be wrong half the time.
                      */}
                    <td className={styles.refused}>
                      {summary.data ? (refusalsById.get(restriction.id) ?? 0) : '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </main>
  )
}

function Metric({
  label,
  value,
  caption,
  alarming = false,
}: {
  label: string
  value: number | undefined
  caption: string
  alarming?: boolean
}) {
  return (
    <div className={styles.metric}>
      <div className={styles.metricLabel}>{label}</div>
      <div className={alarming ? styles.metricValueAlarming : styles.metricValue}>
        {value ?? '—'}
      </div>
      <p className={styles.metricCaption}>{caption}</p>
    </div>
  )
}

function Group({
  heading,
  emptyText,
  restrictions,
}: {
  heading: string
  emptyText: string
  restrictions: RestrictionSummary[]
}) {
  // Slugged, because an id containing spaces silently breaks aria-labelledby: it is
  // whitespace-separated, so the section would lose its accessible name and stop being
  // exposed as a landmark at all.
  const headingId = `group-${heading.toLowerCase().replace(/\s+/g, '-')}`

  return (
    <section className={styles.group} aria-labelledby={headingId}>
      <h2 className={styles.groupHeading} id={headingId}>
        {heading}
        <span className={styles.count}>{restrictions.length}</span>
      </h2>

      {restrictions.length === 0 ? (
        <p className={styles.groupEmpty}>{emptyText}</p>
      ) : (
        <ul className={styles.list}>
          {restrictions.map((restriction) => (
            <RestrictionCard key={restriction.id} restriction={restriction} />
          ))}
        </ul>
      )}
    </section>
  )
}
