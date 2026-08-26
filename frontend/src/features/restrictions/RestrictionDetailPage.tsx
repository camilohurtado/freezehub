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

/** Scope comes back as ids; ids tell a reader nothing, so they are resolved to names. */
function ScopeDimension({
  label,
  ids,
  namesById,
  loading,
}: {
  label: string
  ids: number[]
  namesById: Map<number, string>
  loading: boolean
}) {
  return (
    <div className={styles.dimension}>
      <dt className={styles.dimensionLabel}>{label}</dt>
      <dd className={styles.dimensionValue}>
        {ids.length === 0 ? (
          <span className={styles.any}>Any</span>
        ) : (
          <span>
            {ids
              .map((id) => namesById.get(id) ?? (loading ? '…' : `#${id}`))
              .join(', ')}
          </span>
        )}
      </dd>
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
        <dt className={styles.factLabel}>Starts</dt>
        <dd className={styles.factValue}>{formatInstant(detail.startsAt)}</dd>
        <dt className={styles.factLabel}>Ends</dt>
        <dd className={styles.factValue}>{formatInstant(detail.endsAt)}</dd>
        <dt className={styles.factLabel}>Created</dt>
        <dd className={styles.factValue}>{formatInstant(detail.createdAt)}</dd>
        <dt className={styles.factLabel}>Last updated</dt>
        <dd className={styles.factValue}>{formatInstant(detail.updatedAt)}</dd>
      </dl>

      <section className={styles.scopeBlock} aria-labelledby="scope-heading">
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
          />
          <ScopeDimension
            label="Applications"
            ids={detail.scope.applicationIds}
            namesById={names(applications.data)}
            loading={applications.isPending}
          />
          <ScopeDimension
            label="Environments"
            ids={detail.scope.environmentIds}
            namesById={names(environments.data)}
            loading={environments.isPending}
          />
        </dl>
      </section>

      {actionError && (
        <p className={styles.actionError} role="alert">
          {actionError}
        </p>
      )}

      <div className={styles.actions}>
        {/* Affordances follow the backend's rules rather than being offered and refused. */}
        {editable && (
          <Link className={styles.secondary} to={`/restrictions/${id}/edit`}>
            Edit
          </Link>
        )}
        {cancellable && (
          <button
            className={styles.danger}
            type="button"
            onClick={requestCancel}
            disabled={cancel.isPending}
          >
            {cancel.isPending ? 'Cancelling…' : 'Cancel restriction'}
          </button>
        )}
        {!editable && !cancellable && (
          <p className={styles.finalState}>
            This restriction is {detail.status.toLowerCase()} and can no longer be changed.
          </p>
        )}
      </div>
    </main>
  )
}
