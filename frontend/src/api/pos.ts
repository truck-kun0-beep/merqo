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
 * Point of Sale, Approvals, Inspections, and Sales History API client.
 *
 * Uses the shared `apiRequest` client (same-origin `/api/v1`, CSRF header,
 * RFC 7807 `ApiError`).
 */

/**
 * In Vitest or when `VITE_POS_MOCKS=1` is enabled, `src/dev/posMocks.ts` intercepts
 * the legacy Phase 2 mock paths (`/pos/lookup`, `/pos/sessions/open`, `/sales/*`).
 * Against the live Phase 3 backend, requests use the real `/catalog/lookup`,
 * `/pos/sessions`, and `/pos/sales/*` routes.
 */
const USE_MOCK_PATHS =
  import.meta.env.MODE === 'test' || import.meta.env.VITE_POS_MOCKS === '1'

export interface OpenCashSessionRequest {
  locationId: string
  openingCash: string
}

export interface CloseCashSessionRequest {
  countedCash: string
  countedBkash?: string | null
  countedNagad?: string | null
  countedCard?: string | null
  note?: string | null
}

export interface SetApprovalPinRequest {
  currentPassword: string
  pin: string
}

export interface ApprovalTokenRequest {
  locationId: string
  pin: string
  maxDiscountPercent: string
}

export interface ApprovalTokenResponse {
  approvalToken: string
  expiresAt: string
  maxDiscountPercent: string
}

export interface CreateSaleLineRequest {
  variantId: string
  quantity: number
  manualDiscountType?: PosDiscountType | null
  manualDiscountValue?: string | null
}

export interface CreateSalePaymentRequest {
  method: PaymentMethod
  tendered?: string | null
  amountGiven?: string | null
  reference?: string | null
  trxId?: string | null
  cardReference?: string | null
}

export interface CreateSaleRequest {
  locationId: string
  cashSessionId?: string
  lines: CreateSaleLineRequest[]
  billDiscountType?: PosDiscountType | null
  billDiscountValue?: string | null
  approvalToken?: string | null
  payment: CreateSalePaymentRequest
  managerPin?: string | null
  idempotencyKey?: string | null
  note?: string | null
}

export interface PriceCheckRequest {
  locationId: string
  lines: CreateSaleLineRequest[]
  billDiscountType?: PosDiscountType | null
  billDiscountValue?: string | null
}

export type ReturnDisposition = 'RESTOCK' | 'INSPECTION'

