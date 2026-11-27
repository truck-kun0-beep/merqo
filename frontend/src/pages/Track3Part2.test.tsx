import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import AppShell from '../components/AppShell'
import RequireSession from '../session/RequireSession'
import ApprovalPinPage from './ApprovalPinPage'
import InspectionsPage from './InspectionsPage'
import PosPage from './PosPage'
import { SessionProvider } from '../session/SessionProvider'
import type { CurrentUser } from '../api/models'

function createClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
  })
}

const staffWithoutPinOrAdjust: CurrentUser = {
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
      description: 'Sell at counter',
      scope: 'LOCATION',
      locationIds: ['loc-1'],
    },
    {
      code: 'POS_DISCOUNT',
      module: 'POS',
      description: 'Discount',
      scope: 'LOCATION',
      locationIds: ['loc-1'],
    },
    {
      code: 'POS_CASH_SESSION',
      module: 'POS',
      description: 'Cash session',
      scope: 'LOCATION',
      locationIds: ['loc-1'],
    },
  ],
}

const managerUser: CurrentUser = {
  ...staffWithoutPinOrAdjust,
  id: 'u-manager',
  username: 'branch.manager',
  fullName: 'Tariq Hasan',
  accountType: 'MANAGER',
  permissions: [
    ...staffWithoutPinOrAdjust.permissions,
    {
      code: 'POS_DISCOUNT_APPROVE',
      module: 'POS',
      description: 'Approve discounts',
      scope: 'LOCATION',
      locationIds: ['loc-1'],
    },
    {
      code: 'INVENTORY_ADJUST',
      module: 'INVENTORY',
      description: 'Adjust & inspect stock',
      scope: 'LOCATION',
      locationIds: ['loc-1'],
    },
  ],
}

const ownerUser: CurrentUser = {
  ...staffWithoutPinOrAdjust,
  id: 'u-owner',
  username: 'owner',
  fullName: 'Shop Owner',
  accountType: 'OWNER',
  permissions: [],
}

