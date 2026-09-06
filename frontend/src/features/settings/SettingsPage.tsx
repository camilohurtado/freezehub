import { ApiKeysSection } from './ApiKeysSection'
import { BillingSection } from './BillingSection'
import { IntegrationsSection } from './IntegrationsSection'
import { OrganizationSection } from './OrganizationSection'
import styles from './SettingsPage.module.css'

/**
 * Everything an administrator configures for their organization.
 *
 * Each section loads and fails independently: a member who can see none of them gets
 * three explanations rather than one blank page, and one section's outage does not hide
 * the others.
 */
export function SettingsPage() {
  return (
    <main className={styles.page}>
      <h1 className={styles.title}>Settings</h1>

      <BillingSection />
      <IntegrationsSection />
      <ApiKeysSection />
      <OrganizationSection />
    </main>
  )
}
