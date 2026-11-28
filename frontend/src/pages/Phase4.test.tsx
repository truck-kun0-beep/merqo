import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ToastProvider } from '../components/ui'
import type { CurrentUser } from '../api/models'
import { MAPPED_ORDER_ERROR_CODES, resolveApiErrorMessage } from '../lib/posErrors'
import { callLogFormSchema, normalizeBdPhone } from '../lib/validation'
import { SessionProvider } from '../session/SessionProvider'
import DemoConsolePage from './DemoConsolePage'
import HomePage from './HomePage'
import IntegrationClientsPage from './IntegrationClientsPage'
import ManualOrderPage from './ManualOrderPage'
import OrderDetailPage from './OrderDetailPage'
import OrdersPage from './OrdersPage'

function createClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
  })
}

const ownerUser: CurrentUser = {
  id: 'u-owner',
  username: 'owner',
  fullName: 'Demo Owner',
  email: null,
  accountType: 'OWNER',
  mustChangePassword: false,
  isDemo: true,
  employeeCode: null,
  maxDiscountPercent: '100.00',
  locations: [{ id: 'wh-1' }],
  permissions: [
    {
      code: 'ORDER_VIEW',
      module: 'ORDERS',
      description: 'View orders',
      scope: 'LOCATION',
      locationIds: ['wh-1'],
    },
    {
      code: 'ORDER_CALL',
      module: 'ORDERS',
      description: 'Call customer',
      scope: 'LOCATION',
      locationIds: ['wh-1'],
    },
    {
      code: 'ORDER_CONFIRM',
      module: 'ORDERS',
      description: 'Confirm order',
      scope: 'LOCATION',
      locationIds: ['wh-1'],
    },
    {
      code: 'ORDER_DECLINE',
      module: 'ORDERS',
      description: 'Decline order',
      scope: 'LOCATION',
      locationIds: ['wh-1'],
    },
    {
      code: 'ORDER_CANCEL',
      module: 'ORDERS',
      description: 'Cancel order',
      scope: 'LOCATION',
      locationIds: ['wh-1'],
    },
    {
      code: 'ORDER_CREATE_MANUAL',
      module: 'ORDERS',
      description: 'Create manual order',
      scope: 'LOCATION',
      locationIds: ['wh-1'],
    },
    {
      code: 'INVENTORY_VIEW',
      module: 'INVENTORY',
      description: 'View stock',
      scope: 'LOCATION',
      locationIds: ['wh-1'],
    },
  ],
}

function renderWithApp(ui: React.ReactNode, initialPath = '/') {
  return render(
    <QueryClientProvider client={createClient()}>
      <ToastProvider>
        <SessionProvider>
          <MemoryRouter initialEntries={[initialPath]}>{ui}</MemoryRouter>
        </SessionProvider>
      </ToastProvider>
    </QueryClientProvider>,
  )
}

function stubFetch(handler: (url: string, init?: RequestInit) => { status?: number; body: unknown }) {
  const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const res = handler(url, init)
    const status = res.status ?? 200
    return {
      ok: status >= 200 && status < 300,
      status,
      statusText: 'OK',
      headers: new Headers(),
      url,
      text: async () => JSON.stringify(res.body),
      json: async () => res.body,
    } as unknown as Response
  })
  vi.stubGlobal('fetch', fn)
  return fn
}

