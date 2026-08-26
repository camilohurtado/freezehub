import { CatalogSection } from './CatalogSection'
import {
  useApplications,
  useCatalogMutations,
  useEnvironments,
  useTeamAssignment,
  useTeams,
} from './useCatalog'
import type { ApplicationSummary, CatalogEntry } from '../../types/api'
import styles from './CatalogPage.module.css'

/**
 * Teams, applications and environments — the data restriction scope is built from
 * (FZ-036).
 *
 * A thin CRUD over the catalog API on purpose. Catalog contents plausibly arrive from
 * elsewhere later (repositories, GitLab/GitHub groups), so nothing about the catalog is
 * decided here that an ingestion path would have to unpick.
 */
export function CatalogPage() {
  const teams = useTeams()
  const applications = useApplications()
  const environments = useEnvironments()

  const teamMutations = useCatalogMutations('teams')
  const applicationMutations = useCatalogMutations('applications')
  const environmentMutations = useCatalogMutations('environments')
  const assignment = useTeamAssignment()

  return (
    <main className={styles.page}>
      <h1 className={styles.title}>Catalog</h1>
      <p className={styles.intro}>
        What restrictions can be scoped to. A restriction applies to an application when it
        matches every dimension you set — so a team here is only meaningful once
        applications belong to it.
      </p>

      <CatalogSection<CatalogEntry>
        heading="Teams"
        description="Groups responsible for applications."
        placeholder="Payments"
        items={teams.data}
        isPending={teams.isPending}
        isError={teams.isError}
        error={teams.error}
        onCreate={(name) => teamMutations.create.mutateAsync(name)}
        onRename={(id, name) => teamMutations.rename.mutateAsync({ id, name })}
        onDelete={(id) => teamMutations.remove.mutateAsync(id)}
      />

      <CatalogSection<ApplicationSummary>
        heading="Applications"
        description="Deployable components. Assign the teams that own them."
        placeholder="payments-api"
        items={applications.data}
        isPending={applications.isPending}
        isError={applications.isError}
        error={applications.error}
        onCreate={(name) => applicationMutations.create.mutateAsync(name)}
        onRename={(id, name) => applicationMutations.rename.mutateAsync({ id, name })}
        onDelete={(id) => applicationMutations.remove.mutateAsync(id)}
        renderExtra={(application) => (
          <div className={styles.teamPicker}>
            {(teams.data ?? []).length === 0 ? (
              <span className={styles.teamHint}>Add a team to assign one</span>
            ) : (
              (teams.data ?? []).map((team) => (
                <label key={team.id} className={styles.teamCheckbox}>
                  <input
                    type="checkbox"
                    checked={application.teamIds.includes(team.id)}
                    aria-label={`${team.name} owns ${application.name}`}
                    onChange={(event) =>
                      assignment.mutate({
                        applicationId: application.id,
                        teamId: team.id,
                        attach: event.target.checked,
                      })
                    }
                  />
                  {team.name}
                </label>
              ))
            )}
          </div>
        )}
      />

      <CatalogSection<CatalogEntry>
        heading="Environments"
        description="Deployment destinations. Names are yours — no taxonomy is assumed."
        placeholder="production"
        items={environments.data}
        isPending={environments.isPending}
        isError={environments.isError}
        error={environments.error}
        onCreate={(name) => environmentMutations.create.mutateAsync(name)}
        onRename={(id, name) => environmentMutations.rename.mutateAsync({ id, name })}
        onDelete={(id) => environmentMutations.remove.mutateAsync(id)}
      />
    </main>
  )
}
