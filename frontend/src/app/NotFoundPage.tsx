import { Link } from 'react-router'

export function NotFoundPage() {
  return (
    <main style={{ maxWidth: '32rem', margin: '0 auto', padding: '3rem 1.5rem' }}>
      <h1>Page not found</h1>
      <p>
        <Link to="/dashboard">Back to the dashboard</Link>
      </p>
    </main>
  )
}
