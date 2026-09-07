import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './app/App.tsx'

// Broadsheet's component layer — global on purpose: these are the design system's own
// class names, shared by every screen and diffed against upstream (FZ-101).
import './styles/broadsheet.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
