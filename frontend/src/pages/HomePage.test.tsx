import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import HomePage from '../pages/HomePage'
import type { HealthReport } from '../api/types'

/**
 * Builds a query client for a single test.
 *
 * @returns a fresh client with retries switched off, so a failing fetch fails
 *   the test quickly instead of retrying for half a minute
 */
function createTestQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0, staleTime: 0 },
    },
  })
}

const healthyReport: HealthReport = {
  status: 'UP',
  service: 'merqo-backend',
  time: '2026-11-20T12:00:00Z',
  businessTime: '2026-11-20T18:00:00',
  database: { status: 'UP', probe: 1 },
}

describe('HomePage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('shows the backend health once the request succeeds', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers(),
      text: async () => JSON.stringify(healthyReport),
      json: async () => healthyReport,
    })
    vi.stubGlobal('fetch', fetchMock)

    render(
      <QueryClientProvider client={createTestQueryClient()}>
        <MemoryRouter>
          <HomePage />
        </MemoryRouter>
      </QueryClientProvider>,
    )

    await waitFor(() => {
      expect(screen.getByText('Backend health')).toBeInTheDocument()
    })

    expect(screen.getByText('merqo-backend')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/health',
      expect.objectContaining({ credentials: 'same-origin' }),
    )
  })

  it('shows the waking up screen when the backend cannot be reached', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'))
    vi.stubGlobal('fetch', fetchMock)

    render(
      <QueryClientProvider client={createTestQueryClient()}>
        <MemoryRouter>
          <HomePage />
        </MemoryRouter>
      </QueryClientProvider>,
    )

    await waitFor(() => {
      expect(screen.getByText('Server is waking up')).toBeInTheDocument()
    })
  })

  it('shows the waking up screen when the proxy answers with a gateway status', async () => {
    // A 502, 503 or 504 means the free backend is asleep or busy. It is not a
    // business error, so the user must see the waking screen and not a red box.
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 503,
      headers: new Headers(),
      text: async () => JSON.stringify({ errorCode: 'BACKEND_UNREACHABLE' }),
      json: async () => ({ errorCode: 'BACKEND_UNREACHABLE' }),
    })
    vi.stubGlobal('fetch', fetchMock)

    render(
      <QueryClientProvider client={createTestQueryClient()}>
        <MemoryRouter>
          <HomePage />
        </MemoryRouter>
      </QueryClientProvider>,
    )

    await waitFor(() => {
      expect(screen.getByText('Server is waking up')).toBeInTheDocument()
    })
    expect(screen.queryByText('The backend could not be reached.')).not.toBeInTheDocument()
  })
})
