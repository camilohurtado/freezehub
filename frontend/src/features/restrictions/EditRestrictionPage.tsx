import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import { ApiError } from '../../api/client'
import { getRestriction, updateRestriction } from '../../api/restrictions'
import { useAuth } from '../auth/authContext'
import { utcIsoToLocalInput } from '../../utils/datetime'
import { RestrictionForm } from './RestrictionForm'
import type { CreateRestrictionBody, RestrictionDetail } from '../../types/api'
import type { RestrictionFormValues } from './restrictionFormRules'
import styles from './CreateRestrictionPage.module.css'

/** Stored instants come back in UTC; the inputs want local wall-clock time. */
function toFormValues(restriction: RestrictionDetail): RestrictionFormValues {
  return {
    name: restriction.name,
    description: restriction.description ?? '',
    reason: restriction.reason,
    level: restriction.level,
    startsAtLocal: utcIsoToLocalInput(restriction.startsAt),
    endsAtLocal: utcIsoToLocalInput(restriction.endsAt),
    teamIds: restriction.scope.teamIds,
    applicationIds: restriction.scope.applicationIds,
    environmentIds: restriction.scope.environmentIds,
  }
}

export function EditRestrictionPage() {
  const { restrictionId } = useParams()
  const id = Number(restrictionId)
  const [submitError, setSubmitError] = useState<string | null>(null)

  const { token } = useAuth()
  const queryClient = useQueryClient()
  const navigate = useNavigate()

  const restriction = useQuery<RestrictionDetail>({
    queryKey: ['restriction', id],
    queryFn: ({ signal }) => getRestriction(token, id, signal),
  })

  const update = useMutation({
    mutationFn: (body: CreateRestrictionBody) => updateRestriction(token, id, body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['restrictions'] })
      void queryClient.invalidateQueries({ queryKey: ['restriction', id] })
      navigate(`/restrictions/${id}`)
    },
  })

  async function submit(body: CreateRestrictionBody) {
    setSubmitError(null)
    try {
      await update.mutateAsync(body)
    } catch (caught) {
      // A 409 here means the restriction is no longer SCHEDULED — it may have activated
      // while this form was open. Saying so is more use than "request failed".
      setSubmitError(
        caught instanceof ApiError
          ? caught.message
          : 'Could not save the restriction. Please try again.',
      )
    }
  }

  if (restriction.isPending) {
    return (
      <main className={styles.page}>
        <p role="status">Loading restriction…</p>
      </main>
    )
  }

  if (restriction.isError) {
    return (
      <main className={styles.page}>
        <p role="alert">
          {restriction.error instanceof ApiError && restriction.error.isNotFound
            ? 'That restriction does not exist.'
            : `Could not load the restriction. ${restriction.error.message}`}
        </p>
      </main>
    )
  }

  // Guarded here as well as by the backend: offering an edit that can only be rejected
  // wastes the user's time (FZ-023 permits updates only while SCHEDULED).
  if (restriction.data.status !== 'SCHEDULED') {
    return (
      <main className={styles.page}>
        <h1 className={styles.title}>{restriction.data.name}</h1>
        <p className={styles.notice} role="status">
          This restriction is {restriction.data.status.toLowerCase()} and can no longer be
          edited. Only a scheduled restriction can be changed.
        </p>
      </main>
    )
  }

  return (
    <main className={styles.page}>
      <h1 className={styles.title}>Edit restriction</h1>
      <RestrictionForm
        initialValues={toFormValues(restriction.data)}
        submitLabel="Save changes"
        submitting={update.isPending}
        submitError={submitError}
        onSubmit={submit}
        cancelTo={`/restrictions/${id}`}
      />
    </main>
  )
}
