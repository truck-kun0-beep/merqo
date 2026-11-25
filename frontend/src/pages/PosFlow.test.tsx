import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import PosPage from './PosPage'
import SalesHistoryPage from './SalesHistoryPage'
import { SessionProvider } from '../session/SessionProvider'
import { installPosMocks, resetPosMockStore } from '../dev/posMocks'
import type { CurrentUser } from '../api/models'

function createClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
  })
}

const fullCashier: CurrentUser = {
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
      description: 'Apply discount',
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
    {
      code: 'POS_REPRINT',
      module: 'POS',
      description: 'Reprint receipt',
      scope: 'LOCATION',
      locationIds: ['loc-1'],
    },
    {
      code: 'POS_REFUND',
      module: 'POS',
      description: 'Refund sale',
      scope: 'LOCATION',
      locationIds: ['loc-1'],
    },
  ],
}

const restrictedCashier: CurrentUser = {
  ...fullCashier,
  permissions: [
    {
      code: 'POS_SELL',
      module: 'POS',
      description: 'Sell at counter',
      scope: 'LOCATION',
      locationIds: ['loc-1'],
    },
  ],
}

const locationsBody = [
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
]

function setupMockFetch(activeUser: CurrentUser = fullCashier) {
  resetPosMockStore()
  globalThis.fetch = vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    const body = url.includes('/auth/me')
      ? activeUser
      : url.includes('/locations')
        ? locationsBody
        : {}
    return new Response(JSON.stringify(body), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  }) as unknown as typeof fetch
  return installPosMocks()
}

