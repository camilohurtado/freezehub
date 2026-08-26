import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate } from 'react-router'
import { ApiError } from '../../api/client'
import { createRestriction } from '../../api/restrictions'
import { useAuth } from '../auth/authContext'
import { RestrictionForm } from './RestrictionForm'
import { EMPTY_FORM } from './restrictionFormRules'
import type { CreateRestrictionBody } from '../../types/api'
import styles from './CreateRestrictionPage.module.css'

export function CreateRestrictionPage() {
  const [submitError, setSubmitError] = useState<string | null>(null)
  const { token } = useAuth()
  const queryClient = useQueryClient()
  const navigate = useNavigate()

  const create = useMutation({
    mutationFn: (body: CreateRestrictionBody) => createRestriction(token, body),
    onSuccess: (restriction) => {
      // Invalidated rather than patched: the server decided the id, status and timestamps.
      void queryClient.invalidateQueries({ queryKey: ['restrictions'] })
      navigate(`/restrictions/${restriction.id}`)
    },
  })

  async function submit(body: CreateRestrictionBody) {
    setSubmitError(null)
    try {
      await create.mutateAsync(body)
    } catch (caught) {
      // The backend is authoritative; its rejection is shown, not swallowed.
      setSubmitError(
        caught instanceof ApiError
          ? caught.message
          : 'Could not create the restriction. Please try again.',
      )
    }
  }

  return (
    <main className={styles.page}>
      <h1 className={styles.title}>New restriction</h1>
      <RestrictionForm
        initialValues={EMPTY_FORM}
        submitLabel="Create restriction"
        submitting={create.isPending}
        submitError={submitError}
        onSubmit={submit}
        cancelTo="/restrictions"
      />
    </main>
  )
}
