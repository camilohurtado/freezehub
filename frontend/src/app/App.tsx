import { RouterProvider } from 'react-router'
import { AuthProvider } from '../features/auth/AuthProvider'
import { QueryProvider } from './QueryProvider'
import { router } from './router'

function App() {
  return (
    <AuthProvider>
      <QueryProvider>
        <RouterProvider router={router} />
      </QueryProvider>
    </AuthProvider>
  )
}

export default App
