/*
 * Entry point for the React front end. Vite loads this from index.html, it mounts <App/>
 * into the #root div and pulls in index.css, which holds the Tailwind layers and the
 * project's theme variables. StrictMode is development only: it double invokes effects
 * and renders to surface impure code, which is why some pages log a duplicate fetch
 * locally but only fire it once in the production build.
 */
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.jsx'

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
