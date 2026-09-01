import { NavLink, Outlet } from 'react-router'
import { useAuth } from '../features/auth/authContext'
import styles from './AppLayout.module.css'

export function AppLayout() {
  const { signOut } = useAuth()

  return (
    <div className={styles.shell}>
      <header className={styles.header}>
        <span className={styles.brand}>FreezeHub</span>
        <nav className={styles.nav}>
          <NavLink
            to="/dashboard"
            className={({ isActive }) => (isActive ? styles.linkActive : styles.link)}
          >
            Dashboard
          </NavLink>
          <NavLink
            to="/restrictions"
            className={({ isActive }) => (isActive ? styles.linkActive : styles.link)}
          >
            Restrictions
          </NavLink>
          <NavLink
            to="/deployment-checks"
            className={({ isActive }) => (isActive ? styles.linkActive : styles.link)}
          >
            Checks
          </NavLink>
          <NavLink
            to="/catalog"
            className={({ isActive }) => (isActive ? styles.linkActive : styles.link)}
          >
            Catalog
          </NavLink>
          <NavLink
            to="/audit"
            className={({ isActive }) => (isActive ? styles.linkActive : styles.link)}
          >
            Audit
          </NavLink>
          <NavLink
            to="/settings"
            className={({ isActive }) => (isActive ? styles.linkActive : styles.link)}
          >
            Settings
          </NavLink>
        </nav>
        <button className={styles.signOut} type="button" onClick={signOut}>
          Sign out
        </button>
      </header>

      <Outlet />
    </div>
  )
}