describe('POS & Sales History flows', () => {
  let uninstallMocks: (() => void) | undefined

  beforeEach(() => {
    vi.restoreAllMocks()
  })

  afterEach(() => {
    uninstallMocks?.()
  })

  it('scans items into cart, increments quantity on repeat scan, and retains focus on unknown barcode', async () => {
    uninstallMocks = setupMockFetch()

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
    expect(scanInput).toHaveFocus()

    await userEvent.type(scanInput, '2000000000001{Enter}')
    expect(await screen.findByText('Cotton Crew T-Shirt')).toBeInTheDocument()
    expect(screen.getByLabelText('Quantity for TEE01-BLA-M')).toHaveValue(1)

    // Repeat scan increments quantity
    await userEvent.type(scanInput, '2000000000001{Enter}')
    await waitFor(() => {
      expect(screen.getByLabelText('Quantity for TEE01-BLA-M')).toHaveValue(2)
    })

    // Unknown barcode shows error and preserves focus
    await userEvent.type(scanInput, '0000000000000{Enter}')
    expect(await screen.findByRole('alert')).toHaveTextContent(/Not found.*0000000000000/)
    await waitFor(() => {
      expect(scanInput).toHaveFocus()
    })
  })

  it('validates single-method payments, enforces discount PIN flow, reconciles cash session, and marks reprint DUPLICATE', async () => {
    uninstallMocks = setupMockFetch()

    render(
      <QueryClientProvider client={createClient()}>
        <SessionProvider>
          <MemoryRouter>
            <PosPage />
          </MemoryRouter>
        </SessionProvider>
      </QueryClientProvider>,
    )

    // Open cash session with 5000.00 float
    await userEvent.click(await screen.findByRole('button', { name: 'Open cash session' }))
    const floatInput = await screen.findByLabelText(/Opening cash float/)
    await userEvent.clear(floatInput)
    await userEvent.type(floatInput, '5000.00')
    await userEvent.click(screen.getByRole('button', { name: 'Open session' }))
    expect(await screen.findByText('Open')).toBeInTheDocument()

    // Scan item (list 750.00 - promo 75.00 = 675.00 net, + 5% tax = 708.75)
    const scanInput = screen.getByLabelText('Scan or enter barcode')
    await userEvent.type(scanInput, '2000000000001{Enter}')
    await screen.findByText('Cotton Crew T-Shirt')

    // Apply 20% whole-bill discount (exceeds cashier's 10% limit)
    await userEvent.selectOptions(screen.getByLabelText('Bill discount type'), 'PERCENT')
    await userEvent.type(screen.getByLabelText('Bill discount value'), '20')
    expect(screen.getByText(/Exceeds your 10% limit/)).toBeInTheDocument()

    // Open Pay modal and test bKash TrxID validation + Card PAN rejection
    await userEvent.click(screen.getByRole('button', { name: /Pay/ }))
    const dialog = await screen.findByRole('dialog', { name: 'Complete payment' })

    await userEvent.click(within(dialog).getByRole('button', { name: 'bKash' }))
    await userEvent.click(within(dialog).getByRole('button', { name: 'Complete sale' }))
    expect(within(dialog).getByRole('alert')).toHaveTextContent(/TrxID is required/i)

    await userEvent.click(within(dialog).getByRole('button', { name: 'Card' }))
    await userEvent.type(
      within(dialog).getByLabelText(/Approval code or last 4 digits/),
      '4111111111111111',
    )
    await userEvent.click(within(dialog).getByRole('button', { name: 'Complete sale' }))
    expect(within(dialog).getByRole('alert')).toHaveTextContent(/Never enter a full card number/i)

    // Switch to Cash and complete (triggers Manager PIN modal)
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cash' }))
    await userEvent.click(within(dialog).getByRole('button', { name: 'Complete sale' }))

    const pinDialog = await screen.findByRole('dialog', { name: 'Manager discount approval' })
    await userEvent.type(within(pinDialog).getByLabelText(/Manager approval PIN/), '1234')
    await userEvent.click(within(pinDialog).getByRole('button', { name: 'Approve & complete' }))

    // Receipt appears with Code 128 SVG and DUPLICATE only after reprint
    const receiptDialog = await screen.findByRole('dialog', { name: /Receipt · MQ-/ })
    expect(within(receiptDialog).queryByText('DUPLICATE')).not.toBeInTheDocument()
    await userEvent.click(
      within(receiptDialog).getByRole('button', { name: 'Reprint (DUPLICATE)' }),
    )
    expect(await within(receiptDialog).findByText('DUPLICATE')).toBeInTheDocument()
    await userEvent.click(within(receiptDialog).getByRole('button', { name: 'Close dialog' }))

    // Close cash session and check reconciliation math (5000.00 + 567.00 = 5567.00)
    await userEvent.click(screen.getByRole('button', { name: 'Close session' }))
    const closeDialog = await screen.findByRole('dialog', { name: 'Close cash session' })
    const countedInput = within(closeDialog).getByLabelText(/Counted cash in drawer/)
    await userEvent.clear(countedInput)
    await userEvent.type(countedInput, '5500.00')
    expect(within(closeDialog).getByText('৳ 5,567.00', { exact: false })).toBeInTheDocument()
    expect(within(closeDialog).getByText('-৳ 67.00')).toBeInTheDocument()
  })

  it('enforces return quantity limits and handles ALREADY_RETURNED in Sales History', async () => {
    uninstallMocks = setupMockFetch()

    render(
      <QueryClientProvider client={createClient()}>
        <SessionProvider>
          <MemoryRouter>
            <SalesHistoryPage />
          </MemoryRouter>
        </SessionProvider>
      </QueryClientProvider>,
    )

    await userEvent.click(await screen.findByRole('button', { name: 'MQ-2026-000101' }))
    const drawer = await screen.findByRole('dialog', { name: 'Sale MQ-2026-000101' })
    await userEvent.click(within(drawer).getByRole('button', { name: 'Return items' }))

    // Attempt to return 5 when only 2 were sold
    const qtyInput = within(drawer).getByLabelText('Return quantity for TEE01-BLA-M')
    await userEvent.clear(qtyInput)
    await userEvent.type(qtyInput, '5')
    await userEvent.type(within(drawer).getByLabelText(/Reason/), 'Wrong size')
    await userEvent.click(within(drawer).getByRole('button', { name: 'Submit return' }))
    expect(within(drawer).getByRole('alert')).toHaveTextContent(/cannot exceed 2/)

    // Return full 2 -> sale becomes RETURNED
    await userEvent.clear(qtyInput)
    await userEvent.type(qtyInput, '2')
    await userEvent.click(within(drawer).getByRole('button', { name: 'Submit return' }))
    expect(await within(drawer).findByText(/Refund created:/)).toBeInTheDocument()
    expect(within(drawer).queryByRole('button', { name: 'Return items' })).not.toBeInTheDocument()
  })

  it('hides discount, cash session, reprint, and refund actions when permissions are absent', async () => {
    uninstallMocks = setupMockFetch(restrictedCashier)

    const { unmount } = render(
      <QueryClientProvider client={createClient()}>
        <SessionProvider>
          <MemoryRouter>
            <PosPage />
          </MemoryRouter>
        </SessionProvider>
      </QueryClientProvider>,
    )

    await screen.findByLabelText('Scan or enter barcode')
    expect(screen.queryByRole('button', { name: 'Open cash session' })).not.toBeInTheDocument()
    expect(screen.queryByText(/Whole-bill discount/)).not.toBeInTheDocument()
    unmount()

    render(
      <QueryClientProvider client={createClient()}>
        <SessionProvider>
          <MemoryRouter>
            <SalesHistoryPage />
          </MemoryRouter>
        </SessionProvider>
      </QueryClientProvider>,
    )

    await screen.findByRole('button', { name: 'MQ-2026-000101' })
    expect(screen.queryByRole('button', { name: 'Reprint' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'MQ-2026-000101' }))
    const drawer = await screen.findByRole('dialog', { name: 'Sale MQ-2026-000101' })
    expect(within(drawer).queryByRole('button', { name: 'Return items' })).not.toBeInTheDocument()
    expect(
      within(drawer).queryByRole('button', { name: 'Reprint (DUPLICATE)' }),
    ).not.toBeInTheDocument()
  })
})
