import { describe, expect, it, vi, beforeEach } from 'vitest'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import AppShell from './AppShell'
import { SessionProvider } from '../session/SessionProvider'
import { SESSION_QUERY_KEY } from '../session/sessionContext'
import type { CurrentUser } from '../api/models'

/**
 * A signed in owner, used as the answer of the session call.
 */
const owner: CurrentUser = {
  id: 'u-1',
  username: 'owner',
  fullName: 'Ayesha Rahman',
  email: null,
  accountType: 'OWNER',
  mustChangePassword: false,
  isDemo: true,
  employeeCode: null,
  maxDiscountPercent: '100.00',
  locations: [{ id: 'loc-1' }],
  permissions: [
    {
      code: 'LOCATION_VIEW',
      module: 'USERS_AND_ACCESS',
      description: 'View locations',
      scope: 'LOCATION',
      locationIds: ['loc-1'],
    },
  ],
}

/**
 * Builds an answer of the fetch double.
 *
 * @param status the status of the answer
 * @param body the body of the answer
 * @param url the address that was asked for
 * @returns the answer
 */
function answer(status: number, body: unknown, url: string): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: status === 200 ? 'OK' : 'Error',
    headers: new Headers(),
    url,
    text: async () => JSON.stringify(body),
    json: async () => body,
  } as unknown as Response
}

/**
 * Builds a fetch double that behaves like the backend.
 *
 * <p>The session call answers with the signed in owner until the sign out ran.
 * After the sign out the session call answers with a 401, the way the backend
 * answers once the session is gone.
 *
 * @param options `signOutFails` makes the sign out fail with a network error
 * @returns the mock
 */
function createFetchMock(options: { signOutFails?: boolean } = {}) {
  let signedIn = true

  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)

    if (url.includes('/auth/logout')) {
      if (options.signOutFails) {
        // The backend could not be reached at all.
        throw new TypeError('Failed to fetch')
      }
      signedIn = false
      return {
        ok: true,
        status: 204,
        statusText: 'No Content',
        headers: new Headers(),
        url,
        text: async () => '',
        json: async () => ({}),
      } as unknown as Response
    }

    if (url.includes('/auth/me')) {
      return answer(
        signedIn ? 200 : 401,
        signedIn ? owner : { errorCode: 'UNAUTHENTICATED', status: 401 },
        url,
      )
    }

    if (url.includes('/auth/status')) {
      return answer(200, { setupRequired: false, demoMode: true, businessName: 'Merqo' }, url)
    }

    if (url.includes('/health')) {
      return answer(200, { status: 'UP' }, url)
    }

    return answer(404, {}, url)
  })
}

/**
 * Renders the shell with a login route behind it.
 *
 * <p>The login route is a child of the shell on purpose. The shell therefore
 * stays mounted after the sign out, so the test can prove that the session
 * itself was cleared and not only that another route was opened. If the user
 * menu is still there, the cached user survived the sign out.
 *
 * @param fetchMock the fetch double
 * @returns the query client, so a test can ask for a fresh session answer
 */
function renderShell(fetchMock: ReturnType<typeof vi.fn>) {
  vi.stubGlobal('fetch', fetchMock)

  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
  })

  render(
    <QueryClientProvider client={client}>
      <SessionProvider>
        <MemoryRouter initialEntries={['/']}>
          <Routes>
            <Route element={<AppShell />}>
              <Route index element={<p>Dashboard content</p>} />
              <Route path="login" element={<p>Login screen</p>} />
            </Route>
          </Routes>
        </MemoryRouter>
      </SessionProvider>
    </QueryClientProvider>,
  )

  return client
}

describe('the sign out', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    document.cookie = 'XSRF-TOKEN=test-token'
  })

  it('ends the session on the server and sends the user to the login screen', async () => {
    const fetchMock = createFetchMock()

    renderShell(fetchMock)

    await screen.findByText('Ayesha Rahman')

    await userEvent.click(screen.getByRole('button', { name: /Ayesha Rahman/ }))
    await userEvent.click(screen.getByRole('menuitem', { name: 'Sign out' }))

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/v1/auth/logout',
        expect.objectContaining({ method: 'POST' }),
      )
    })

    await waitFor(() => {
      expect(screen.getByText('Login screen')).toBeInTheDocument()
    })

    await waitFor(() => {
      expect(screen.queryByText('Ayesha Rahman')).not.toBeInTheDocument()
    })
  })

  it('leaves the signed in shell even when the server cannot be reached', async () => {
    const fetchMock = createFetchMock({ signOutFails: true })

    renderShell(fetchMock)

    await screen.findByText('Ayesha Rahman')

    await userEvent.click(screen.getByRole('button', { name: /Ayesha Rahman/ }))
    await userEvent.click(screen.getByRole('menuitem', { name: 'Sign out' }))

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/v1/auth/logout',
        expect.objectContaining({ method: 'POST' }),
      )
    })

    // The server refused the sign out, but the user still leaves the signed in
    // shell and lands on the login page.
    await waitFor(() => {
      expect(screen.getByText('Login screen')).toBeInTheDocument()
    })

    await waitFor(() => {
      expect(screen.queryByText('Ayesha Rahman')).not.toBeInTheDocument()
    })
  })

  it('drops the signed in user when the server says the session is over', async () => {
    const fetchMock = createFetchMock()
    const client = renderShell(fetchMock)

    await screen.findByText('Ayesha Rahman')

    // The session ends on the server without the user asking for it: the
    // session timed out, or the backend restarted and lost it. The next
    // answer of "who am I?" is a 401.
    vi.mocked(fetchMock).mockImplementation(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/auth/me')) {
        return answer(401, { errorCode: 'UNAUTHENTICATED', status: 401 }, url)
      }
      return answer(200, { status: 'UP' }, url)
    })

    await act(async () => {
      await client.invalidateQueries({ queryKey: SESSION_QUERY_KEY })
    })

    // The answer of the server is cached, so the shell must not keep showing
    // the user that the cache still holds.
    await waitFor(() => {
      expect(screen.queryByText('Ayesha Rahman')).not.toBeInTheDocument()
    })
  })
})
