import { apiRequest, toQueryString } from './client'
import type { PageResponse } from './models'
import type { OrderPaymentPositionView } from './payments'

export type OrderChannel = 'ONLINE' | 'MANUAL'

export type OrderStatus =
  | 'PENDING_CONFIRMATION'
  | 'FOLLOW_UP'
  | 'CONFIRMED'
  | 'DECLINED'
  | 'CANCELLED'
  | 'READY_TO_SHIP'
  | 'SHIPPED'
  | 'DELIVERED'
  | 'REFUSED'
  | 'RETURNED'

export type OrderPaymentMode = 'FULL_PREPAID' | 'COD' | 'ADVANCE_COD'

export type ClaimedAdvanceMethod = 'BKASH' | 'NAGAD' | 'CARD' | 'BANK'

export type CallResult = 'ANSWERED' | 'NO_ANSWER' | 'BUSY' | 'WRONG_NUMBER' | 'CALL_BACK_REQUESTED'

export type DeclineReason =
  | 'CUSTOMER_UNREACHABLE'
  | 'CUSTOMER_DECLINED'
  | 'OUT_OF_STOCK'
  | 'INVALID_ADDRESS'
  | 'SUSPECTED_FAKE'
  | 'OTHER'

export type CancelReason =
  | 'CUSTOMER_REQUESTED'
  | 'STOCK_PROBLEM'
  | 'ADDRESS_PROBLEM'
  | 'OTHER'

export type RiskTier = 'NEW' | 'OK' | 'WATCH' | 'HIGH'

export interface OrderFlagsView {
  priceMismatch: boolean
  invalidPhone: boolean
  highRisk: boolean
  cancellationRequested: boolean
}

export interface PhoneHistory {
  delivered: number
  cancelled: number
  declined: number
  refused: number
  returned: number
  total: number
}

export interface OrderSummaryView {
  id: string
  orderNumber: string
  channel: OrderChannel
  status: OrderStatus
  locationId: string
  customerName: string
  phoneRaw: string
  phoneNormalized: string | null
  district: string | null
  sourceLabel: string | null
  externalOrderId: string | null
  subtotal: string
  discountTotal: string
  shippingFee: string
  total: string
  paymentMode: OrderPaymentMode
  advanceAmount: string
  codAmount: string
  flags: OrderFlagsView
  riskTier: RiskTier
  callAttempts: number
  followUpAt: string | null
  cancellationRequestedAt: string | null
  createdAt: string
  confirmedAt: string | null
  closedAt: string | null
}

export interface StockSnapshotView {
  onHand: number
  reserved: number
  softHolds: number
  safetyStock: number
  available: number
}

export interface OrderLineView {
  id: string
  lineIndex: number
  variantId: string
  productName: string
  sku: string
  barcode: string | null
  color: string | null
  size: string | null
  quantity: number
  unitPrice: string
  lineTotal: string
  catalogOnlinePrice: string | null
  availability: StockSnapshotView | null
}

export interface OrderCallLogView {
  id: string
  attemptNumber: number
  result: CallResult
  note: string | null
  followUpAt: string | null
  calledBy: string
  calledByUsername: string
  calledAt: string
}

export interface OrderStatusHistoryView {
  id: string
  fromStatus: OrderStatus | null
  toStatus: OrderStatus
  changedBy: string | null
  actorLabel: string
  reason: string | null
  note: string | null
  createdAt: string
}

export interface OrderDetailView {
  id: string
  orderNumber: string
  channel: OrderChannel
  status: OrderStatus
  locationId: string
  customerId: string | null
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
  paymentMode: OrderPaymentMode
  advanceAmount: string
  codAmount: string
  claimedAdvanceMethod: ClaimedAdvanceMethod | null
  claimedAdvanceReference: string | null
  flags: OrderFlagsView
  phoneHistory: PhoneHistory
  riskTier: RiskTier
  followUpAt: string | null
  cancellationRequestedAt: string | null
  cancellationRequestNote: string | null
  createdAt: string
  confirmedAt: string | null
  closedAt: string | null
  lines: OrderLineView[]
  callLogs: OrderCallLogView[]
  statusHistory: OrderStatusHistoryView[]
  paymentPosition?: OrderPaymentPositionView
  readyForShipment?: boolean
}

