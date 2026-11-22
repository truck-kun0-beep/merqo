import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import RequireSession, { RequireSignedOut } from '../session/RequireSession'
import { SessionProvider } from '../session/SessionProvider'
import type { CurrentUser } from '../api/models'

/**
 * A signed in manager.
 */
const manager: CurrentUser = {
  id: '11111111-1111-4111-8111-111111111111',
  username: 'demo.manager.dm',
  fullName: 'Demo Manager (two branches)',
  email: null,
  accountType: 'MANAGER',
  mustChangePassword: false,
  isDemo: true,
  employeeCode: null,
  maxDiscountPercent: '0.00',
  locations: [{ id: 'loc-dm' }],
  permissions: [
    {
      code: 'LOCATION_VIEW',
      module: 'USERS_AND_ACCESS',
      description: 'View locations',
      scope: 'LOCATION',
      locationIds: ['loc-dm'],
    },
  ],
}

/**
 * Builds a query client for a single test.
 *
 * @returns a fresh client with retries switched off
 */
function createTestQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0, staleTime: 0 },
    },
  })
}

/**
 * Builds a fetch double that answers the session call with the given status.
 *
 * @param status the status of the session call
 * @param body the body of the session call
 * @returns the mock
 */
function createFetchMock(status: number, body: unknown) {
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    return {
      ok: status >= 200 && status < 300,
      status,
      statusText: status === 200 ? 'OK' : 'Error',
      headers: new Headers(),
      url,
      text: async () => JSON.stringify(body),
      json: async () => body,
    } as unknown as Response
  })
}

describe('RequireSession', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('sends an anonymous visitor to the login page', async () => {
    vi.stubGlobal('fetch', createFetchMock(401, { errorCode: 'UNAUTHENTICATED', status: 401 }))

    render(
      <QueryClientProvider client={createTestQueryClient()}>
        <SessionProvider>
          <MemoryRouter initialEntries={['/locations']}>
            <Routes>
              <Route path="/login" element={<p>the login page</p>} />
              <Route
                path="/locations"
                element={
                  <RequireSession>
                    <p>the locations screen</p>
                  </RequireSession>
                }
              />
            </Routes>
          </MemoryRouter>
        </SessionProvider>
      </QueryClientProvider>,
    )

    await waitFor(() => {
      expect(screen.getByText('the login page')).toBeInTheDocument()
    })
    expect(screen.queryByText('the locations screen')).not.toBeInTheDocument()
  })

  it('shows the screen to a signed in user', async () => {
    vi.stubGlobal('fetch', createFetchMock(200, manager))

    render(
      <QueryClientProvider client={createTestQueryClient()}>
        <SessionProvider>
          <MemoryRouter initialEntries={['/locations']}>
            <Routes>
              <Route
                path="/locations"
                element={
                  <RequireSession>
                    <p>the locations screen</p>
                  </RequireSession>
                }
              />
            </Routes>
          </MemoryRouter>
        </SessionProvider>
      </QueryClientProvider>,
    )

    await waitFor(() => {
      expect(screen.getByText('the locations screen')).toBeInTheDocument()
    })
  })

  it('refuses a screen the user has no permission for', async () => {
    vi.stubGlobal('fetch', createFetchMock(200, manager))

    render(
      <QueryClientProvider client={createTestQueryClient()}>
        <SessionProvider>
          <MemoryRouter initialEntries={['/audit']}>
            <Routes>
              <Route
                path="/audit"
                element={
                  <RequireSession permission="AUDIT_VIEW">
                    <p>the audit screen</p>
                  </RequireSession>
                }
              />
            </Routes>
          </MemoryRouter>
        </SessionProvider>
      </QueryClientProvider>,
    )

    await waitFor(() => {
      expect(screen.getByText('This screen is not open to your account.')).toBeInTheDocument()
    })
    expect(screen.queryByText('the audit screen')).not.toBeInTheDocument()
  })
})

describe('RequireSignedOut', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('sends a signed in user to the overview instead of the login page', async () => {
    vi.stubGlobal('fetch', createFetchMock(200, manager))

    render(
      <QueryClientProvider client={createTestQueryClient()}>
        <SessionProvider>
          <MemoryRouter initialEntries={['/login']}>
            <Routes>
              <Route path="/" element={<p>the overview</p>} />
              <Route
                path="/login"
                element={
                  <RequireSignedOut>
                    <p>the login page</p>
                  </RequireSignedOut>
                }
              />
            </Routes>
          </MemoryRouter>
        </SessionProvider>
      </QueryClientProvider>,
    )

    await waitFor(() => {
      expect(screen.getByText('the overview')).toBeInTheDocument()
    })
    expect(screen.queryByText('the login page')).not.toBeInTheDocument()
  })
})
