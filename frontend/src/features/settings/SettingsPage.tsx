import { ApiKeysSection } from './ApiKeysSection'
import { BillingSection } from './BillingSection'
import { IntegrationsSection } from './IntegrationsSection'
import { OrganizationSection } from './OrganizationSection'
import { useActiveSection } from './useActiveSection'
import styles from './SettingsPage.module.css'

/**
 * Everything an administrator configures for their organization (`1h`, FZ-116).
 *
 * Each section loads and fails independently: a member who can see none of them gets four
 * explanations rather than one blank page, and one section's outage does not hide the
 * others.
 *
 * Ordered as `1h` orders it — organization, integrations, keys, billing — which is
 * roughly how often they are touched, and the rail beside them is what makes a page this
 * long navigable rather than merely scrollable.
 */
const SECTIONS = [
  { id: 'organization', label: 'Organization' },
  { id: 'integrations', label: 'Integrations' },
  { id: 'api-keys', label: 'API keys' },
  { id: 'billing', label: 'Billing' },
] as const

const SECTION_IDS = SECTIONS.map((section) => section.id) as unknown as string[]

export function SettingsPage() {
  const active = useActiveSection(SECTION_IDS)

  return (
    <main className={styles.page}>
      <nav className={styles.rail} aria-label="Settings sections">
        {SECTIONS.map((section) => (
          <a
            key={section.id}
            href={`#${section.id}`}
            className={active === section.id ? styles.railLinkActive : styles.railLink}
            aria-current={active === section.id ? 'true' : undefined}
          >
            {section.label}
          </a>
        ))}
      </nav>

      <div className={styles.content}>
        <h1 className={styles.title}>Settings</h1>

        <OrganizationSection />
        <IntegrationsSection />
        <ApiKeysSection />
        <BillingSection />
      </div>
    </main>
  )
}