export interface OrderCancelResponse {
  order: OrderDetailView
  releasedReservation: boolean
  customerContacted: boolean
  advanceClaimed: boolean
  advanceAmount: string
}

export interface OrderCountsView {
  pendingConfirmation: number
  followUp: number
  needsAction: number
  confirmed: number
}

export interface WarehouseAvailabilityRow {
  variantId: string
  sku: string
  productName: string
  color: string | null
  size: string | null
  onHand: number
  reserved: number
  softHolds: number
  safetyStock: number
  available: number
}

export interface WarehouseAvailabilityResponse {
  locationId: string
  locationCode: string
  items: WarehouseAvailabilityRow[]
}

export interface CustomerOrderRefView {
  id: string
  orderNumber: string
  channel: OrderChannel
  status: OrderStatus
  total: string
  createdAt: string
}

export interface CustomerHistoryResponse {
  phoneRaw: string
  phoneNormalized: string | null
  validPhone: boolean
  customerName: string | null
  history: PhoneHistory
  riskTier: RiskTier
  recentOrders: CustomerOrderRefView[]
}

export interface IntegrationClientSummary {
  id: string
  name: string
  keyId: string
  active: boolean
  createdBy: string | null
  createdAt: string
  lastUsedAt: string | null
  rotatedAt: string | null
}

export interface IntegrationClientSecretResponse {
  client: IntegrationClientSummary
  secret: string
}

export interface SimulatorSampleSku {
  variantId: string
  sku: string
  productName: string
  onlinePrice: string
}

export interface SimulatorInfoResponse {
  demoMode: boolean
  websiteMode: string
  paymentMode?: string
  demoClient: {
    id: string
    name: string
    keyId: string
    secret: string
  }
  sampleSkus: SimulatorSampleSku[]
  presets: string[]
}

export interface SimulateOrderResponse {
  externalOrderId: string
  signedHeaders: Record<string, string>
  requestBody: Record<string, unknown>
  accepted: boolean
  httpStatus: number
  idempotentReplay?: boolean
  order?: {
    orderId: string
    orderNumber: string
    externalOrderId: string
    status: OrderStatus
    priceMismatch: boolean
    invalidPhone: boolean
    total: string
    createdAt: string
  }
  errorCode?: string
  errorMessage?: string
}

export interface OrdersFilter {
  status?: OrderStatus | ''
  channel?: OrderChannel | ''
  needsAction?: boolean
  priceMismatch?: boolean
  invalidPhone?: boolean
  cancellationRequested?: boolean
  q?: string
  fromDate?: string
  toDate?: string
  page?: number
  size?: number
}

export function fetchOrders(filters: OrdersFilter = {}): Promise<PageResponse<OrderSummaryView>> {
  return apiRequest<PageResponse<OrderSummaryView>>(
    `/orders${toQueryString({
      status: filters.status || undefined,
      channel: filters.channel || undefined,
      needsAction: filters.needsAction ? 'true' : undefined,
      priceMismatch: filters.priceMismatch ? 'true' : undefined,
      invalidPhone: filters.invalidPhone ? 'true' : undefined,
      cancellationRequested: filters.cancellationRequested ? 'true' : undefined,
      q: filters.q?.trim() || undefined,
      fromDate: filters.fromDate || undefined,
      toDate: filters.toDate || undefined,
      page: filters.page ?? 0,
      size: filters.size ?? 20,
    })}`,
  )
}

export function fetchOrderCounts(): Promise<OrderCountsView> {
  return apiRequest<OrderCountsView>('/orders/counts')
}

export function fetchOrderDetail(id: string): Promise<OrderDetailView> {
  return apiRequest<OrderDetailView>(`/orders/${id}`)
}

