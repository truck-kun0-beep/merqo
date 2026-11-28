import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import AppShell from '../components/AppShell'
import { ToastProvider } from '../components/ui'
import type { CurrentUser } from '../api/models'
import { MAPPED_PAYMENT_ERROR_CODES, resolveApiErrorMessage } from '../lib/posErrors'
import {
  recordOrderPaymentSchema,
  refundPayFormSchema,
  verifyPaymentFormSchema,
} from '../lib/validation'
import { SessionProvider } from '../session/SessionProvider'
import DemoConsolePage from './DemoConsolePage'
import HomePage from './HomePage'
import OrderDetailPage from './OrderDetailPage'
import PaymentsPage from './PaymentsPage'
import SettingsPage from './SettingsPage'

function createClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
  })
}

const fullPaymentOwner: CurrentUser = {
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
    'ORDER_VIEW',
    'PAYMENT_VIEW',
    'PAYMENT_RECORD',
    'PAYMENT_VERIFY',
    'REFUND_REQUEST',
    'REFUND_APPROVE',
    'REFUND_PAY',
    'COD_SETTLEMENT_MANAGE',
    'SETTINGS_VIEW',
  ].map((code) => ({
    code,
    module: 'PAYMENTS',
    description: code,
    scope: 'ALL',
    locationIds: [],
  })),
}

