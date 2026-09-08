import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { ApiError } from '../../api/client'
import {
  createIntegration,
  deleteIntegration,
  listIntegrations,
  setIntegrationEnabled,
} from '../../api/integrations'
import { useAuth } from '../auth/authContext'
import type { Integration, IntegrationType } from '../../types/api'
import styles from './SettingsPage.module.css'

/** What each channel's config has to contain, mirrored from the backend for guidance only. */
const CONFIG_HELP: Record<IntegrationType, { label: string; placeholder: string; hint: string }> = {
  SLACK: {
    label: 'Slack',
    placeholder: '{"webhookUrl": "https://hooks.slack.com/services/..."}',
    hint: 'An incoming webhook URL. Treated as a credential — it is stored but never shown again.',
  },
  EMAIL: {
    label: 'Email',
    placeholder: '{"recipients": ["releases@acme.test"]}',
    hint: 'One or more recipient addresses.',
  },
  WEBHOOK: {
    label: 'Webhook',
    placeholder: '{"url": "https://acme.test/hooks/freezehub"}',
    hint: 'An HTTPS endpoint that will receive machine-readable lifecycle events.',
  },
}

/**
 * Where restriction announcements are sent (`FZ-045`).
 *
 * Extracted from SettingsPage when API keys and organization settings joined it
 * (`FZ-038`), following the CatalogSection pattern — three sections inline would have
 * made one component nobody wants to read.
 */
export function IntegrationsSection() {
  const { token } = useAuth()
  const queryClient = useQueryClient()

  const [type, setType] = useState<IntegrationType>('SLACK')
  const [config, setConfig] = useState('')
  const [actionError, setActionError] = useState<string | null>(null)

  const integrations = useQuery<Integration[]>({
    queryKey: ['integrations'],
    queryFn: ({ signal }) => listIntegrations(token, signal),
  })

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['integrations'] })
  }

  const create = useMutation({
    mutationFn: () => createIntegration(token, type, config),
    onSuccess: invalidate,
  })
  const toggle = useMutation({
    mutationFn: ({ id, enabled }: { id: number; enabled: boolean }) =>
      setIntegrationEnabled(token, id, enabled),
    onSuccess: invalidate,
  })
  const remove = useMutation({
    mutationFn: (id: number) => deleteIntegration(token, id),
    onSuccess: invalidate,
  })

  function report(caught: unknown) {
    // The backend validates each channel's config; its message says what is wrong with it.
    setActionError(
      caught instanceof ApiError ? caught.message : 'Something went wrong. Please try again.',
    )
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    setActionError(null)
    try {
      await create.mutateAsync()
      setConfig('')
    } catch (caught) {
      report(caught)
    }
  }

  const forbidden = integrations.error instanceof ApiError && integrations.error.status === 403

  return (
    <section className={styles.section} id="integrations" aria-labelledby="integrations-heading">
        <h2 className={styles.sectionHeading} id="integrations-heading">
          Notification destinations
        </h2>
        <p className={styles.sectionDescription}>
          Where restriction announcements are sent. A restriction that is scheduled,
          activated, completed or cancelled is announced to every enabled destination.
        </p>

        {forbidden && (
          <p className={styles.state} role="status">
            Only an administrator can manage notification destinations.
          </p>
        )}

        {!forbidden && integrations.isPending && (
          <p className={styles.state} role="status">
            Loading destinations…
          </p>
        )}

        {!forbidden && integrations.isError && (
          <p className={styles.actionError} role="alert">
            Could not load destinations. {integrations.error.message}
          </p>
        )}

        {actionError && (
          <p className={styles.actionError} role="alert">
            {actionError}
          </p>
        )}

        {!forbidden && integrations.data && integrations.data.length === 0 && (
          <p className={styles.state}>
            No destinations yet. Nothing will be announced until one is added.
          </p>
        )}

        {!forbidden && integrations.data && integrations.data.length > 0 && (
          <ul className={styles.list} aria-label="Notification destinations">
            {integrations.data.map((integration) => (
              <li key={integration.id} className={styles.row}>
                <div className={styles.rowMain}>
                  <span className={styles.itemName}>{CONFIG_HELP[integration.type].label}</span>
                  <span className={styles.summary}>{integration.summary}</span>
                </div>
                <div className={styles.rowActions}>
                  <label className={styles.toggle}>
                    <input
                      type="checkbox"
                      checked={integration.enabled}
                      aria-label={`${CONFIG_HELP[integration.type].label} enabled`}
                      onChange={(event) => {
                        setActionError(null)
                        toggle.mutate({ id: integration.id, enabled: event.target.checked })
                      }}
                    />
                    Enabled
                  </label>
                  <button
                    className={styles.danger}
                    type="button"
                    aria-label={`Delete ${CONFIG_HELP[integration.type].label} destination`}
                    onClick={async () => {
                      setActionError(null)
                      try {
                        await remove.mutateAsync(integration.id)
                      } catch (caught) {
                        report(caught)
                      }
                    }}
                  >
                    Delete
                  </button>
                </div>
              </li>
            ))}
          </ul>
        )}

        {!forbidden && (
          <form className={styles.createForm} onSubmit={submit}>
            <label className={styles.label} htmlFor="integration-type">
              Add a destination
            </label>
            <select
              id="integration-type"
              className={styles.input}
              value={type}
              onChange={(event) => setType(event.target.value as IntegrationType)}
            >
              {(Object.keys(CONFIG_HELP) as IntegrationType[]).map((option) => (
                <option key={option} value={option}>
                  {CONFIG_HELP[option].label}
                </option>
              ))}
            </select>

            <label className={styles.label} htmlFor="integration-config">
              Configuration
            </label>
            <textarea
              id="integration-config"
              className={styles.textarea}
              rows={3}
              placeholder={CONFIG_HELP[type].placeholder}
              value={config}
              onChange={(event) => setConfig(event.target.value)}
            />
            <p className={styles.hint}>{CONFIG_HELP[type].hint}</p>

            <button className={styles.primary} type="submit" disabled={!config.trim() || create.isPending}>
              {create.isPending ? 'Adding…' : 'Add destination'}
            </button>
          </form>
        )}
    </section>
  )
}