export interface CreateReturnRequest {
  reason: string
  note?: string | null
  idempotencyKey?: string | null
  lines: Array<{
    saleLineId: string
    quantity: number
    disposition?: ReturnDisposition
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

export interface InspectionItem {
  locationId: string
  variantId: string
  sku: string | null
  productName: string | null
  color: string | null
  size: string | null
  inInspection: number
  onHand: number
  available: number
}

export type InspectionOutcome = 'RESTOCK' | 'WRITE_OFF'

export interface ResolveInspectionRequest {
  locationId: string
  variantId: string
  quantity: number
  outcome: InspectionOutcome
  note?: string | null
}

export interface ResolveInspectionResponse {
  locationId: string
  variantId: string
  outcome: InspectionOutcome
  quantity: number
  onHand: number
  inInspection: number
  isFlagged: boolean
  valueAtCost?: string
}

export function posLookupBarcode(barcode: string, locationId: string): Promise<PosLookupResponse> {
  if (USE_MOCK_PATHS) {
    return apiRequest<PosLookupResponse>(`/pos/lookup${toQueryString({ barcode, locationId })}`)
  }
  return apiRequest<PosLookupResponse>(
    `/catalog/lookup${toQueryString({ barcode, scope: 'POS', locationId })}`,
  )
}

export function fetchCurrentCashSession(
  locationId?: string,
): Promise<{ session: CashSessionSummary | null }> {
  return apiRequest<{ session: CashSessionSummary | null }>(
    `/pos/sessions/current${toQueryString({ locationId })}`,
  )
}

export function openCashSession(request: OpenCashSessionRequest): Promise<CashSessionSummary> {
  const path = USE_MOCK_PATHS ? '/pos/sessions/open' : '/pos/sessions'
  return apiRequest<CashSessionSummary>(path, {
    method: 'POST',
    body: JSON.stringify(request),
  })
}

export function fetchCashSessionSummary(sessionId: string): Promise<CashSessionSummary> {
  return apiRequest<CashSessionSummary>(`/pos/sessions/${sessionId}/summary`)
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

export function setApprovalPin(request: SetApprovalPinRequest): Promise<void> {
  return apiRequest<void>('/auth/approval-pin', {
    method: 'PUT',
    body: JSON.stringify(request),
  })
}

export function requestDiscountApproval(
  request: ApprovalTokenRequest,
): Promise<ApprovalTokenResponse> {
  return apiRequest<ApprovalTokenResponse>('/pos/approvals', {
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

export function createSale(
  request: CreateSaleRequest,
  idempotencyKey?: string,
): Promise<SaleDetail> {
  const path = USE_MOCK_PATHS ? '/sales' : '/pos/sales'
  const key = idempotencyKey ?? request.idempotencyKey ?? undefined
  return apiRequest<SaleDetail>(path, {
    method: 'POST',
    headers: key ? { 'Idempotency-Key': key } : undefined,
    body: JSON.stringify(request),
  })
}

export function fetchSales(filters: SalesFilters = {}): Promise<PageResponse<SaleSummary>> {
  const basePath = USE_MOCK_PATHS ? '/sales' : '/pos/sales'
  return apiRequest<PageResponse<SaleSummary>>(`${basePath}${toQueryString(filters)}`)
}

export function fetchSale(id: string): Promise<SaleDetail> {
  const basePath = USE_MOCK_PATHS ? '/sales' : '/pos/sales'
  return apiRequest<SaleDetail>(`${basePath}/${id}`)
}

export function fetchSaleByReceipt(receiptNumber: string): Promise<SaleDetail> {
  if (USE_MOCK_PATHS) {
    return apiRequest<SaleDetail>(
      `/sales/by-receipt/${encodeURIComponent(receiptNumber.trim())}`,
    )
  }
  return apiRequest<SaleDetail>(
    `/pos/sales/lookup${toQueryString({ receiptNumber: receiptNumber.trim() })}`,
  )
}

export function fetchSaleReceipt(id: string): Promise<ReceiptData> {
  return apiRequest<ReceiptData>(`/pos/sales/${id}/receipt`)
}

export function reprintSaleReceipt(id: string): Promise<ReceiptData> {
  const basePath = USE_MOCK_PATHS ? '/sales' : '/pos/sales'
  return apiRequest<ReceiptData>(`${basePath}/${id}/reprint`, { method: 'POST' })
}

export function createSaleReturn(
  saleId: string,
  request: CreateReturnRequest,
  idempotencyKey?: string,
): Promise<SaleReturnRecord> {
  const basePath = USE_MOCK_PATHS ? '/sales' : '/pos/sales'
  const key = idempotencyKey ?? request.idempotencyKey ?? undefined
  return apiRequest<SaleReturnRecord>(`${basePath}/${saleId}/returns`, {
    method: 'POST',
    headers: key ? { 'Idempotency-Key': key } : undefined,
    body: JSON.stringify(request),
  })
}

export function listSaleReturns(saleId: string): Promise<SaleReturnRecord[]> {
  return apiRequest<SaleReturnRecord[]>(`/pos/sales/${saleId}/returns`)
}

export function listInspections(locationId?: string): Promise<InspectionItem[]> {
  return apiRequest<InspectionItem[]>(`/inventory/inspections${toQueryString({ locationId })}`)
}

export function resolveInspection(
  request: ResolveInspectionRequest,
): Promise<ResolveInspectionResponse> {
  return apiRequest<ResolveInspectionResponse>('/inventory/inspections/resolve', {
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
