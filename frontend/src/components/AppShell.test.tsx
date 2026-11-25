import { describe, expect, it, vi, beforeEach } from 'vitest'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import AppShell from './AppShell'
import { SessionProvider } from '../session/SessionProvider'
import type { CurrentUser } from '../api/models'

const staffUser: CurrentUser = {
  id: 'u-1',
  username: 'cashier.one',
  fullName: 'Ayesha Rahman',
  email: null,
  accountType: 'STAFF',
  mustChangePassword: false,
  isDemo: false,
  employeeCode: 'EMP-10',
  maxDiscountPercent: '5.00',
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

function renderShell(routes: Record<string, { status?: number; body: unknown }>) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    const match = Object.keys(routes).find((key) => url.includes(key))
    const entry = match ? routes[match] : { status: 200, body: {} }
    const status = entry.status ?? 200
    return {
      ok: status >= 200 && status < 300,
      status,
      headers: new Headers(),
      text: async () => JSON.stringify(entry.body),
      json: async () => entry.body,
    } as unknown as Response
  })
  vi.stubGlobal('fetch', fetchMock)

  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
  })

  return render(
    <QueryClientProvider client={client}>
      <SessionProvider>
        <MemoryRouter initialEntries={['/']}>
          <Routes>
            <Route element={<AppShell />}>
              <Route index element={<p>Dashboard content</p>} />
            </Route>
          </Routes>
        </MemoryRouter>
      </SessionProvider>
    </QueryClientProvider>,
  )
}

describe('AppShell', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('displays business name from /auth/status, demo banner when enabled, and permission-filtered nav', async () => {
    renderShell({
      '/auth/status': {
        body: { setupRequired: false, demoMode: true, businessName: 'Northline Outfitters' },
      },
      '/auth/me': { body: staffUser },
      '/health': {
        body: {
          status: 'UP',
          service: 'merqo',
          time: '2026-11-20T12:00:00Z',
          businessTime: '2026-11-20T18:00:00',
          database: { status: 'UP' },
        },
      },
    })

    expect(await screen.findByText('Northline Outfitters')).toBeInTheDocument()
    expect(screen.getByText(/DEMO MODE/)).toBeInTheDocument()
    expect(screen.getByText('Skip to content')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Locations' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'People' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Settings' })).not.toBeInTheDocument()

    // User menu opens with Change password and Sign out
    await userEvent.click(screen.getByRole('button', { name: /Ayesha Rahman/ }))
    expect(screen.getByRole('menuitem', { name: 'Change password' })).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: 'Sign out' })).toBeInTheDocument()
  })

  it('shows offline banner when browser loses connection', async () => {
    renderShell({
      '/auth/status': {
        body: { setupRequired: false, demoMode: false, businessName: 'Northline Outfitters' },
      },
      '/auth/me': { body: staffUser },
      '/health': {
        body: {
          status: 'UP',
          service: 'merqo',
          time: '2026-11-20T12:00:00Z',
          businessTime: '2026-11-20T18:00:00',
          database: { status: 'UP' },
        },
      },
    })

    await screen.findByText('Northline Outfitters')
    act(() => {
      window.dispatchEvent(new Event('offline'))
    })

    await waitFor(() => {
      expect(screen.getByText(/Offline: connection lost/i)).toBeInTheDocument()
    })
  })
})