const limitedVerifierUser: CurrentUser = {
  ...fullPaymentOwner,
  id: 'u-fin',
  username: 'finance',
  accountType: 'STAFF',
  permissions: ['PAYMENT_VIEW', 'PAYMENT_VERIFY'].map((code) => ({
    code,
    module: 'PAYMENTS',
    description: code,
    scope: 'LOCATION',
    locationIds: ['wh-1'],
  })),
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

const baseOrderDetail = {
  id: 'ord-5',
  orderNumber: 'ORD-000501',
  channel: 'ONLINE',
  status: 'CONFIRMED',
  locationId: 'wh-1',
  customerId: 'cust-5',
  customerName: 'Nadia Rahman',
  phoneRaw: '01711223344',
  phoneNormalized: '01711223344',
  address: 'Gulshan 2, Dhaka',
  district: 'Dhaka',
  note: null,
  sourceLabel: 'Demo Webshop',
  externalOrderId: 'WEB-501',
  subtotal: '1000.00',
  discountTotal: '0.00',
  shippingFee: '60.00',
  total: '1060.00',
  paymentMode: 'ADVANCE_COD',
  advanceAmount: '300.00',
  codAmount: '760.00',
  claimedAdvanceMethod: 'BKASH',
  claimedAdvanceReference: 'BKCLM501',
  flags: {
    priceMismatch: false,
    invalidPhone: false,
    highRisk: false,
    cancellationRequested: false,
  },
  phoneHistory: { delivered: 1, cancelled: 0, declined: 0, refused: 0, returned: 0, total: 1 },
  riskTier: 'OK',
  followUpAt: null,
  cancellationRequestedAt: null,
  cancellationRequestNote: null,
  createdAt: '2026-11-28T06:00:00Z',
  confirmedAt: '2026-11-28T07:00:00Z',
  closedAt: null,
  lines: [],
  callLogs: [],
  statusHistory: [],
  readyForShipment: false,
}

describe('Phase 5 Payments, Refunds, COD & Finance UI', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('maps all 11 Phase 5 error codes and enforces PAN / CASH validation schemas', () => {
    expect(MAPPED_PAYMENT_ERROR_CODES).toHaveLength(11)
    for (const code of MAPPED_PAYMENT_ERROR_CODES) {
      const msg = resolveApiErrorMessage(code, 'Raw detail')
      expect(msg.trim().length).toBeGreaterThan(8)
      expect(msg).not.toBe(code)
    }

    expect(
      recordOrderPaymentSchema.safeParse({
        method: 'CARD',
        amount: '300',
        reference: '4111 2222 3333 4444',
      }).success,
    ).toBe(false)

    expect(
      verifyPaymentFormSchema.safeParse({
        decision: 'VERIFIED',
        confirmedAmount: '300',
        evidence: '',
      }).success,
    ).toBe(false)

    expect(
      verifyPaymentFormSchema.safeParse({
        decision: 'REJECTED',
        evidence: '',
      }).success,
    ).toBe(false)

    expect(
      refundPayFormSchema.safeParse({
        payoutMethod: 'CASH',
        payoutReference: 'REF123',
        note: 'Counter payout',
      }).success,
    ).toBe(false)

    expect(
      refundPayFormSchema.safeParse({
        payoutMethod: 'CASH',
        payoutReference: '',
        note: 'Handed cash back at counter',
      }).success,
    ).toBe(true)
  })

  it('prefills claimed advance, blocks PANs, and runs mock provider check on OrderDetailPage', async () => {
    let paymentsList = [
      {
        id: 'pay-pending-1',
        paymentNumber: 'PAY-000010',
        orderId: 'ord-5',
        orderNumber: 'ORD-000501',
        method: 'BKASH',
        amount: '300.00',
        reference: 'BKCLM501',
        source: 'WEBSITE_CLAIM',
        payerNote: null,
        recordedBy: 'u-owner',
        recordedByUsername: 'owner',
        recordedAt: '2026-11-28T06:05:00Z',
        status: 'PENDING_VERIFICATION',
        decision: null,
      },
    ]

    const fetchMock = stubFetch((url, init) => {
      if (url.includes('/auth/me')) return { body: fullPaymentOwner }
      if (url.includes('/auth/status')) {
        return {
          body: {
            setupRequired: false,
            demoMode: true,
            businessName: 'Merqo',
            paymentMode: 'mock',
            providerCheckEnabled: true,
          },
        }
      }
      if (url.includes('/orders/ord-5/payments/claim-prefill')) {
        return {
          body: {
            hasClaim: true,
            method: 'BKASH',
            reference: 'BKCLM501',
            claimedAmount: '300.00',
            remainingAllowed: '300.00',
          },
        }
      }
      if (url.includes('/orders/ord-5/payments') && init?.method === 'POST') {
        const body = JSON.parse(String(init.body))
        expect(body.fromClaim).toBe(true)
        const created = {
          ...paymentsList[0],
          id: 'pay-new',
          paymentNumber: 'PAY-000011',
          amount: body.amount,
          reference: body.reference,
        }
        paymentsList = [...paymentsList, created]
        return { status: 201, body: created }
      }
      if (url.includes('/payments/pay-pending-1/check') && init?.method === 'POST') {
        paymentsList = [
          {
            ...paymentsList[0],
            status: 'VERIFIED',
            decision: {
              id: 'dec-1',
              decision: 'VERIFIED',
              decidedBy: 'u-owner',
              decidedByUsername: 'owner',
              deciderKind: 'PROVIDER_MOCK',
              evidence: 'Mock match',
              providerReference: 'MOCK-BK-1',
              decidedAt: '2026-11-28T08:00:00Z',
            } as unknown as null,
          },
        ]
        return {
          body: {
            outcome: 'CONFIRMED',
            message: 'Verified via mock provider.',
            providerReference: 'MOCK-BK-1',
            payment: paymentsList[0],
          },
        }
      }
      if (url.includes('/orders/ord-5/payments')) {
        return {
          body: {
            orderId: 'ord-5',
            orderNumber: 'ORD-000501',
            orderStatus: 'CONFIRMED',
            paymentMode: 'ADVANCE_COD',
            total: '1060.00',
            advanceAmount: '300.00',
            codAmount: '760.00',
            claimedAdvance: {
              hasClaim: true,
              method: 'BKASH',
              reference: 'BKCLM501',
              claimedAmount: '300.00',
              remainingAllowed: '300.00',
            },
            position: {
              requiredPrepayment: '300.00',
              verifiedPaid: paymentsList[0].status === 'VERIFIED' ? '300.00' : '0.00',
              pendingPaid:
                paymentsList[0].status === 'PENDING_VERIFICATION' ? '300.00' : '0.00',
              rejectedPaid: '0.00',
              refundedPaid: '0.00',
              refundReserved: '0.00',
              netHeld: '0.00',
              refundable: '0.00',
              prepaymentSatisfied: paymentsList[0].status === 'VERIFIED',
              shortfall: paymentsList[0].status === 'VERIFIED' ? '0.00' : '300.00',
              codExpected: '760.00',
              codCollected: '0.00',
              codSettledGross: '0.00',
              codFeesDeducted: '0.00',
              codWithCourier: '0.00',
              codUncollected: '760.00',
              readyForShipment: paymentsList[0].status === 'VERIFIED',
              refundDue: false,
            },
            payments: paymentsList,
            refunds: [],
            codCollections: [],
          },
        }
      }
      if (url.includes('/orders/ord-5')) return { body: baseOrderDetail }
      return { body: {} }
    })

    renderWithApp(
      <Routes>
        <Route path="/orders/:id" element={<OrderDetailPage />} />
      </Routes>,
      '/orders/ord-5',
    )

    expect(await screen.findByText('Payments & COD position')).toBeInTheDocument()
    expect(screen.getByText('Awaiting prepayment')).toBeInTheDocument()
    expect(screen.getByText('PAY-000010')).toBeInTheDocument()

    // Record payment modal prefills claim & blocks full card number client-side
    await userEvent.click(screen.getByRole('button', { name: 'Record payment' }))
    const refInput = await screen.findByLabelText('Payment reference')
    expect(refInput).toHaveValue('BKCLM501')

    await userEvent.selectOptions(screen.getByLabelText('Payment method'), 'CARD')
    await userEvent.clear(refInput)
    await userEvent.type(refInput, '4111222233334444')
    await userEvent.click(screen.getByRole('button', { name: 'Save payment' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/full card number/i)
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))

    // Click Check with provider -> marks order Ready for shipment
    await userEvent.click(screen.getByRole('button', { name: 'Check with provider' }))
    await waitFor(() => {
      expect(
        fetchMock.mock.calls.some((c) => String(c[0]).includes('/payments/pay-pending-1/check')),
      ).toBe(true)
    })
    expect(await screen.findByText('Ready for shipment')).toBeInTheDocument()
  })

  it('renders PaymentsPage tabs (History, Pending, Refunds, COD live settlement math)', async () => {
    const fetchMock = stubFetch((url, init) => {
      if (url.includes('/auth/me')) return { body: fullPaymentOwner }
      if (url.includes('/auth/status')) {
        return {
          body: {
            setupRequired: false,
            demoMode: true,
            businessName: 'Merqo',
            paymentMode: 'manual',
            providerCheckEnabled: false,
          },
        }
      }
      if (url.includes('/locations')) {
        return {
          body: [
            {
              id: 'wh-1',
              code: 'WH',
              name: 'Central Warehouse',
              locationType: 'WAREHOUSE',
              address: null,
              phone: null,
              isActive: true,
              isOnlineFulfillment: true,
            },
          ],
        }
      }
      if (url.includes('/payments/history')) {
        return {
          body: {
            items: [
              {
                type: 'ORDER_PAYMENT',
                occurredAt: '2026-11-28T06:00:00Z',
                method: 'BKASH',
                amount: '500.00',
                reference: 'BK999111',
                documentNumber: 'PAY-000001',
                orderId: 'ord-5',
                saleId: null,
                settlementId: null,
                locationName: 'Central Warehouse',
                status: 'VERIFIED',
                recordedBy: 'owner',
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
      if (url.includes('/payments/summary')) {
        return {
          body: {
            totalCount: 1,
            totalAmount: '500.00',
            byType: [{ key: 'ORDER_PAYMENT', count: 1, totalAmount: '500.00' }],
            byMethod: [{ key: 'BKASH', count: 1, totalAmount: '500.00' }],
          },
        }
      }
      if (url.includes('/payments/pending')) {
        return {
          body: [
            {
              id: 'p-pend',
              paymentNumber: 'PAY-000002',
              orderId: 'ord-5',
              orderNumber: 'ORD-000501',
              orderStatus: 'PENDING_CONFIRMATION',
              customerName: 'Nadia Rahman',
              method: 'BKASH',
              amount: '300.00',
              reference: 'BKCLM501',
              source: 'WEBSITE_CLAIM',
              recordedBy: 'u-owner',
              recordedByUsername: 'owner',
              recordedAt: '2026-11-27T01:00:00Z',
              hoursPending: 36,
              overdue: true,
            },
          ],
        }
      }
      if (url.includes('/refunds/due')) {
        return {
          body: [
            {
              orderId: 'ord-cancel',
              orderNumber: 'ORD-000599',
              orderStatus: 'CANCELLED',
              customerName: 'Kamal Hasan',
              verifiedPaid: '800.00',
              refundedPaid: '0.00',
              refundable: '800.00',
              closedAt: '2026-11-28T05:00:00Z',
            },
          ],
        }
      }
      if (url.includes('/refunds')) {
        return { body: [] }
      }
      if (url.includes('/cod/outstanding')) {
        return {
          body: {
            gapThresholdDays: 7,
            courierTotals: [
              {
                courier: 'STEADFAST',
                orderCount: 1,
                expected: '760.00',
                collected: '760.00',
                settledGross: '0.00',
                withCourier: '760.00',
                uncollected: '0.00',
                gapCount: 1,
              },
            ],
            orders: [
              {
                orderId: 'ord-cod-1',
                orderNumber: 'ORD-000701',
                customerName: 'Tariq Aziz',
                courier: 'STEADFAST',
                earliestCollectionDate: '2026-11-18',
                expected: '760.00',
                collected: '760.00',
                settledGross: '0.00',
                withCourier: '760.00',
                uncollected: '0.00',
                gap: true,
              },
            ],
          },
        }
      }
      if (url.includes('/cod/settlements') && init?.method === 'POST') {
        return {
          status: 201,
          body: {
            id: 'set-1',
            settlementNumber: 'SET-000001',
            courier: 'Steadfast',
            reference: 'STF-2026-01',
            receivedOn: '2026-11-28',
            receiveMethod: 'BANK',
            amountReceived: '730.00',
            feesTotal: '20.00',
            sumNet: '740.00',
            difference: '-10.00',
            differenceKind: 'SHORT',
            note: 'Courier deducted BDT 10 penalty',
            recordedBy: 'u-owner',
            recordedByUsername: 'owner',
            recordedAt: '2026-11-28T12:00:00Z',
            lines: [],
          },
        }
      }
      if (url.includes('/cod/settlements')) return { body: [] }
      return { body: {} }
    })

    renderWithApp(
      <Routes>
        <Route path="/payments" element={<PaymentsPage />} />
      </Routes>,
      '/payments?tab=history',
    )

    expect(await screen.findByText('PAY-000001')).toBeInTheDocument()
    expect(screen.getByText('Matching transactions')).toBeInTheDocument()

    // Switch to To verify -> see overdue payment; manual mode hides Check button
    await userEvent.click(screen.getByRole('tab', { name: /To verify/i }))
    expect(await screen.findByText('PAY-000002')).toBeInTheDocument()
    expect(screen.getByText('Overdue')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Check' })).not.toBeInTheDocument()

    // Switch to Refunds -> see Refunds due row
    await userEvent.click(screen.getByRole('tab', { name: /Refunds/i }))
    expect(await screen.findByText('ORD-000599')).toBeInTheDocument()

    // Switch to COD & settlements -> open Settle, test integer-paisa live math & required note on SHORT
    await userEvent.click(screen.getByRole('tab', { name: /COD & settlements/i }))
    expect(await screen.findByText(/Gap > 7d/i)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Settle' }))

    const dialog = await screen.findByRole('dialog')
    await userEvent.type(within(dialog).getByLabelText('Settlement reference'), 'STF-2026-01')
    const feeInput = within(dialog).getByLabelText('Fee ORD-000701')
    await userEvent.clear(feeInput)
    await userEvent.type(feeInput, '20.00')
    const recvInput = within(dialog).getByLabelText('Amount received')
    await userEvent.clear(recvInput)
    await userEvent.type(recvInput, '730.00')

    expect(within(dialog).getByText('৳ 740.00')).toBeInTheDocument()
    expect(within(dialog).getByText(/SHORT \(৳ -10\.00\)/)).toBeInTheDocument()

    // Missing note rejected client-side
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save settlement' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(/explanatory note is required/i)

    await userEvent.type(within(dialog).getByLabelText('Settlement note'), 'Penalty deduction')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save settlement' }))

    await waitFor(() => {
      expect(
        fetchMock.mock.calls.some(
          (c) => String(c[0]).includes('/cod/settlements') && c[1]?.method === 'POST',
        ),
      ).toBe(true)
    })
  })

  it('hides unpermitted Payments nav items, shows Overview count, renders Settings keys & Demo simulate payment', async () => {
    const fetchMock = stubFetch((url, init) => {
      if (url.includes('/auth/me')) return { body: limitedVerifierUser }
      if (url.includes('/auth/status')) {
        return {
          body: {
            setupRequired: false,
            demoMode: true,
            businessName: 'Merqo',
            paymentMode: 'mock',
            providerCheckEnabled: true,
          },
        }
      }
      if (url.includes('/locations')) return { body: [] }
      if (url.includes('/payments/pending')) return { body: [{ id: '1' }, { id: '2' }] }
      if (url.includes('/settings')) {
        return {
          body: {
            groups: {
              BUSINESS: [],
              RECEIPT: [],
              TAX: [],
              INVENTORY: [],
              THRESHOLDS: [
                {
                  key: 'payments.require_separate_verifier',
                  label: 'Require separate payment verifier',
                  type: 'BOOLEAN',
                  value: 'false',
                  defaultValue: 'false',
                  readOnly: false,
                },
                {
                  key: 'payments.verification_overdue_hours',
                  label: 'Payment verification overdue hours',
                  type: 'INTEGER',
                  value: '24',
                  defaultValue: '24',
                  readOnly: false,
                },
              ],
            },
          },
        }
      }
      if (url.includes('/demo/simulator-info')) {
        return {
          body: {
            demoMode: true,
            websiteMode: 'mock',
            paymentMode: 'mock',
            demoClient: { id: 'dc-1', name: 'Demo', keyId: 'mk_demo', secret: 'ms_demo' },
            sampleSkus: [],
            presets: ['STANDARD_COD'],
          },
        }
      }
      if (url.includes('/demo/simulate-payment') && init?.method === 'POST') {
        return {
          status: 201,
          body: {
            id: 'mock-1',
            method: 'BKASH',
            reference: 'SIMBK9901',
            amount: '810.00',
            senderHint: '01700000099',
            variant: 'MATCH',
            alreadyClaimed: false,
          },
        }
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

    const { unmount } = renderWithApp(
      <Routes>
        <Route element={<AppShell />}>
          <Route index element={<HomePage />} />
        </Route>
      </Routes>,
      '/',
    )

    // Sidebar shows History & To verify, hides Refunds & COD & settlements for limitedVerifierUser
    expect(await screen.findByRole('link', { name: 'Payments & history' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'To verify' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'COD & settlements' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Refunds' })).not.toBeInTheDocument()

    // Action Center displays real pending count (2)
    const actionsSection = await screen.findByRole('region', { name: 'Action center' })
    expect(await within(actionsSection).findByText('2')).toBeInTheDocument()
    unmount()

    // SettingsPage renders Phase 5 threshold settings
    const s = renderWithApp(<SettingsPage />)
    expect(await screen.findByText('payments.require_separate_verifier')).toBeInTheDocument()
    expect(screen.getByText('payments.verification_overdue_hours')).toBeInTheDocument()
    s.unmount()

    // DemoConsolePage calls simulate-payment
    renderWithApp(<DemoConsolePage />)
    await userEvent.click(await screen.findByRole('button', { name: 'Simulate provider payment' }))
    expect(await screen.findByText(/Seeded BKASH · SIMBK9901/)).toBeInTheDocument()
    expect(
      fetchMock.mock.calls.some((c) => String(c[0]).includes('/demo/simulate-payment')),
    ).toBe(true)
  })
})
