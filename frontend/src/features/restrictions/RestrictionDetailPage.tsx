import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { ApiError } from '../../api/client'
import { cancelRestriction, getRestriction } from '../../api/restrictions'
import { LevelBadge, StatusBadge } from '../../components/Badges'
import { useAuth } from '../auth/authContext'
import { useApplications, useEnvironments, useTeams } from '../catalog/useCatalog'
import { formatInstant } from '../../utils/datetime'
import type { RestrictionDetail } from '../../types/api'
import styles from './RestrictionDetailPage.module.css'

/**
 * Scope comes back as ids; ids tell a reader nothing, so they are resolved to names against
 * the catalogs this page loads anyway (`FZ-104` established that a lookup is not a domain
 * rule, so doing it here puts the frontend in charge of nothing).
 *
 * Values are tags rather than a comma-joined sentence, because a scope is a set and a list
 * of names separated by commas reads as prose about one thing.
 */
function ScopeDimension({
  label,
  ids,
  namesById,
  loading,
  inForce,
}: {
  label: string
  ids: number[]
  namesById: Map<number, string>
  loading: boolean
  inForce: boolean
}) {
  return (
    <div className={styles.dimension}>
      <dt className={styles.label}>{label}</dt>
      <dd className={styles.dimensionValue}>
        {ids.length === 0 ? (
          <span className={styles.any}>Any</span>
        ) : (
          ids.map((id) => (
            <span key={id} className={`tag ${inForce ? 'tag-accent-2' : 'tag-neutral'}`}>
              {namesById.get(id) ?? (loading ? '…' : `#${id}`)}
            </span>
          ))
        )}
      </dd>
    </div>
  )
}

/** One fact of the definition grid. */
function Fact({ label, value }: { label: string; value: string }) {
  return (
    <div className={styles.fact}>
      <dt className={styles.label}>{label}</dt>
      <dd className={styles.factValue}>{value}</dd>
    </div>
  )
}

export function RestrictionDetailPage() {
  const { restrictionId } = useParams()
  const id = Number(restrictionId)
  const [actionError, setActionError] = useState<string | null>(null)

  const { token } = useAuth()
  const queryClient = useQueryClient()

  const restriction = useQuery<RestrictionDetail>({
    queryKey: ['restriction', id],
    queryFn: ({ signal }) => getRestriction(token, id, signal),
  })

  const teams = useTeams()
  const applications = useApplications()
  const environments = useEnvironments()

  const cancel = useMutation({
    mutationFn: () => cancelRestriction(token, id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['restrictions'] })
      void queryClient.invalidateQueries({ queryKey: ['restriction', id] })
    },
  })

  async function requestCancel() {
    setActionError(null)
    try {
      await cancel.mutateAsync()
    } catch (caught) {
      // 409 means it finished or was already cancelled — likely while this page was open.
      setActionError(
        caught instanceof ApiError ? caught.message : 'Could not cancel the restriction.',
      )
    }
  }

  if (restriction.isPending) {
    return (
      <main className={styles.page}>
        <p className={styles.state} role="status">
          Loading restriction…
        </p>
      </main>
    )
  }

  if (restriction.isError) {
    const notFound = restriction.error instanceof ApiError && restriction.error.isNotFound
    return (
      <main className={styles.page}>
        <div className={styles.state} role="alert">
          <p>
            {notFound
              ? 'That restriction does not exist.'
              : `Could not load the restriction. ${restriction.error.message}`}
          </p>
          <Link to="/restrictions">Back to restrictions</Link>
        </div>
      </main>
    )
  }

  const detail = restriction.data
  const editable = detail.status === 'SCHEDULED'
  const cancellable = detail.status === 'SCHEDULED' || detail.status === 'ACTIVE'

  /*
   * The one rule for colour, applied rather than copied. The mockup draws scope values in
   * magenta because it draws an ACTIVE hard freeze, and magenta claims "a deployment is
   * being stopped here". A scheduled, advisory, completed or cancelled restriction is
   * stopping nothing, so its scope takes neutral tags — magenta on all four would empty
   * the colour of the meaning the system gives it.
   */
  const inForce = detail.status === 'ACTIVE' && detail.level === 'HARD_FREEZE'

  const names = (entries: { id: number; name: string }[] | undefined) =>
    new Map((entries ?? []).map((entry) => [entry.id, entry.name]))

  return (
    <main className={styles.page}>
      <p className={styles.breadcrumb}>
        <Link to="/restrictions">← Restrictions</Link>
      </p>

      <div className={styles.header}>
        <h1 className={styles.title}>{detail.name}</h1>
        <div className={styles.badges}>
          <StatusBadge status={detail.status} />
          <LevelBadge level={detail.level} />
        </div>
      </div>

      <p className={styles.reason}>{detail.reason}</p>
      {detail.description && <p className={styles.description}>{detail.description}</p>}

      <dl className={styles.facts}>
        <Fact label="Starts" value={formatInstant(detail.startsAt)} />
        <Fact label="Ends" value={formatInstant(detail.endsAt)} />
        <Fact label="Created" value={formatInstant(detail.createdAt)} />
        <Fact label="Last updated" value={formatInstant(detail.updatedAt)} />
      </dl>

      <section aria-labelledby="scope-heading">
        <h2 className={styles.sectionHeading} id="scope-heading">
          Scope
        </h2>
        <p className={styles.scopeHint}>
          A deployment is affected when it matches <strong>every</strong> dimension below.
          “Any” means the dimension places no constraint.
        </p>
        <dl className={styles.dimensions}>
          <ScopeDimension
            label="Teams"
            ids={detail.scope.teamIds}
            namesById={names(teams.data)}
            loading={teams.isPending}
            inForce={inForce}
          />
          <ScopeDimension
            label="Applications"
            ids={detail.scope.applicationIds}
            namesById={names(applications.data)}
            loading={applications.isPending}
            inForce={inForce}
          />
          <ScopeDimension
            label="Environments"
            ids={detail.scope.environmentIds}
            namesById={names(environments.data)}
            loading={environments.isPending}
            inForce={inForce}
          />
        </dl>
      </section>

      {actionError && (
        <p className={styles.actionError} role="alert">
          {actionError}
        </p>
      )}

      <div className={styles.actions}>
        {/*
          * Affordances follow the backend's rules (`FZ-023`, `FZ-024`), but the control
          * stays on the page when the rule forbids it — disabled, with the reason beside
          * it. Removing it answers "where is Edit?" with silence.
          */}
        {editable ? (
          <Link className={styles.secondary} to={`/restrictions/${id}/edit`}>
            Edit
          </Link>
        ) : (
          <button className={styles.secondary} type="button" disabled aria-describedby="action-rule">
            Edit
          </button>
        )}

        <button
          className={styles.danger}
          type="button"
          onClick={requestCancel}
          disabled={!cancellable || cancel.isPending}
          aria-describedby={cancellable ? undefined : 'action-rule'}
        >
          {cancel.isPending ? 'Cancelling…' : 'Cancel restriction'}
        </button>

        <p className={styles.rule} id="action-rule">
          {cancellable
            ? 'Editing is closed once a restriction is active. Cancelling ends it now and notifies every channel.'
            : `This restriction is ${detail.status.toLowerCase()} and can no longer be changed.`}
        </p>
      </div>
    </main>
  )
}
