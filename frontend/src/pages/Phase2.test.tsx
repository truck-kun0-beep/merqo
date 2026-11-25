import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import ProductDetailPage from './ProductDetailPage'
import InventoryPage from './InventoryPage'
import PromotionsPage from './PromotionsPage'
import { StockCountDetailView } from './StockCountsPage'
import TransferDetailPage from './TransferDetailPage'
import CatalogImportPage from './CatalogImportPage'
import { SessionProvider } from '../session/SessionProvider'
import type { CurrentUser } from '../api/models'

function createClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
  })
}

function stubFetch(routes: Record<string, { status?: number; body: unknown }>) {
  const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const key = Object.keys(routes).find((candidate) => url.includes(candidate))
    const route = key ? routes[key] : { status: 200, body: [] }
    const status = route.status ?? 200
    return {
      ok: status >= 200 && status < 300,
      status,
      headers: new Headers(),
      url,
      text: async () => JSON.stringify(route.body),
      json: async () => route.body,
      _init: init,
    } as unknown as Response
  })
  vi.stubGlobal('fetch', fn)
  return fn
}

const staffWithoutCost: CurrentUser = {
  id: 'u-staff',
  username: 'staff1',
  fullName: 'Staff One',
  email: null,
  accountType: 'STAFF',
  mustChangePassword: false,
  isDemo: false,
  employeeCode: 'E-1',
  maxDiscountPercent: '0.00',
  locations: [{ id: 'loc-1' }],
  permissions: [
    {
      code: 'PRODUCT_VIEW',
      module: 'CATALOG',
      description: 'View products',
      scope: 'ALL',
      locationIds: [],
    },
    {
      code: 'INVENTORY_VIEW',
      module: 'INVENTORY',
      description: 'View stock',
      scope: 'LOCATION',
      locationIds: ['loc-1'],
    },
    {
      code: 'STOCK_COUNT',
      module: 'INVENTORY',
      description: 'Count stock',
      scope: 'LOCATION',
      locationIds: ['loc-1'],
    },
  ],
}