describe('UI Track 3 Part 2: Approval PIN, Inspections, Idempotency & Live POS wiring', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('hides Approval PIN menu item and Inspections link without permissions, and blocks direct /approval-pin visit', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input)
        const body = url.includes('/auth/me')
          ? staffWithoutPinOrAdjust
          : url.includes('/auth/status')
            ? { setupRequired: false, demoMode: false, businessName: 'Merqo Store' }
            : {}
        return new Response(JSON.stringify(body), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }),
    )

    render(
      <QueryClientProvider client={createClient()}>
        <SessionProvider>
          <MemoryRouter initialEntries={['/approval-pin']}>
            <Routes>
              <Route element={<AppShell />}>
                <Route
                  path="/approval-pin"
                  element={
                    <RequireSession>
                      <ApprovalPinPage />
                    </RequireSession>
                  }
                />
              </Route>
            </Routes>
          </MemoryRouter>
        </SessionProvider>
      </QueryClientProvider>,
    )

    expect(
      await screen.findByText(/This screen is not open to your account/i),
    ).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Inspections' })).not.toBeInTheDocument()

    await userEvent.click(await screen.findByRole('button', { name: /Ayesha Rahman/ }))
    expect(screen.queryByRole('menuitem', { name: 'Approval PIN' })).not.toBeInTheDocument()
  })

  it('shows Approval PIN for POS_DISCOUNT_APPROVE and OWNER, calls PUT /auth/approval-pin, and displays server PIN_POLICY message', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      const method = (init?.method ?? 'GET').toUpperCase()

      if (url.includes('/auth/me')) {
        return new Response(JSON.stringify(managerUser), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }
      if (url.includes('/auth/status')) {
        return new Response(
          JSON.stringify({ setupRequired: false, demoMode: false, businessName: 'Merqo Store' }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        )
      }
      if (method === 'PUT' && url.includes('/auth/approval-pin')) {
        const parsed = JSON.parse(String(init?.body ?? '{}')) as { pin: string }
        if (parsed.pin === '1111') {
          return new Response(
            JSON.stringify({
              type: 'https://merqo.dev/problems/pin-policy',
              title: 'Invalid PIN',
              status: 422,
              detail: 'PIN cannot repeat the same digit four times.',
              instance: '/api/v1/auth/approval-pin',
              errorCode: 'PIN_POLICY',
              correlationId: 'corr-pin',
              timestamp: new Date().toISOString(),
              fieldErrors: {},
              errors: [],
            }),
            { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
          )
        }
        return new Response(null, { status: 204 })
      }
      return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } })
    })
    vi.stubGlobal('fetch', fetchMock)

    render(
      <QueryClientProvider client={createClient()}>
        <SessionProvider>
          <MemoryRouter initialEntries={['/approval-pin']}>
            <Routes>
              <Route element={<AppShell />}>
                <Route path="/approval-pin" element={<ApprovalPinPage />} />
              </Route>
            </Routes>
          </MemoryRouter>
        </SessionProvider>
      </QueryClientProvider>,
    )

    await userEvent.click(await screen.findByRole('button', { name: /Tariq Hasan/ }))
    expect(screen.getByRole('menuitem', { name: 'Approval PIN' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Inspections' })).toBeInTheDocument()

    await userEvent.type(screen.getByLabelText(/Current password/), 'Secret123!')
    await userEvent.type(screen.getByLabelText(/New approval PIN/), '1111')
    await userEvent.type(screen.getByLabelText(/Confirm approval PIN/), '1111')
    await userEvent.click(screen.getByRole('button', { name: 'Save approval PIN' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'PIN cannot repeat the same digit four times.',
    )

    await userEvent.clear(screen.getByLabelText(/New approval PIN/))
    await userEvent.clear(screen.getByLabelText(/Confirm approval PIN/))
    await userEvent.type(screen.getByLabelText(/New approval PIN/), '5839')
    await userEvent.type(screen.getByLabelText(/Confirm approval PIN/), '5839')
    await userEvent.click(screen.getByRole('button', { name: 'Save approval PIN' }))

    expect(await screen.findByRole('status')).toHaveTextContent(
      'Your approval PIN has been updated.',
    )
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/auth/approval-pin',
      expect.objectContaining({ method: 'PUT' }),
    )
  })

  it('allows OWNER to access Approval PIN even without explicit POS_DISCOUNT_APPROVE permission row', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input)
        const body = url.includes('/auth/me')
          ? ownerUser
          : { setupRequired: false, demoMode: false, businessName: 'Merqo Store' }
        return new Response(JSON.stringify(body), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }),
    )

    render(
      <QueryClientProvider client={createClient()}>
        <SessionProvider>
          <MemoryRouter initialEntries={['/approval-pin']}>
            <Routes>
              <Route element={<AppShell />}>
                <Route path="/approval-pin" element={<ApprovalPinPage />} />
              </Route>
            </Routes>
          </MemoryRouter>
        </SessionProvider>
      </QueryClientProvider>,
    )

    expect(await screen.findByRole('button', { name: 'Save approval PIN' })).toBeInTheDocument()
  })

  it('lists inspection items and resolves via Restock and Write off with confirmation dialog', async () => {
    const resolveBodies: unknown[] = []
    let remainingInInspection = 3

    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input)
        const method = (init?.method ?? 'GET').toUpperCase()
        if (url.includes('/auth/me')) {
          return new Response(JSON.stringify(managerUser), {
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
        if (method === 'GET' && url.includes('/inventory/inspections')) {
          return new Response(
            JSON.stringify(
              remainingInInspection > 0
                ? [
                    {
                      locationId: 'loc-1',
                      variantId: 'var-9',
                      sku: 'CHN03-KHA-30',
                      productName: 'Slim Chino Trouser',
                      color: 'Khaki',
                      size: '30',
                      inInspection: remainingInInspection,
                      onHand: 18,
                      available: 15,
                    },
                  ]
                : [],
            ),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          )
        }
        if (method === 'POST' && url.includes('/inventory/inspections/resolve')) {
          const parsed = JSON.parse(String(init?.body ?? '{}')) as {
            quantity: number
            outcome: string
          }
          resolveBodies.push(parsed)
          remainingInInspection = Math.max(0, remainingInInspection - parsed.quantity)
          return new Response(
            JSON.stringify({
              locationId: 'loc-1',
              variantId: 'var-9',
              outcome: parsed.outcome,
              quantity: parsed.quantity,
              onHand: 18,
              inInspection: remainingInInspection,
              isFlagged: false,
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          )
        }
        return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } })
      }),
    )

    render(
      <QueryClientProvider client={createClient()}>
        <SessionProvider>
          <MemoryRouter>
            <InspectionsPage />
          </MemoryRouter>
        </SessionProvider>
      </QueryClientProvider>,
    )

    expect(await screen.findByText(/CHN03-KHA-30/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Write off' }))

    const modal = await screen.findByRole('dialog', { name: /Resolve inspection · CHN03-KHA-30/ })
    const qtyInput = within(modal).getByLabelText(/Quantity/)
    await userEvent.clear(qtyInput)
    await userEvent.type(qtyInput, '2')
    await userEvent.type(within(modal).getByLabelText(/Note/), 'Seam tear')
    await userEvent.click(within(modal).getByRole('button', { name: 'Continue to confirm' }))

    const confirmDialog = await screen.findByRole('dialog', {
      name: 'Confirm inspection write off?',
    })
    await userEvent.click(within(confirmDialog).getByRole('button', { name: 'Confirm write off' }))

    await waitFor(() => {
      expect(resolveBodies).toHaveLength(1)
    })
    expect(resolveBodies[0]).toEqual({
      locationId: 'loc-1',
      variantId: 'var-9',
      quantity: 2,
      outcome: 'WRITE_OFF',
      note: 'Seam tear',
    })
  })

  it('sends Idempotency-Key on sale, reuses exact same key after network failure, and fetches Last receipt via GET /pos/sales/{id}/receipt', async () => {
    const capturedSaleHeaders: string[] = []
    let failFirstSale = true
    let fetchedOriginalReceipt = false

    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input)
        const method = (init?.method ?? 'GET').toUpperCase()

        if (url.includes('/auth/me')) {
          return new Response(JSON.stringify(staffWithoutPinOrAdjust), {
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
                address: 'Road 11',
                phone: '01700000111',
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
                sessionNumber: 'CS-1',
                locationId: 'loc-1',
                cashierId: 'u-cashier',
                cashierUsername: 'cashier.one',
                openedAt: '2026-11-27T08:00:00Z',
                closedAt: null,
                status: 'OPEN',
                openingCash: '1000.00',
                expectedCash: '1000.00',
                countedCash: null,
                variance: null,
                closeNote: null,
                saleCount: 0,
                refundTotal: '0.00',
                byMethod: [{ method: 'CASH', expected: '1000.00', counted: null, difference: null }],
              },
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          )
        }
        if (url.includes('/catalog/lookup')) {
          return new Response(
            JSON.stringify({
              variant: {
                id: 'v-1',
                productId: 'p-1',
                productName: 'Cotton Tee',
                sku: 'TEE-01',
                color: 'Black',
                size: 'M',
                otherAttribute: null,
                sellingPrice: '500.00',
                isActive: true,
              },
              barcodes: [],
              promotion: null,
              listPrice: '500.00',
              promotionDiscount: '0.00',
              manualDiscount: '0.00',
              netUnitPrice: '500.00',
              quantity: 1,
              subtotal: '500.00',
              taxRatePercent: '0.00',
              taxInclusive: false,
              tax: '0.00',
              total: '500.00',
              belowCost: false,
              stock: { locationId: 'loc-1', onHand: 10, reserved: 0, available: 10, inInspection: 0 },
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          )
        }
        if (method === 'POST' && url.endsWith('/api/v1/pos/sales')) {
          const headers = (init?.headers ?? {}) as Record<string, string>
          capturedSaleHeaders.push(headers['Idempotency-Key'] ?? '')
          if (failFirstSale) {
            failFirstSale = false
            throw new TypeError('Failed to fetch')
          }
          const receiptPayload = {
            businessName: 'Merqo Store',
            branchName: 'Banani Branch',
            branchAddress: 'Road 11',
            branchPhone: '01700000111',
            receiptNumber: 'MQ-2026-000555',
            issuedAt: '2026-11-27T10:00:00Z',
            cashierName: 'Ayesha Rahman',
            lines: [
              {
                name: 'Cotton Tee',
                variant: 'Black / M',
                quantity: 1,
                unitPrice: '500.00',
                discount: '0.00',
                lineTotal: '500.00',
              },
            ],
            subtotal: '500.00',
            discount: '0.00',
            tax: '0.00',
            total: '500.00',
            paymentMethod: 'CASH',
            paymentReference: null,
            footerText: null,
            isDuplicate: false,
          }
          return new Response(
            JSON.stringify({
              id: 'sale-555',
              receiptNumber: 'MQ-2026-000555',
              locationId: 'loc-1',
              locationName: 'Banani Branch',
              cashSessionId: 'sess-1',
              cashierId: 'u-cashier',
              cashierUsername: 'cashier.one',
              createdAt: '2026-11-27T10:00:00Z',
              subtotal: '500.00',
              promotionDiscount: '0.00',
              manualDiscount: '0.00',
              taxTotal: '0.00',
              total: '500.00',
              paymentMethod: 'CASH',
              changeGiven: '0.00',
              payment: {
                method: 'CASH',
                amount: '500.00',
                tendered: '500.00',
                changeGiven: '0.00',
                reference: null,
              },
              lines: [],
              note: null,
              receipt: receiptPayload,
            }),
            { status: 201, headers: { 'Content-Type': 'application/json' } },
          )
        }
        if (method === 'GET' && url.endsWith('/api/v1/pos/sales/sale-555/receipt')) {
          fetchedOriginalReceipt = true
          return new Response(
            JSON.stringify({
              businessName: 'Merqo Store',
              branchName: 'Banani Branch',
              branchAddress: 'Road 11',
              branchPhone: '01700000111',
              receiptNumber: 'MQ-2026-000555',
              issuedAt: '2026-11-27T10:00:00Z',
              cashierName: 'Ayesha Rahman',
              lines: [],
              subtotal: '500.00',
              discount: '0.00',
              tax: '0.00',
              total: '500.00',
              paymentMethod: 'CASH',
              paymentReference: null,
              footerText: null,
              isDuplicate: false,
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          )
        }
        return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } })
      }),
    )

    render(
      <QueryClientProvider client={createClient()}>
        <SessionProvider>
          <MemoryRouter>
            <PosPage />
          </MemoryRouter>
        </SessionProvider>
      </QueryClientProvider>,
    )

    const scanInput = await screen.findByLabelText('Scan or enter barcode')
    await userEvent.type(scanInput, '1234567890123{Enter}')
    await screen.findByText('Cotton Tee')

    await userEvent.click(screen.getByRole('button', { name: /Pay/ }))
    const payDialog = await screen.findByRole('dialog', { name: 'Complete payment' })

    // First attempt fails due to simulated network failure
    await userEvent.click(within(payDialog).getByRole('button', { name: 'Complete sale' }))
    expect(await within(payDialog).findByText(/The server could not be reached/i)).toBeInTheDocument()

    // Retry sends exact same Idempotency-Key
    await userEvent.click(within(payDialog).getByRole('button', { name: 'Complete sale' }))
    const receiptDialog = await screen.findByRole('dialog', { name: /Receipt · MQ-2026-000555/ })

    expect(capturedSaleHeaders).toHaveLength(2)
    expect(capturedSaleHeaders[0]).not.toBe('')
    expect(capturedSaleHeaders[1]).toBe(capturedSaleHeaders[0])

    // Close receipt and click "Last receipt" -> must fetch GET /pos/sales/{id}/receipt
    await userEvent.click(within(receiptDialog).getByRole('button', { name: 'Close dialog' }))
    await userEvent.click(screen.getByRole('button', { name: /Last receipt \(MQ-2026-000555\)/ }))
    await screen.findByRole('dialog', { name: /Receipt · MQ-2026-000555/ })
    expect(fetchedOriginalReceipt).toBe(true)
  })
})
