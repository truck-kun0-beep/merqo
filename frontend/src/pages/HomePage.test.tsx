import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import HomePage from '../pages/HomePage'
import { SessionProvider } from '../session/SessionProvider'
import type { CurrentUser } from '../api/models'
import type { HealthReport } from '../api/types'

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

const cashierUser: CurrentUser = {
  id: 'u-cashier',
  username: 'cashier.one',
  fullName: 'Ayesha Rahman',
  email: null,
  accountType: 'STAFF',
  mustChangePassword: false,
  isDemo: false,
  employeeCode: 'EMP-01',
  maxDiscountPercent: '10.00',
  locations: [{ id: 'loc-1' }],
  permissions: [
    {
      code: 'POS_SELL',
      module: 'POS',
      description: 'Sell',
      scope: 'LOCATION',
      locationIds: ['loc-1'],
    },
    {
      code: 'POS_CASH_SESSION',
      module: 'POS',
      description: 'Session',
      scope: 'LOCATION',
      locationIds: ['loc-1'],
    },
  ],
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

  it('renders four separated metric cards, branch comparison empty table, Action center, and Today at my branch live strip for cashiers', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input)
        if (url.includes('/auth/me')) {
          return new Response(JSON.stringify(cashierUser), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          })
        }
        if (url.includes('/locations')) {
          return new Response(
            JSON.stringify([
              {
                id: 'loc-1',
                code: 'BR1',
                name: 'Banani Branch',
                locationType: 'BRANCH',
                address: null,
                phone: null,
                isActive: true,
                isOnlineFulfillment: false,
              },
            ]),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          )
        }
        if (url.includes('/pos/sessions/current')) {
          return new Response(
            JSON.stringify({
              session: {
                id: 'sess-1',
                sessionNumber: 'CS-2026-0001',
                locationId: 'loc-1',
                cashierId: 'u-cashier',
                cashierUsername: 'cashier.one',
                openedAt: '2026-11-27T08:00:00Z',
                closedAt: null,
                status: 'OPEN',
                openingCash: '1000.00',
                expectedCash: '2500.00',
                countedCash: null,
                variance: null,
                closeNote: null,
                saleCount: 4,
                refundTotal: '0.00',
                byMethod: [],
              },
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          )
        }
        if (url.includes('/pos/sales')) {
          return new Response(
            JSON.stringify({
              items: [
                {
                  id: 'sale-9',
                  receiptNumber: 'MQ-2026-000777',
                  locationId: 'loc-1',
                  locationName: 'Banani Branch',
                  cashierUsername: 'cashier.one',
                  createdAt: '2026-11-27T11:15:00Z',
                  itemCount: 2,
                  total: '1500.00',
                  paymentMethod: 'CASH',
                },
              ],
              page: 0,
              size: 1,
              totalItems: 1,
              totalPages: 1,
              hasNext: false,
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          )
        }
        return new Response(JSON.stringify(healthyReport), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }),
    )

    render(
      <QueryClientProvider client={createTestQueryClient()}>
        <SessionProvider>
          <MemoryRouter>
            <HomePage />
          </MemoryRouter>
        </SessionProvider>
      </QueryClientProvider>,
    )

    const metricsSection = screen.getByRole('region', { name: 'Financial summary' })
    expect(within(metricsSection).getByText('Sales')).toBeInTheDocument()
    expect(within(metricsSection).getByText('Gross profit')).toBeInTheDocument()
    expect(within(metricsSection).getByText('Operating profit')).toBeInTheDocument()
    expect(within(metricsSection).getByText('Cash received')).toBeInTheDocument()

    expect(screen.getByRole('region', { name: 'Branch comparison' })).toBeInTheDocument()
    expect(screen.getByText('Branch reporting pending')).toBeInTheDocument()

    const actionsSection = screen.getByRole('region', { name: 'Action center' })
    expect(within(actionsSection).getByText('Low-stock alerts')).toBeInTheDocument()
    expect(within(actionsSection).getByText('Pending inspections')).toBeInTheDocument()
    expect(within(actionsSection).getByText('Overdue transfers')).toBeInTheDocument()

    const todaySection = await screen.findByRole('region', { name: 'Today at my branch' })
    expect(await within(todaySection).findByText('CS-2026-0001')).toBeInTheDocument()
    expect(await within(todaySection).findByText('MQ-2026-000777')).toBeInTheDocument()
    expect(await within(todaySection).findByText('৳ 1,500.00')).toBeInTheDocument()
  })
})