const sampleDetail: {
  [K in string]: unknown
} & {
  id: string
  orderNumber: string
  channel: string
  status: string
  locationId: string
  customerId: string
  customerName: string
  phoneRaw: string
  phoneNormalized: string | null
  address: string
  district: string | null
  note: string | null
  sourceLabel: string | null
  externalOrderId: string | null
  subtotal: string
  discountTotal: string
  shippingFee: string
  total: string
  paymentMode: string
  advanceAmount: string
  codAmount: string
  claimedAdvanceMethod: string | null
  claimedAdvanceReference: string | null
  flags: {
    priceMismatch: boolean
    invalidPhone: boolean
    highRisk: boolean
    cancellationRequested: boolean
  }
  phoneHistory: { delivered: number; cancelled: number; declined: number; refused: number; returned: number; total: number }
  riskTier: string
  followUpAt: string | null
  cancellationRequestedAt: string | null
  cancellationRequestNote: string | null
  createdAt: string
  confirmedAt: string | null
  closedAt: string | null
  lines: Array<Record<string, unknown>>
  callLogs: Array<Record<string, unknown>>
  statusHistory: Array<Record<string, unknown>>
} = {
  id: 'ord-1',
  orderNumber: 'ORD-000042',
  channel: 'ONLINE',
  status: 'PENDING_CONFIRMATION',
  locationId: 'wh-1',
  customerId: 'cust-1',
  customerName: 'Rafiq Ahmed',
  phoneRaw: '0171-BAD',
  phoneNormalized: null,
  address: 'House 12, Road 4, Dhanmondi',
  district: 'Dhaka',
  note: null,
  sourceLabel: 'Demo Webshop',
  externalOrderId: 'WEB-42',
  subtotal: '750.00',
  discountTotal: '0.00',
  shippingFee: '60.00',
  total: '810.00',
  paymentMode: 'ADVANCE_COD',
  advanceAmount: '200.00',
  codAmount: '610.00',
  claimedAdvanceMethod: 'BKASH',
  claimedAdvanceReference: 'BK-42',
  flags: {
    priceMismatch: true,
    invalidPhone: true,
    highRisk: true,
    cancellationRequested: false,
  },
  phoneHistory: { delivered: 1, cancelled: 0, declined: 0, refused: 2, returned: 0, total: 3 },
  riskTier: 'HIGH',
  followUpAt: null,
  cancellationRequestedAt: null,
  cancellationRequestNote: null,
  createdAt: '2026-11-28T06:00:00Z',
  confirmedAt: null,
  closedAt: null,
  lines: [
    {
      id: 'line-1',
      lineIndex: 1,
      variantId: 'var-1',
      productName: 'Everyday Tee',
      sku: 'TE-100-BLK',
      barcode: '8901',
      color: 'Black',
      size: null,
      quantity: 1,
      unitPrice: '700.00',
      lineTotal: '700.00',
      catalogOnlinePrice: '750.00',
      availability: { onHand: 10, reserved: 0, softHolds: 1, safetyStock: 2, available: 7 },
    },
  ],
  callLogs: [],
  statusHistory: [
    {
      id: 'h-1',
      fromStatus: null,
      toStatus: 'PENDING_CONFIRMATION',
      changedBy: null,
      actorLabel: 'integration:Demo Webshop',
      reason: 'ORDER_RECEIVED',
      note: null,
      createdAt: '2026-11-28T06:00:00Z',
    },
  ],
}