export function logOrderCall(
  id: string,
  body: { result: CallResult; note?: string; followUpAt?: string | null },
): Promise<OrderDetailView> {
  return apiRequest<OrderDetailView>(`/orders/${id}/calls`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function editOrderCustomer(
  id: string,
  body: {
    customerName: string
    phone: string
    address: string
    district?: string | null
    note?: string | null
  },
): Promise<OrderDetailView> {
  return apiRequest<OrderDetailView>(`/orders/${id}/customer`, {
    method: 'PUT',
    body: JSON.stringify(body),
  })
}

export function confirmOrder(id: string, note?: string): Promise<OrderDetailView> {
  return apiRequest<OrderDetailView>(`/orders/${id}/confirm`, {
    method: 'POST',
    body: JSON.stringify({ note: note || null }),
  })
}

export function declineOrder(
  id: string,
  body: { reason: DeclineReason; note?: string },
): Promise<OrderDetailView> {
  return apiRequest<OrderDetailView>(`/orders/${id}/decline`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function cancelOrder(
  id: string,
  body: { reason: CancelReason; note?: string },
): Promise<OrderCancelResponse> {
  return apiRequest<OrderCancelResponse>(`/orders/${id}/cancel`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function requestOrderCancellation(id: string, note: string): Promise<OrderDetailView> {
  return apiRequest<OrderDetailView>(`/orders/${id}/cancellation-request`, {
    method: 'POST',
    body: JSON.stringify({ note }),
  })
}

export interface CreateManualOrderRequest {
  customerName: string
  phone: string
  address: string
  district?: string | null
  note?: string | null
  lines: Array<{ variantId?: string; sku?: string; quantity: number }>
  shippingFee?: string
  discountTotal?: string
  paymentMode: OrderPaymentMode
  advanceAmount?: string
  claimedAdvanceMethod?: ClaimedAdvanceMethod | null
  claimedAdvanceReference?: string | null
  confirmNow: boolean
}

export function createManualOrder(body: CreateManualOrderRequest): Promise<OrderDetailView> {
  return apiRequest<OrderDetailView>('/orders/manual', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function fetchWarehouseAvailability(params: {
  q?: string
  sku?: string[]
} = {}): Promise<WarehouseAvailabilityResponse> {
  return apiRequest<WarehouseAvailabilityResponse>(
    `/orders/availability${toQueryString({
      q: params.q?.trim() || undefined,
      sku: params.sku && params.sku.length > 0 ? params.sku.join(',') : undefined,
    })}`,
  )
}

export function fetchCustomerHistory(phone: string): Promise<CustomerHistoryResponse> {
  return apiRequest<CustomerHistoryResponse>(`/customers/history${toQueryString({ phone })}`)
}

export function fetchIntegrationClients(): Promise<IntegrationClientSummary[]> {
  return apiRequest<IntegrationClientSummary[]>('/integration/clients')
}

export function createIntegrationClient(name: string): Promise<IntegrationClientSecretResponse> {
  return apiRequest<IntegrationClientSecretResponse>('/integration/clients', {
    method: 'POST',
    body: JSON.stringify({ name }),
  })
}

export function rotateIntegrationClient(id: string): Promise<IntegrationClientSecretResponse> {
  return apiRequest<IntegrationClientSecretResponse>(`/integration/clients/${id}/rotate`, {
    method: 'POST',
  })
}

export function disableIntegrationClient(id: string): Promise<IntegrationClientSummary> {
  return apiRequest<IntegrationClientSummary>(`/integration/clients/${id}/disable`, {
    method: 'POST',
  })
}

export function enableIntegrationClient(id: string): Promise<IntegrationClientSummary> {
  return apiRequest<IntegrationClientSummary>(`/integration/clients/${id}/enable`, {
    method: 'POST',
  })
}

export function fetchSimulatorInfo(): Promise<SimulatorInfoResponse> {
  return apiRequest<SimulatorInfoResponse>('/demo/simulator-info')
}

export function simulateDemoOrder(body: {
  preset?: string
  externalOrderId?: string
  customerName?: string
  phone?: string
  address?: string
  district?: string
  sku?: string
  quantity?: number
  unitPrice?: string
  shippingFee?: string
  paymentMode?: OrderPaymentMode
  advanceAmount?: string
}): Promise<SimulateOrderResponse> {
  return apiRequest<SimulateOrderResponse>('/demo/simulate-order', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}
