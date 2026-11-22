import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import UserDetailPage from '../pages/UserDetailPage'
import { SessionProvider } from '../session/SessionProvider'
import type { CurrentUser } from '../api/models'

/**
 * A signed in owner, used as the answer of the session call.
 */
const owner: CurrentUser = {
  id: '11111111-1111-4111-8111-111111111111',
  username: 'owner',
  fullName: 'Demo Owner',
  email: null,
  accountType: 'OWNER',
  mustChangePassword: false,
  isDemo: true,
  employeeCode: null,
  maxDiscountPercent: '0.00',
  locations: [{ id: 'loc-wh' }],
  permissions: [
    {
      code: 'USER_VIEW',
      module: 'USERS_AND_ACCESS',
      description: 'View users and accounts',
      scope: 'ALL',
      locationIds: [],
    },
    {
      code: 'TEMPLATE_MANAGE',
      module: 'USERS_AND_ACCESS',
      description: 'Create and edit permission templates',
      scope: 'ALL',
      locationIds: [],
    },
  ],
}

/**
 * The id of the account the screen shows.
 */
const accountId = '22222222-2222-4222-8222-222222222222'

/**
 * The account the backend answers with.
 */
const account = {
  id: accountId,
  username: 'kasem',
  fullName: 'Kasem Uddin',
  email: 'kasem@merqo.test',
  phone: '01700000000',
  accountType: 'STAFF',
  isActive: true,
  mustChangePassword: true,
  employeeCode: 'EMP-002',
  maxDiscountPercent: '5.00',
  isDemo: false,
  lastLoginAt: '2026-11-20T09:00:00Z',
  createdAt: '2026-11-01T09:00:00Z',
  templates: [
    {
      id: '11111111-0000-4000-8000-000000000001',
      code: 'CASHIER',
      name: 'Cashier',
      description: 'Sells at the counter.',
      isActive: true,
    },
  ],
  overrides: [
    {
      id: 'override-1',
      permission: 'DISCOUNT_MANAGE',
      effect: 'ALLOW',
      locationId: null,
    },
  ],
  locations: [
    {
      id: 'assignment-1',
      locationId: 'loc-wh',
      assignmentType: 'STAFF',
      startedAt: '2026-11-02T09:00:00Z',
      endedAt: null,
      isActive: true,
    },
  ],
  delegations: [],
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
 * Renders the account screen with the session provider around it.
 *
 * @returns nothing
 */
function renderUserDetailPage() {
  return render(
    <QueryClientProvider client={createTestQueryClient()}>
      <SessionProvider>
        <MemoryRouter initialEntries={[`/users/${accountId}`]}>
          <Routes>
            <Route path="/users/:id" element={<UserDetailPage />} />
          </Routes>
        </MemoryRouter>
      </SessionProvider>
    </QueryClientProvider>,
  )
}

/**
 * The routes the screen needs.
 *
 * @returns the answers
 */
function routes(): Record<string, { status?: number; body: unknown }> {
  return {
    '/effective-permissions': {
      body: [
        {
          code: 'POS_SELL',
          module: 'POS',
          description: 'Sell at the counter',
          scope: 'LOCATION',
          locationIds: ['loc-wh'],
          reasons: ['from a template'],
        },
      ],
    },
    '/permission-templates': {
      body: [
        {
          id: '11111111-0000-4000-8000-000000000001',
          code: 'CASHIER',
          name: 'Cashier',
          description: 'Sells at the counter.',
          isSystem: true,
          isActive: true,
          permissions: ['POS_SELL'],
        },
      ],
    },
    '/permissions': {
      body: [
        {
          code: 'POS',
          label: 'Point of sale',
          permissions: [
            {
              code: 'POS_SELL',
              description: 'Sell at the counter',
              scope: 'LOCATION',
              ownerOnly: false,
              delegable: true,
            },
            {
              code: 'POS_REFUND',
              description: 'Refund a sale',
              scope: 'LOCATION',
              ownerOnly: false,
              delegable: false,
            },
          ],
        },
      ],
    },
    '/locations': {
      body: [
        {
          id: 'loc-wh',
          code: 'WH',
          name: 'Main Warehouse',
          locationType: 'WAREHOUSE',
          address: null,
          phone: null,
          isActive: true,
          isOnlineFulfillment: true,
        },
      ],
    },
    '/auth/me': { body: owner },
    [`/users/${accountId}`]: { body: account },
  }
}

describe('UserDetailPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('shows the account and the permissions it really holds', async () => {
    vi.stubGlobal('fetch', createFetchMock(routes()))

    renderUserDetailPage()

    expect(await screen.findByRole('heading', { name: 'Kasem Uddin' })).toBeInTheDocument()
    expect(await screen.findByText('POS_SELL')).toBeInTheDocument()
    expect(screen.getByText('from a template')).toBeInTheDocument()
  })

  it('shows the templates and the overrides of the account', async () => {
    vi.stubGlobal('fetch', createFetchMock(routes()))

    renderUserDetailPage()

    expect(await screen.findByText('Cashier')).toBeInTheDocument()
    expect(await screen.findByText('DISCOUNT_MANAGE')).toBeInTheDocument()
  })

  it('sends the new template list to the backend', async () => {
    const fetchMock = createFetchMock(routes())
    vi.stubGlobal('fetch', fetchMock)

    renderUserDetailPage()

    await screen.findByText('Cashier')

    await userEvent.click(screen.getByRole('button', { name: 'Save the templates' }))

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        `/api/v1/users/${accountId}/templates`,
        expect.objectContaining({ method: 'PUT' }),
      )
    })

    const saveCall = fetchMock.mock.calls.find(
      (call) => String(call[0]).includes('/templates') && call[1]?.method === 'PUT',
    )
    const body = JSON.parse((saveCall?.[1] as RequestInit).body as string)
    expect(body.templateIds).toEqual(['11111111-0000-4000-8000-000000000001'])
  })

  it('shows the message of the backend when the change is refused', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      const isSave = url.includes('/templates') && init?.method === 'PUT'
      const body = isSave
        ? {
            type: 'about:blank',
            title: 'Forbidden',
            status: 403,
            detail: 'You may not change this account.',
            instance: url,
            errorCode: 'FORBIDDEN',
            correlationId: 'abc',
            timestamp: '2026-11-21T08:00:00Z',
            fieldErrors: {},
            errors: [],
          }
        : url.includes('/effective-permissions')
          ? []
          : url.includes('/permission-templates')
            ? []
            : url.includes('/permissions')
              ? []
              : url.includes('/locations')
                ? []
                : url.includes('/auth/me')
                  ? owner
                  : account
      const status = isSave ? 403 : 200
      return {
        ok: status >= 200 && status < 300,
        status,
        statusText: isSave ? 'Error' : 'OK',
        headers: new Headers(),
        url,
        text: async () => JSON.stringify(body),
        json: async () => body,
      } as unknown as Response
    })
    vi.stubGlobal('fetch', fetchMock)

    renderUserDetailPage()

    await screen.findByText('Cashier')

    await userEvent.click(screen.getByRole('button', { name: 'Save the templates' }))

    await waitFor(() => {
      expect(screen.getByText('You may not change this account.')).toBeInTheDocument()
    })
  })
})