describe('Phase 4 Online Orders, Integration Keys & Demo Console UI', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('maps all 10 Phase 4 error codes and validates BD phone / call note rules', () => {
    expect(MAPPED_ORDER_ERROR_CODES).toHaveLength(10)
    for (const code of MAPPED_ORDER_ERROR_CODES) {
      const msg = resolveApiErrorMessage(code, 'Raw detail')
      expect(msg.trim().length).toBeGreaterThan(8)
      expect(msg).not.toBe(code)
    }

    expect(normalizeBdPhone('+880 1711-223344')).toBe('01711223344')
    expect(normalizeBdPhone('8801812345678')).toBe('01812345678')
    expect(normalizeBdPhone('01211223344')).toBeNull()

    expect(callLogFormSchema.safeParse({ result: 'NO_ANSWER', note: '' }).success).toBe(false)
    expect(callLogFormSchema.safeParse({ result: 'NO_ANSWER', note: 'Switched off' }).success).toBe(
      true,
    )
    expect(callLogFormSchema.safeParse({ result: 'ANSWERED', note: '' }).success).toBe(true)
  })

  it('renders OrdersPage with counts, attention flags, and high risk badge', async () => {
    stubFetch((url) => {
      if (url.includes('/auth/me')) return { body: ownerUser }
      if (url.includes('/orders/counts')) {
        return {
          body: { pendingConfirmation: 4, followUp: 2, needsAction: 6, confirmed: 3 },
        }
      }
      if (url.includes('/orders')) {
        return {
          body: {
            items: [
              {
                id: 'ord-1',
                orderNumber: 'ORD-000042',
                channel: 'ONLINE',
                status: 'PENDING_CONFIRMATION',
                locationId: 'wh-1',
                customerName: 'Rafiq Ahmed',
                phoneRaw: '0171-BAD',
                phoneNormalized: null,
                district: 'Dhaka',
                sourceLabel: 'Demo Webshop',
                externalOrderId: 'WEB-42',
                subtotal: '750.00',
                discountTotal: '0.00',
                shippingFee: '60.00',
                total: '810.00',
                paymentMode: 'ADVANCE_COD',
                advanceAmount: '200.00',
                codAmount: '610.00',
                flags: sampleDetail.flags,
                riskTier: 'HIGH',
                callAttempts: 1,
                followUpAt: null,
                cancellationRequestedAt: null,
                createdAt: '2026-11-28T06:00:00Z',
                confirmedAt: null,
                closedAt: null,
              },
            ],
            page: 0,
            size: 20,
            totalItems: 1,
            totalPages: 1,
            hasNext: false,
          },
        }
      }
      return { body: {} }
    })

    renderWithApp(<OrdersPage />)

    expect(await screen.findByText('ORD-000042')).toBeInTheDocument()
    const table = screen.getByRole('table')
    expect(within(table).getByText('High refusal risk')).toBeInTheDocument()
    expect(within(table).getByText('Price mismatch')).toBeInTheDocument()
    expect(within(table).getByText('Bad phone')).toBeInTheDocument()
  })

  it('handles call note validation, confirm, and cancel with reservation release banner on OrderDetailPage', async () => {
    let currentOrder: typeof sampleDetail = {
      ...sampleDetail,
      phoneNormalized: '01711223344',
      flags: { ...sampleDetail.flags, invalidPhone: false },
    }

    const fetchMock = stubFetch((url, init) => {
      if (url.includes('/auth/me')) return { body: ownerUser }
      if (url.includes('/orders/ord-1/confirm') && init?.method === 'POST') {
        currentOrder = { ...currentOrder, status: 'CONFIRMED', confirmedAt: '2026-11-28T07:00:00Z' }
        return { body: currentOrder }
      }
      if (url.includes('/orders/ord-1/cancel') && init?.method === 'POST') {
        currentOrder = { ...currentOrder, status: 'CANCELLED', closedAt: '2026-11-28T08:00:00Z' }
        return {
          body: {
            order: currentOrder,
            releasedReservation: true,
            customerContacted: true,
            advanceClaimed: true,
            advanceAmount: '200.00',
          },
        }
      }
      if (url.includes('/orders/ord-1')) return { body: currentOrder }
      return { body: {} }
    })

    renderWithApp(
      <Routes>
        <Route path="/orders/:id" element={<OrderDetailPage />} />
      </Routes>,
      '/orders/ord-1',
    )

    expect(await screen.findByText('Order ORD-000042')).toBeInTheDocument()

    // Selecting NO_ANSWER without note triggers inline Zod error without API call
    await userEvent.selectOptions(screen.getByLabelText('Call outcome'), 'NO_ANSWER')
    await userEvent.click(screen.getByRole('button', { name: 'Save call log' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/note is required/i)

    // Confirm order
    await userEvent.click(screen.getByRole('button', { name: 'Confirm order' }))
    const dialog = await screen.findByRole('dialog')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirm order' }))

    await waitFor(() => {
      expect(
        fetchMock.mock.calls.some((c) => String(c[0]).includes('/orders/ord-1/confirm')),
      ).toBe(true)
    })

    // Cancel confirmed order & verify released reservation + advance review banner
    await userEvent.click(await screen.findByRole('button', { name: 'Cancel order' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Confirm cancellation' }))

    expect(await screen.findByText(/Hard warehouse stock reservations were released/i)).toBeInTheDocument()
    expect(screen.getByText(/Claimed advance of/i)).toBeInTheDocument()
  })

  it('looks up customer risk history in ManualOrderPage and submits manual order', async () => {
    const fetchMock = stubFetch((url, init) => {
      if (url.includes('/auth/me')) return { body: ownerUser }
      if (url.includes('/catalog/variants')) {
        return {
          body: {
            items: [
              {
                id: 'var-1',
                productId: 'p-1',
                sku: 'TE-100-BLK',
                color: 'Black',
                size: null,
                otherAttribute: null,
                sellingPrice: '750.00',
                isActive: true,
              },
            ],
            page: 0,
            size: 100,
            totalItems: 1,
            totalPages: 1,
            hasNext: false,
          },
        }
      }
      if (url.includes('/customers/history')) {
        return {
          body: {
            phoneRaw: '01911100003',
            phoneNormalized: '01911100003',
            validPhone: true,
            customerName: 'Tariq Mahmud',
            history: { delivered: 0, cancelled: 0, declined: 0, refused: 2, returned: 0, total: 2 },
            riskTier: 'HIGH',
            recentOrders: [],
          },
        }
      }
      if (url.includes('/orders/manual') && init?.method === 'POST') {
        return { status: 201, body: { ...sampleDetail, id: 'ord-manual-1', channel: 'MANUAL' } }
      }
      return { body: {} }
    })

    renderWithApp(
      <Routes>
        <Route path="/orders/manual" element={<ManualOrderPage />} />
        <Route path="/orders/:id" element={<p>Order Created Landing</p>} />
      </Routes>,
      '/orders/manual',
    )

    const phoneInput = await screen.findByLabelText('Customer phone')
    await userEvent.type(phoneInput, '01911100003')
    expect(await screen.findByText('High refusal risk')).toBeInTheDocument()

    await userEvent.type(screen.getByLabelText('Customer name'), 'Tariq Mahmud')
    await userEvent.type(screen.getByLabelText('Delivery address'), 'Mirpur 10')
    await userEvent.selectOptions(screen.getByLabelText('Item 1'), 'var-1')
    await userEvent.click(screen.getByRole('button', { name: 'Create manual order' }))

    await waitFor(() => {
      expect(fetchMock.mock.calls.some((c) => String(c[0]).includes('/orders/manual'))).toBe(true)
    })
  })

  it('reveals new secret once on IntegrationClientsPage and fires simulator on DemoConsolePage', async () => {
    let clients: Array<Record<string, unknown>> = []
    stubFetch((url, init) => {
      if (url.includes('/auth/me')) return { body: ownerUser }
      if (url.includes('/integration/clients') && init?.method === 'POST') {
        const created = {
          id: 'cli-1',
          name: 'My Shop',
          keyId: 'mk_test_123',
          active: true,
          createdBy: 'u-owner',
          createdAt: '2026-11-28T06:00:00Z',
          lastUsedAt: null,
          rotatedAt: null,
        }
        clients = [created]
        return { status: 201, body: { client: created, secret: 'ms_one_time_secret_xyz' } }
      }
      if (url.includes('/integration/clients')) return { body: clients }
      if (url.includes('/demo/simulator-info')) {
        return {
          body: {
            demoMode: true,
            websiteMode: 'mock',
            demoClient: { id: 'dc-1', name: 'Demo Webshop', keyId: 'mk_demo_webshop', secret: 'ms_demo' },
            sampleSkus: [{ variantId: 'v-1', sku: 'TE-100-BLK', productName: 'Tee', onlinePrice: '750.00' }],
            presets: ['STANDARD_COD', 'UNKNOWN_SKU'],
          },
        }
      }
      if (url.includes('/demo/simulate-order') && init?.method === 'POST') {
        return {
          body: {
            externalOrderId: 'SIM-1234',
            signedHeaders: { 'X-Merqo-Key': 'mk_demo_webshop' },
            requestBody: {},
            accepted: true,
            httpStatus: 201,
            order: {
              orderId: 'ord-sim-1',
              orderNumber: 'ORD-000099',
              externalOrderId: 'SIM-1234',
              status: 'PENDING_CONFIRMATION',
              priceMismatch: false,
              invalidPhone: false,
              total: '810.00',
              createdAt: '2026-11-28T06:00:00Z',
            },
          },
        }
      }
      return { body: {} }
    })

    const { unmount } = renderWithApp(<IntegrationClientsPage />)
    await userEvent.click(await screen.findByRole('button', { name: 'Create API key' }))
    await userEvent.type(await screen.findByLabelText('Storefront name'), 'My Shop')
    await userEvent.click(screen.getByRole('button', { name: 'Create key' }))

    expect(await screen.findByTestId('revealed-secret')).toHaveTextContent('ms_one_time_secret_xyz')
    await userEvent.click(screen.getByRole('button', { name: 'Dismiss' }))
    expect(screen.queryByTestId('revealed-secret')).not.toBeInTheDocument()
    unmount()

    renderWithApp(<DemoConsolePage />)
    await userEvent.click(await screen.findByRole('button', { name: 'Send signed webhook' }))
    expect(await screen.findByText(/Open ORD-000099/)).toBeInTheDocument()
  })

  it('displays real Orders awaiting confirmation count on HomePage when user has ORDER_VIEW', async () => {
    stubFetch((url) => {
      if (url.includes('/auth/me')) return { body: ownerUser }
      if (url.includes('/locations')) return { body: [] }
      if (url.includes('/orders/counts')) {
        return { body: { pendingConfirmation: 5, followUp: 2, needsAction: 7, confirmed: 4 } }
      }
      return {
        body: {
          status: 'UP',
          service: 'merqo-backend',
          time: '2026-11-28T06:00:00Z',
          businessTime: '2026-11-28T12:00:00',
          database: { status: 'UP', probe: 1 },
        },
      }
    })

    renderWithApp(<HomePage />)
    const actionsSection = await screen.findByRole('region', { name: 'Action center' })
    expect(await within(actionsSection).findByText('7')).toBeInTheDocument()
  })
})
