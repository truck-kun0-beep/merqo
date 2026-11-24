import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import App from './App'
import { ToastProvider } from './components/ui/Toast'
import { SessionProvider } from './session/SessionProvider'
import './index.css'

/**
 * The query client.
 *
 * Retries are kept short because the backend may be waking up. A failed request
 * is never cached longer than a few seconds.
 */
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 10_000,
      gcTime: 5 * 60_000,
      retry: 3,
      refetchOnWindowFocus: true,
    },
  },
})

const container = document.getElementById('root')
if (!container) {
  throw new Error('The root element is missing from index.html')
}

createRoot(container).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <SessionProvider>
        <ToastProvider>
          <BrowserRouter>
            <App />
          </BrowserRouter>
        </ToastProvider>
      </SessionProvider>
    </QueryClientProvider>
  </StrictMode>,
)
