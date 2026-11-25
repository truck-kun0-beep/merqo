import { apiRequest } from './client'
import type {
  BelowCostWarning,
  CashSessionSummary,
  PageResponse,
  PaymentMethod,
  PosDiscountType,
  PosLookupResponse,
  ReceiptData,
  SaleDetail,
  SaleReturnRecord,
  SaleSummary,
} from './models'

/**
 * Point of Sale and Sales History API client.
 *
 * Uses the shared `apiRequest` client (same-origin `/api/v1`, CSRF header,
 * RFC 7807 `ApiError`).
 */

export interface OpenCashSessionRequest {
  locationId: string
  openingCash: string
}

export interface CloseCashSessionRequest {
  countedCash: string
  note?: string | null
}

export interface CreateSaleLineRequest {
  variantId: string
  quantity: number
  manualDiscountType?: PosDiscountType | null
  manualDiscountValue?: string | null
}

export interface CreateSalePaymentRequest {
  method: PaymentMethod
  amountGiven?: string | null
  trxId?: string | null
  cardReference?: string | null
}

export interface CreateSaleRequest {
  locationId: string
  cashSessionId: string
  lines: CreateSaleLineRequest[]
  billDiscountType?: PosDiscountType | null
  billDiscountValue?: string | null
  payment: CreateSalePaymentRequest
  managerPin?: string | null
  note?: string | null
}

export interface PriceCheckRequest {
  locationId: string
  lines: CreateSaleLineRequest[]
  billDiscountType?: PosDiscountType | null
  billDiscountValue?: string | null
}

export interface CreateReturnRequest {
  reason: string
  lines: Array<{
    saleLineId: string
    quantity: number
  }>
}

export interface SalesFilters {
  locationId?: string
  fromDate?: string
  toDate?: string
  q?: string
  page?: number
  size?: number
}

export function posLookupBarcode(
  barcode: string,
  locationId: string,
): Promise<PosLookupResponse> {
  return apiRequest<PosLookupResponse>(
    `/pos/lookup${toQueryString({ barcode, locationId })}`,
  )
}

export function fetchCurrentCashSession(
  locationId: string,
): Promise<{ session: CashSessionSummary | null }> {
  return apiRequest<{ session: CashSessionSummary | null }>(
    `/pos/sessions/current${toQueryString({ locationId })}`,
  )
}

export function openCashSession(request: OpenCashSessionRequest): Promise<CashSessionSummary> {
  return apiRequest<CashSessionSummary>('/pos/sessions/open', {
    method: 'POST',
    body: JSON.stringify(request),
  })
}

export function closeCashSession(
  sessionId: string,
  request: CloseCashSessionRequest,
): Promise<CashSessionSummary> {
  return apiRequest<CashSessionSummary>(`/pos/sessions/${sessionId}/close`, {
    method: 'POST',
    body: JSON.stringify(request),
  })
}

export function checkSalePrices(
  request: PriceCheckRequest,
): Promise<{ belowCostWarnings: BelowCostWarning[] }> {
  return apiRequest<{ belowCostWarnings: BelowCostWarning[] }>('/pos/price-check', {
    method: 'POST',
    body: JSON.stringify(request),
  })
}

export function createSale(request: CreateSaleRequest): Promise<SaleDetail> {
  return apiRequest<SaleDetail>('/sales', {
    method: 'POST',
    body: JSON.stringify(request),
  })
}

export function fetchSales(filters: SalesFilters = {}): Promise<PageResponse<SaleSummary>> {
  return apiRequest<PageResponse<SaleSummary>>(`/sales${toQueryString(filters)}`)
}

export function fetchSale(id: string): Promise<SaleDetail> {
  return apiRequest<SaleDetail>(`/sales/${id}`)
}

export function fetchSaleByReceipt(receiptNumber: string): Promise<SaleDetail> {
  return apiRequest<SaleDetail>(
    `/sales/by-receipt/${encodeURIComponent(receiptNumber.trim())}`,
  )
}

export function reprintSaleReceipt(id: string): Promise<ReceiptData> {
  return apiRequest<ReceiptData>(`/sales/${id}/reprint`, { method: 'POST' })
}

export function createSaleReturn(
  saleId: string,
  request: CreateReturnRequest,
): Promise<SaleReturnRecord> {
  return apiRequest<SaleReturnRecord>(`/sales/${saleId}/returns`, {
    method: 'POST',
    body: JSON.stringify(request),
  })
}

function toQueryString(filters: object): string {
  const parts: string[] = []
  for (const [key, value] of Object.entries(filters)) {
    if (value === undefined || value === '') {
      continue
    }
    parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
  }
  return parts.length ? `?${parts.join('&')}` : ''
}
