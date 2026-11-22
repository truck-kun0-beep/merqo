import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import SettingsPage from '../pages/SettingsPage'
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
      code: 'SETTINGS_VIEW',
      module: 'USERS_AND_ACCESS',
      description: 'View business settings',
      scope: 'ALL',
      locationIds: [],
    },
  ],
}

/**
 * The settings the backend answers with.
 */
const settings = {
  groups: {
    BUSINESS: [
      {
        key: 'business.name',
        label: 'Name',
        type: 'TEXT',
        value: 'Merqo Demo Store',
        defaultValue: 'Merqo Demo Store',
        readOnly: false,
      },
    ],
    RECEIPT: [
      {
        key: 'receipt.footer',
        label: 'Footer',
        type: 'TEXT',
        value: 'Thank you.',
        defaultValue: 'Thank you.',
        readOnly: false,
      },
    ],
    TAX: [
      {
        key: 'tax.rate_percent',
        label: 'Rate percent',
        type: 'DECIMAL',
        value: '0',
        defaultValue: '0',
        readOnly: false,
      },
    ],
    THRESHOLDS: [
      {
        key: 'threshold.dead_stock_days',
        label: 'Dead stock days',
        type: 'INTEGER',
        value: '60',
        defaultValue: '60',
        readOnly: false,
      },
    ],
  },
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
 * Renders the settings screen with the session provider around it.
 *
 * @returns nothing
 */
function renderSettingsPage() {
  return render(
    <QueryClientProvider client={createTestQueryClient()}>
      <SessionProvider>
        <MemoryRouter initialEntries={['/settings']}>
          <SettingsPage />
        </MemoryRouter>
      </SessionProvider>
    </QueryClientProvider>,
  )
}

describe('SettingsPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('shows the settings of every group', async () => {
    vi.stubGlobal(
      'fetch',
      createFetchMock({
        '/settings': { body: settings },
        '/auth/me': { body: owner },
      }),
    )

    renderSettingsPage()

    expect(await screen.findByDisplayValue('Merqo Demo Store')).toBeInTheDocument()
    expect(screen.getByDisplayValue('Thank you.')).toBeInTheDocument()
    expect(screen.getByDisplayValue('60')).toBeInTheDocument()
  })

  it('sends only the changed values to the backend', async () => {
    const fetchMock = createFetchMock({
      '/settings': { body: settings },
      '/auth/me': { body: owner },
    })
    vi.stubGlobal('fetch', fetchMock)

    renderSettingsPage()

    const nameField = await screen.findByDisplayValue('Merqo Demo Store')
    await userEvent.clear(nameField)
    await userEvent.type(nameField, 'Merqo Real Shop')
    await userEvent.click(screen.getByRole('button', { name: 'Save the settings' }))

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/v1/settings',
        expect.objectContaining({ method: 'PUT' }),
      )
    })

    const saveCall = fetchMock.mock.calls.find(
      (call) => String(call[0]).includes('/settings') && call[1]?.method === 'PUT',
    )
    const body = JSON.parse((saveCall?.[1] as RequestInit).body as string)
    expect(body.values).toEqual({ 'business.name': 'Merqo Real Shop' })
  })

  it('says when there is nothing to save', async () => {
    vi.stubGlobal(
      'fetch',
      createFetchMock({
        '/settings': { body: settings },
        '/auth/me': { body: owner },
      }),
    )

    renderSettingsPage()

    await screen.findByDisplayValue('Merqo Demo Store')

    expect(screen.getByText('No change to save.')).toBeInTheDocument()
  })

  it('shows the message of the backend when the save is refused', async () => {
    const problem = {
      type: 'about:blank',
      title: 'Unprocessable Content',
      status: 422,
      detail: 'tax.rate_percent must be a number.',
      instance: '/api/v1/settings',
      errorCode: 'VALIDATION_FAILED',
      correlationId: 'abc',
      timestamp: '2026-11-21T08:00:00Z',
      fieldErrors: {},
      errors: [],
    }
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      const isSave = url.includes('/settings') && init?.method === 'PUT'
      const body = isSave ? problem : url.includes('/settings') ? settings : owner
      const status = isSave ? 422 : 200
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

    renderSettingsPage()

    const taxField = await screen.findByDisplayValue('0')
    await userEvent.clear(taxField)
    await userEvent.type(taxField, 'not a number')
    await userEvent.click(screen.getByRole('button', { name: 'Save the settings' }))

    await waitFor(() => {
      expect(screen.getByText('The settings were not saved.')).toBeInTheDocument()
    })
    expect(screen.getByText('tax.rate_percent must be a number.')).toBeInTheDocument()
  })
})