describe('Phase 2 screens', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('hides cost columns and edit actions when user lacks cost and edit permissions', async () => {
    stubFetch({
      '/auth/me': { body: staffWithoutCost },
      '/catalog/products/p-1': {
        body: {
          id: 'p-1',
          productCode: 'OT001',
          name: 'Cotton Tee',
          categoryId: null,
          brandId: null,
          description: null,
          taxRatePercent: null,
          isActive: true,
          variants: [
            {
              id: 'v-1',
              productId: 'p-1',
              sku: 'OT001-BLK-M',
              color: 'Black',
              size: 'M',
              otherAttribute: null,
              sellingPrice: '750.00',
              isActive: true,
            },
          ],
        },
      },
      '/catalog/variants/v-1': {
        body: {
          id: 'v-1',
          productId: 'p-1',
          sku: 'OT001-BLK-M',
          color: 'Black',
          size: 'M',
          otherAttribute: null,
          sellingPrice: '750.00',
          isActive: true,
          barcodes: [{ id: 'b-1', barcode: '2000000000001', kind: 'PRIMARY' }],
        },
      },
    })

    render(
      <QueryClientProvider client={createClient()}>
        <SessionProvider>
          <MemoryRouter initialEntries={['/catalog/products/p-1']}>
            <Routes>
              <Route path="/catalog/products/:id" element={<ProductDetailPage />} />
            </Routes>
          </MemoryRouter>
        </SessionProvider>
      </QueryClientProvider>,
    )

    expect(await screen.findByText('OT001-BLK-M')).toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: 'Cost' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Add variant' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Change price' })).not.toBeInTheDocument()

    // Also verify InventoryPage hides Value at cost when absent
    stubFetch({
      '/auth/me': { body: staffWithoutCost },
      '/locations': {
        body: [
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
        ],
      },
      '/inventory/stock/totals': { body: [{ locationId: 'loc-1', units: 25 }] },
      '/inventory/stock': {
        body: [
          {
            locationId: 'loc-1',
            variantId: 'v-1',
            sku: 'OT001-BLK-M',
            productName: 'Cotton Tee',
            color: 'Black',
            size: 'M',
            isActive: true,
            onHand: 25,
            reserved: 0,
            available: 25,
            inInspection: 0,
            reorderPoint: 5,
            isLowStock: false,
          },
        ],
      },
    })

    render(
      <QueryClientProvider client={createClient()}>
        <SessionProvider>
          <MemoryRouter initialEntries={['/inventory']}>
            <InventoryPage />
          </MemoryRouter>
        </SessionProvider>
      </QueryClientProvider>,
    )

    expect(await screen.findByText('25 units')).toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: 'Value at cost' })).not.toBeInTheDocument()
    expect(screen.queryByText(/Value:/)).not.toBeInTheDocument()
  })

  it('shows conflicting promotions and end-existing option in promotion preview', async () => {
    const owner: CurrentUser = {
      ...staffWithoutCost,
      accountType: 'OWNER',
      permissions: [
        {
          code: 'DISCOUNT_MANAGE',
          module: 'CATALOG',
          description: 'Manage discounts',
          scope: 'ALL',
          locationIds: [],
        },
      ],
    }

    stubFetch({
      '/auth/me': { body: owner },
      '/locations': {
        body: [
          {
            id: 'loc-1',
            code: 'BR1',
            name: 'Banani',
            locationType: 'BRANCH',
            address: null,
            phone: null,
            isActive: true,
            isOnlineFulfillment: false,
          },
        ],
      },
      '/promotions': {
        body: [
          {
            id: 'promo-1',
            name: 'Existing Winter Promo',
            discountType: 'PERCENT',
            value: '10',
            scope: 'POS',
            allLocations: true,
            startsAt: null,
            endsAt: null,
            isEnabled: true,
            status: 'ACTIVE',
            note: null,
            variantIds: ['v-1'],
            locationIds: [],
          },
        ],
      },
      '/catalog/products': {
        body: {
          items: [
            {
              id: 'p-1',
              productCode: 'OT001',
              name: 'Cotton Tee',
              categoryId: null,
              brandId: null,
              description: null,
              taxRatePercent: null,
              isActive: true,
              variants: [
                {
                  id: 'v-1',
                  productId: 'p-1',
                  sku: 'OT001-BLK-M',
                  color: 'Black',
                  size: 'M',
                  otherAttribute: null,
                  sellingPrice: '500.00',
                  isActive: true,
                },
              ],
            },
          ],
          page: 0,
          size: 200,
          totalItems: 1,
          totalPages: 1,
          hasNext: false,
        },
      },
      '/catalog/variants': {
        body: { items: [], page: 0, size: 200, totalItems: 0, totalPages: 0, hasNext: false },
      },
      '/catalog/categories': { body: [] },
      '/catalog/brands': { body: [] },
    })

    render(
      <QueryClientProvider client={createClient()}>
        <SessionProvider>
          <MemoryRouter>
            <PromotionsPage />
          </MemoryRouter>
        </SessionProvider>
      </QueryClientProvider>,
    )

    await userEvent.click(await screen.findByRole('button', { name: 'New promotion' }))
    await userEvent.type(screen.getByLabelText('Promotion name'), 'Flash Sale')
    await userEvent.click(await screen.findByLabelText(/Cotton Tee/))
    await userEvent.click(screen.getByRole('button', { name: 'Preview promotion' }))

    const conflictAlert = await screen.findByRole('alert')
    expect(conflictAlert).toHaveTextContent('Conflicting promotions detected')
    expect(within(conflictAlert).getByText('Existing Winter Promo')).toBeInTheDocument()
    expect(screen.getByLabelText(/End the conflicting promotions now/)).toBeInTheDocument()
  })

  it('never renders system quantities in DOM during blind IN_PROGRESS stock count', async () => {
    stubFetch({
      '/auth/me': { body: staffWithoutCost },
      '/inventory/counts/cnt-1': {
        body: {
          id: 'cnt-1',
          number: 'CNT-000001',
          locationId: 'loc-1',
          status: 'IN_PROGRESS',
          categoryId: null,
          isFlagged: false,
          createdBy: 'u-staff',
          createdAt: '2026-11-20T10:00:00Z',
          submittedAt: null,
          appliedAt: null,
          lines: [
            {
              variantId: 'v-1',
              sku: 'OT001-BLK-M',
              countedQuantity: null,
              // Even if somehow present on wire, blind UI must never render systemQuantityAtStart/variance
              systemQuantityAtStart: 42,
              variance: -42,
            },
          ],
        },
      },
    })

    const { container } = render(
      <QueryClientProvider client={createClient()}>
        <SessionProvider>
          <MemoryRouter>
            <StockCountDetailView
              countId="cnt-1"
              locationName={() => 'Banani Branch'}
              onBack={() => {}}
            />
          </MemoryRouter>
        </SessionProvider>
      </QueryClientProvider>,
    )

    expect(await screen.findByText('OT001-BLK-M')).toBeInTheDocument()
    expect(screen.getByText(/Blind count active/)).toBeInTheDocument()
    expect(container.textContent).not.toContain('42')
    expect(screen.queryByRole('columnheader', { name: 'System at start' })).not.toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: 'Variance' })).not.toBeInTheDocument()
  })

  it('shows transfer action buttons strictly by status and location permission', async () => {
    const dispatcher: CurrentUser = {
      ...staffWithoutCost,
      accountType: 'MANAGER',
      permissions: [
        {
          code: 'TRANSFER_VIEW',
          module: 'INVENTORY',
          description: 'View transfers',
          scope: 'LOCATION',
          locationIds: ['loc-src'],
        },
        {
          code: 'TRANSFER_DISPATCH',
          module: 'INVENTORY',
          description: 'Dispatch transfers',
          scope: 'LOCATION',
          locationIds: ['loc-src'],
        },
      ],
    }

    stubFetch({
      '/auth/me': { body: dispatcher },
      '/locations': {
        body: [
          {
            id: 'loc-src',
            code: 'WH',
            name: 'Main Warehouse',
            locationType: 'WAREHOUSE',
            address: null,
            phone: null,
            isActive: true,
            isOnlineFulfillment: true,
          },
          {
            id: 'loc-dst',
            code: 'BR1',
            name: 'Banani Branch',
            locationType: 'BRANCH',
            address: null,
            phone: null,
            isActive: true,
            isOnlineFulfillment: false,
          },
        ],
      },
      '/transfers/trf-1': {
        body: {
          id: 'trf-1',
          number: 'TRF-000001',
          sourceLocationId: 'loc-src',
          destinationLocationId: 'loc-dst',
          status: 'APPROVED',
          note: null,
          requestedAt: '2026-11-20T09:00:00Z',
          approvedAt: '2026-11-20T09:30:00Z',
          dispatchedAt: null,
          receivedAt: null,
          closedAt: null,
          lines: [
            {
              id: 'tl-1',
              variantId: 'v-1',
              requestedQuantity: 10,
              approvedQuantity: 8,
              dispatchedQuantity: 0,
              receivedQuantity: 0,
              shortageQuantity: 0,
              inTransit: 0,
            },
          ],
          history: [],
        },
      },
    })

    render(
      <QueryClientProvider client={createClient()}>
        <SessionProvider>
          <MemoryRouter initialEntries={['/transfers/trf-1']}>
            <Routes>
              <Route path="/transfers/:id" element={<TransferDetailPage />} />
            </Routes>
          </MemoryRouter>
        </SessionProvider>
      </QueryClientProvider>,
    )

    expect(await screen.findByText('Transfer TRF-000001')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Dispatch' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Receive' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument()
  })

  it('displays row-numbered errors returned by CSV import validation', async () => {
    const importer: CurrentUser = {
      ...staffWithoutCost,
      permissions: [
        {
          code: 'PRODUCT_CREATE',
          module: 'CATALOG',
          description: 'Create products',
          scope: 'ALL',
          locationIds: [],
        },
      ],
    }

    stubFetch({
      '/auth/me': { body: importer },
      '/locations': { body: [] },
      '/catalog/import/validate': {
        body: {
          isValid: false,
          totalRows: 2,
          toCreate: 1,
          toUpdate: 0,
          openingStockLines: 1,
          errors: [{ row: 3, message: 'The prefix MQ- is reserved for Merqo document numbers.' }],
        },
      },
    })

    const { container } = render(
      <QueryClientProvider client={createClient()}>
        <SessionProvider>
          <MemoryRouter>
            <CatalogImportPage />
          </MemoryRouter>
        </SessionProvider>
      </QueryClientProvider>,
    )

    const fileInput = container.querySelector('input[type="file"]') as HTMLInputElement
    const file = new File(['product_code,product_name\nP1,Tee'], 'catalog.csv', {
      type: 'text/csv',
    })
    await userEvent.upload(fileInput, file)
    await userEvent.click(screen.getByRole('button', { name: '1. Validate file' }))

    await waitFor(() => {
      expect(screen.getByText(/Row 3:/)).toBeInTheDocument()
    })
    expect(
      screen.getByText(/The prefix MQ- is reserved for Merqo document numbers\./),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '2. Confirm & import' })).toBeDisabled()
  })
})
