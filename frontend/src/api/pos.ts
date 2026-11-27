import { apiRequest, toQueryString } from './client'
import type {
  ApprovalTokenResponse,
  CashSessionSummary,
  InspectionItem,
  InspectionOutcome,
  LookupResult,
  PageResponse,
  PaymentMethod,
  PosDiscountType,
  ReceiptData,
  ResolveInspectionResponse,
  ReturnDisposition,
  ReturnReason,
  SaleDetail,
  SaleReturnRecord,
  SaleSummary,
} from './models'

/**
 * Live Point of Sale, Approvals, Returns, and Inspection API wrappers.
 *
 * Always targets the real Phase 3 backend paths.
 */

export interface OpenCashSessionRequest {
  locationId: string
  openingCash: string
}

export interface CloseCashSessionRequest {
  countedCash: string
  countedBkash?: string
  countedNagad?: string
  countedCard?: string
  note?: string
}

export interface SetApprovalPinRequest {
  currentPassword: string
  pin: string
}

export interface CreateApprovalRequest {
  locationId: string
  pin: string
  maxDiscountPercent: string
}

export interface CreateSaleLineRequest {
  variantId: string
  quantity: number
  manualDiscountType?: PosDiscountType
  manualDiscountValue?: string
}

export interface CreateSalePaymentRequest {
  method: PaymentMethod
  tendered?: string
  reference?: string
}

export interface CreateSaleRequest {
  locationId: string
  lines: CreateSaleLineRequest[]
  billDiscountType?: PosDiscountType
  billDiscountValue?: string
  approvalToken?: string
  payment: CreateSalePaymentRequest
  idempotencyKey?: string
  note?: string
}

export interface SalesFilters {
  locationId?: string
  fromDate?: string
  toDate?: string
  q?: string
  page?: number
  size?: number
}

export interface CreateReturnLineRequest {
  saleLineId: string
  quantity: number
  disposition: ReturnDisposition
}

export interface CreateReturnRequest {
  reason: ReturnReason
  note?: string
  lines: CreateReturnLineRequest[]
  idempotencyKey?: string
}

export interface ResolveInspectionRequest {
  locationId: string
  variantId: string
  quantity: number
  outcome: InspectionOutcome
  note?: string
}

/**
 * Looks up a sellable POS item by barcode at a branch location.
 */
export function posLookupBarcode(barcode: string, locationId: string): Promise<LookupResult> {
  return apiRequest<LookupResult>(
    `/catalog/lookup${toQueryString({ barcode, scope: 'POS', locationId })}`,
  )
}

/**
 * Returns the open cash session for `locationId`, or `{ session: null }`.
 */
export function fetchCurrentCashSession(
  locationId: string,
): Promise<{ session: CashSessionSummary | null }> {
  return apiRequest<{ session: CashSessionSummary | null }>(
    `/pos/sessions/current${toQueryString({ locationId })}`,
  )
}

/**
 * Opens a cash session at the given branch with an opening float.
 */
export function openCashSession(request: OpenCashSessionRequest): Promise<CashSessionSummary> {
  return apiRequest<CashSessionSummary>('/pos/sessions', {
    method: 'POST',
    body: JSON.stringify(request),
  })
}

/**
 * Returns server-computed cash and tender summaries for a cash session.
 */
export function fetchCashSessionSummary(sessionId: string): Promise<CashSessionSummary> {
  return apiRequest<CashSessionSummary>(`/pos/sessions/${sessionId}/summary`)
}

/**
 * Closes a cash session with counted drawer & tender figures.
 */
export function closeCashSession(
  sessionId: string,
  request: CloseCashSessionRequest,
): Promise<CashSessionSummary> {
  return apiRequest<CashSessionSummary>(`/pos/sessions/${sessionId}/close`, {
    method: 'POST',
    body: JSON.stringify(request),
  })
}

/**
 * Sets or updates the caller's own discount approval PIN (requires `POS_DISCOUNT_APPROVE` or `OWNER`).
 */
export function setApprovalPin(request: SetApprovalPinRequest): Promise<void> {
  return apiRequest<void>('/auth/approval-pin', {
    method: 'PUT',
    body: JSON.stringify(request),
  })
}

/**
 * Exchanges a manager approval PIN for a single-use short-lived approval token.
 * Response never contains the approver's name or identity.
 */
export function requestDiscountApproval(
  request: CreateApprovalRequest,
): Promise<ApprovalTokenResponse> {
  return apiRequest<ApprovalTokenResponse>('/pos/approvals', {
    method: 'POST',
    body: JSON.stringify(request),
  })
}

/**
 * Completes a POS sale under the active branch cash session.
 */
export function createSale(
  request: CreateSaleRequest,
  idempotencyKey: string,
): Promise<SaleDetail> {
  return apiRequest<SaleDetail>('/pos/sales', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify({ ...request, idempotencyKey }),
  })
}

/**
 * Lists POS sales scoped to the caller's accessible branches.
 */
export function fetchSales(filters: SalesFilters = {}): Promise<PageResponse<SaleSummary>> {
  return apiRequest<PageResponse<SaleSummary>>(`/pos/sales${toQueryString(filters)}`)
}

/**
 * Reads the full detail of a single POS sale.
 */
export function fetchSale(id: string): Promise<SaleDetail> {
  return apiRequest<SaleDetail>(`/pos/sales/${id}`)
}

/**
 * Looks up a POS sale by its exact receipt number.
 */
export function fetchSaleByReceipt(receiptNumber: string): Promise<SaleDetail> {
  return apiRequest<SaleDetail>(`/pos/sales/lookup${toQueryString({ receiptNumber })}`)
}

/**
 * Fetches the receipt payload (`isDuplicate: false`) for a sale.
 */
export function fetchSaleReceipt(saleId: string): Promise<ReceiptData> {
  return apiRequest<ReceiptData>(`/pos/sales/${saleId}/receipt`)
}

/**
 * Marks a receipt reprint on the server and returns a `DUPLICATE` receipt payload.
 */
export function reprintSaleReceipt(saleId: string): Promise<ReceiptData> {
  return apiRequest<ReceiptData>(`/pos/sales/${saleId}/reprint`, {
    method: 'POST',
  })
}

/**
 * Processes a full or partial return with per-line stock/inspection disposition.
 */
export function createSaleReturn(
  saleId: string,
  request: CreateReturnRequest,
  idempotencyKey: string,
): Promise<SaleReturnRecord> {
  return apiRequest<SaleReturnRecord>(`/pos/sales/${saleId}/returns`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify({ ...request, idempotencyKey }),
  })
}

/**
 * Lists all returns recorded against a sale.
 */
export function listSaleReturns(saleId: string): Promise<SaleReturnRecord[]> {
  return apiRequest<SaleReturnRecord[]>(`/pos/sales/${saleId}/returns`)
}

/**
 * Lists inventory quantities currently held in inspection.
 */
export function fetchInspections(locationId?: string): Promise<InspectionItem[]> {
  return apiRequest<InspectionItem[]>(`/inventory/inspections${toQueryString({ locationId })}`)
}

/**
 * Resolves a quantity in inspection as `RESTOCK` (back to sellable stock) or `WRITE_OFF`.
 */
export function resolveInspection(
  request: ResolveInspectionRequest,
): Promise<ResolveInspectionResponse> {
  return apiRequest<ResolveInspectionResponse>('/inventory/inspections/resolve', {
    method: 'POST',
    body: JSON.stringify(request),
  })
}
