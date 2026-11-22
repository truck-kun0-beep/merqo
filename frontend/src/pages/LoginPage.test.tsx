import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import LoginPage from '../pages/LoginPage'
import { SessionProvider } from '../session/SessionProvider'
import type { CurrentUser } from '../api/models'

/**
 * A signed in demo manager, used as the answer of the session call.
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
 * Builds a fetch double that answers with the given routes.
 *
 * @param routes the answers, keyed by path suffix
 * @returns the mock
 */
function createFetchMock(routes: Record<string, { status?: number; body: unknown }>) {
  return vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
    const url = String(input)
    const key = Object.keys(routes).find((candidate) => url.includes(candidate))
    const route = key ? routes[key] : { status: 404, body: { errorCode: 'NOT_FOUND' } }
    const status = route.status ?? 200
    return {
      ok: status >= 200 && status < 300,
      status,
      statusText: status === 200 ? 'OK' : 'Error',
      headers: new Headers(),
      url,
      text: async () => JSON.stringify(route.body),
      json: async () => route.body,
    } as unknown as Response
  })
}

/**
 * Renders the login page with the session provider around it.
 *
 * @returns nothing
 */
function renderLoginPage() {
  return render(
    <QueryClientProvider client={createTestQueryClient()}>
      <SessionProvider>
        <MemoryRouter initialEntries={['/login']}>
          <LoginPage />
        </MemoryRouter>
      </SessionProvider>
    </QueryClientProvider>,
  )
}

describe('LoginPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    document.cookie = 'XSRF-TOKEN=test-token'
  })

  afterEach(() => {
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT'
  })

  it('signs a user in with a username and a password', async () => {
    const fetchMock = createFetchMock({
      '/auth/status': { body: { setupRequired: false, demoMode: false, businessName: 'Merqo' } },
      '/auth/login': { body: manager },
      '/auth/me': { status: 401, body: { errorCode: 'UNAUTHENTICATED' } },
    })
    vi.stubGlobal('fetch', fetchMock)

    renderLoginPage()

    await userEvent.type(await screen.findByLabelText('Username or email'), 'demo.manager.dm')
    await userEvent.type(screen.getByLabelText('Password'), 'secret123')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/v1/auth/login',
        expect.objectContaining({ method: 'POST' }),
      )
    })

    const loginCall = fetchMock.mock.calls.find((call) => String(call[0]).includes('/auth/login'))
    const init = loginCall?.[1] as RequestInit
    expect(JSON.parse(init.body as string)).toEqual({
      identifier: 'demo.manager.dm',
      password: 'secret123',
    })
  })

  it('sends the CSRF token in the header of a request that changes something', async () => {
    const fetchMock = createFetchMock({
      '/auth/status': { body: { setupRequired: false, demoMode: false, businessName: 'Merqo' } },
      '/auth/login': { body: manager },
      '/auth/me': { status: 401, body: { errorCode: 'UNAUTHENTICATED' } },
    })
    vi.stubGlobal('fetch', fetchMock)

    renderLoginPage()

    await userEvent.type(await screen.findByLabelText('Username or email'), 'demo.manager.dm')
    await userEvent.type(screen.getByLabelText('Password'), 'secret123')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/v1/auth/login',
        expect.objectContaining({ method: 'POST' }),
      )
    })

    const loginCall = fetchMock.mock.calls.find((call) => String(call[0]).includes('/auth/login'))
    const headers = (loginCall?.[1] as RequestInit | undefined)?.headers as
      Record<string, string> | undefined
    expect(headers?.['X-XSRF-TOKEN']).toBe('test-token')
  })

  it('shows the demo accounts when demo mode is on and signs in with one click', async () => {
    const fetchMock = createFetchMock({
      '/auth/status': {
        body: { setupRequired: false, demoMode: true, businessName: 'Merqo Demo' },
      },
      '/demo/accounts': {
        body: [
          {
            accountKey: 'owner',
            displayName: 'Demo Owner',
            role: 'Owner',
            description: 'Every branch.',
          },
        ],
      },
      '/demo/login': { body: manager },
      '/auth/me': { status: 401, body: { errorCode: 'UNAUTHENTICATED' } },
    })
    vi.stubGlobal('fetch', fetchMock)

    renderLoginPage()

    await userEvent.click(await screen.findByRole('button', { name: /Demo Owner/ }))

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/v1/demo/login',
        expect.objectContaining({ method: 'POST' }),
      )
    })
  })

  it('shows a clear message when the credentials are wrong', async () => {
    const fetchMock = createFetchMock({
      '/auth/status': { body: { setupRequired: false, demoMode: false, businessName: 'Merqo' } },
      '/auth/login': {
        status: 401,
        body: {
          status: 401,
          title: 'Unauthorized',
          detail: 'Invalid username or password',
          errorCode: 'INVALID_CREDENTIALS',
          correlationId: 'abc',
          timestamp: '2026-11-20T12:00:00Z',
          fieldErrors: {},
          errors: [],
          type: 'about:blank',
          instance: '/api/v1/auth/login',
        },
      },
      '/auth/me': { status: 401, body: { errorCode: 'UNAUTHENTICATED' } },
    })
    vi.stubGlobal('fetch', fetchMock)

    renderLoginPage()

    await userEvent.type(await screen.findByLabelText('Username or email'), 'demo.manager.dm')
    await userEvent.type(screen.getByLabelText('Password'), 'wrong-password')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    await waitFor(() => {
      expect(screen.getByText('Invalid username or password.')).toBeInTheDocument()
    })
  })

  it('warns when the system has no owner yet', async () => {
    const fetchMock = createFetchMock({
      '/auth/status': { body: { setupRequired: true, demoMode: false, businessName: 'Merqo' } },
      '/auth/me': { status: 401, body: { errorCode: 'UNAUTHENTICATED' } },
    })
    vi.stubGlobal('fetch', fetchMock)

    renderLoginPage()

    await waitFor(() => {
      expect(screen.getByText('This system has no owner yet.')).toBeInTheDocument()
    })
  })
})
