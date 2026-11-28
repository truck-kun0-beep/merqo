import { apiRequest, toQueryString } from './client'
import type { PageResponse } from './models'
import type { ClaimedAdvanceMethod, OrderStatus } from './orders'

export type OnlinePaymentMethod = 'BKASH' | 'NAGAD' | 'CARD' | 'BANK'

export type RefundPayoutMethod = OnlinePaymentMethod | 'CASH'

export type PaymentStatus = 'PENDING' | 'VERIFIED' | 'REJECTED'

export type PaymentDecision = 'VERIFIED' | 'REJECTED'

export type PaymentSource = 'STAFF_RECORDED' | 'WEBSITE_CLAIM'

export type DeciderKind = 'STAFF_MANUAL' | 'PROVIDER_MOCK' | 'PROVIDER_LIVE'

export type RefundStatus = 'REQUESTED' | 'APPROVED' | 'REJECTED' | 'PAID'

export type DifferenceKind = 'NONE' | 'OVER' | 'SHORT'

export interface OrderPaymentPositionView {
  requiredPrepayment: string
  verifiedPaid: string
  pendingPaid: string
  rejectedPaid: string
  refundedPaid: string
  refundReserved: string
  netHeld: string
  refundable: string
  prepaymentSatisfied: boolean
  shortfall: string
  codExpected: string
  codCollected: string
  codSettledGross: string
  codFeesDeducted: string
  codWithCourier: string
  codUncollected: string
  readyForShipment: boolean
  refundDue: boolean
}

export interface PaymentDecisionView {
  id: string
  decision: PaymentDecision
  decidedBy: string | null
  decidedByUsername: string | null
  deciderKind: DeciderKind
  evidence: string | null
  providerReference: string | null
  decidedAt: string
}

export interface OrderPaymentView {
  id: string
  paymentNumber: string
  orderId: string
  orderNumber: string
  method: OnlinePaymentMethod
  amount: string
  reference: string
  source: PaymentSource
  payerNote: string | null
  recordedBy: string
  recordedByUsername: string
  recordedAt: string
  status: PaymentStatus
  decision: PaymentDecisionView | null
}

export interface ClaimedAdvanceView {
  hasClaim: boolean
  method: ClaimedAdvanceMethod | null
  reference: string | null
  claimedAmount: string
  remainingAllowed: string
}

export interface PendingPaymentView {
  id: string
  paymentNumber: string
  orderId: string
  orderNumber: string
  orderStatus: OrderStatus
  customerName: string
  method: OnlinePaymentMethod
  amount: string
  reference: string
  source: PaymentSource
  recordedBy: string
  recordedByUsername: string
  recordedAt: string
  hoursPending: number
  overdue: boolean
}

export interface ProviderCheckResponse {
  outcome: 'VERIFIED' | 'REJECTED' | 'NOT_FOUND' | 'AMOUNT_MISMATCH' | 'UNAVAILABLE' | string
  message: string
  providerReference: string | null
  payment: OrderPaymentView
}

export interface RefundEventView {
  id: string
  eventType: string
  actorId: string
  actorUsername: string
  note: string | null
  payoutMethod: RefundPayoutMethod | null
  payoutReference: string | null
  createdAt: string
}

export interface RefundView {
  id: string
  refundNumber: string
  orderId: string
  orderNumber: string
  orderStatus: OrderStatus
  amount: string
  reason: string
  note: string | null
  requestedBy: string
  requestedByUsername: string
  requestedAt: string
  status: RefundStatus
  events: RefundEventView[]
}

export interface CodCollectionView {
  id: string
  orderId: string
  orderNumber: string
  courier: string
  amount: string
  collectedOn: string
  recordedBy: string
  recordedByUsername: string
  recordedAt: string
  note: string | null
}

export interface OrderPaymentsDetailResponse {
  orderId: string
  orderNumber: string
  orderStatus: OrderStatus
  paymentMode: string
  total: string
  advanceAmount: string
  codAmount: string
  claimedAdvance: ClaimedAdvanceView | null
  position: OrderPaymentPositionView
  payments: OrderPaymentView[]
  refunds: RefundView[]
  codCollections: CodCollectionView[]
}

export interface RefundDueRowView {
  orderId: string
  orderNumber: string
  orderStatus: OrderStatus
  customerName: string
  verifiedPaid: string
  refundedPaid: string
  refundable: string
  closedAt: string | null
}

export interface SettlementLineView {
  id: string
  orderId: string
  orderNumber: string
  codCollectedAmount: string
  fee: string
  net: string
}

export interface SettlementSummaryView {
  id: string
  settlementNumber: string
  courier: string
  reference: string
  receivedOn: string
  receiveMethod: string
  amountReceived: string
  feesTotal: string
  difference: string
  differenceKind: DifferenceKind
  note: string | null
  recordedBy: string
  recordedByUsername: string
  recordedAt: string
  lineCount: number
}

export interface SettlementDetailView {
  id: string
  settlementNumber: string
  courier: string
  reference: string
  receivedOn: string
  receiveMethod: string
  amountReceived: string
  feesTotal: string
  sumNet: string
  difference: string
  differenceKind: DifferenceKind
  note: string | null
  recordedBy: string
  recordedByUsername: string
  recordedAt: string
  lines: SettlementLineView[]
}

export interface CodOutstandingOrderView {
  orderId: string
  orderNumber: string
  customerName: string
  courier: string | null
  earliestCollectionDate: string | null
  expected: string
  collected: string
  settledGross: string
  withCourier: string
  uncollected: string
  gap: boolean
}

export interface CodCourierTotalView {
  courier: string
  orderCount: number
  expected: string
  collected: string
  settledGross: string
  withCourier: string
  uncollected: string
  gapCount: number
}

export interface CodOutstandingResponse {
  gapThresholdDays: number
  courierTotals: CodCourierTotalView[]
  orders: CodOutstandingOrderView[]
}

export type PaymentHistoryType =
  | 'POS_SALE'
  | 'POS_RETURN'
  | 'ORDER_PAYMENT'
  | 'ORDER_REFUND'
  | 'COD_SETTLEMENT'

export interface PaymentHistoryRowView {
  type: PaymentHistoryType
  occurredAt: string
  method: string | null
  amount: string
  reference: string | null
  documentNumber: string
  orderId: string | null
  saleId: string | null
  settlementId: string | null
  locationName: string | null
  status: string | null
  recordedBy: string | null
}

export interface PaymentSummaryBucketView {
  key: string
  count: number
  totalAmount: string
}

export interface PaymentSummaryResponse {
  totalCount: number
  totalAmount: string
  byType: PaymentSummaryBucketView[]
  byMethod: PaymentSummaryBucketView[]
}

export interface PaymentHistoryFilters {
  type?: PaymentHistoryType | ''
  method?: string
  status?: string
  locationId?: string
  fromDate?: string
  toDate?: string
  q?: string
  page?: number
  size?: number
}

export interface SimulatePaymentResponse {
  id: string
  method: OnlinePaymentMethod
  reference: string
  amount: string
  senderHint: string | null
  variant: string
  alreadyClaimed: boolean
}

export function fetchOrderPayments(orderId: string): Promise<OrderPaymentsDetailResponse> {
  return apiRequest<OrderPaymentsDetailResponse>(`/orders/${orderId}/payments`)
}

export function fetchClaimPrefill(orderId: string): Promise<ClaimedAdvanceView> {
  return apiRequest<ClaimedAdvanceView>(`/orders/${orderId}/payments/claim-prefill`)
}

export function recordOrderPayment(
  orderId: string,
  body: {
    method: OnlinePaymentMethod
    amount: string
    reference: string
    payerNote?: string | null
    fromClaim?: boolean
  },
): Promise<OrderPaymentView> {
  return apiRequest<OrderPaymentView>(`/orders/${orderId}/payments`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function verifyOrderPayment(
  paymentId: string,
  body: {
    decision: PaymentDecision
    confirmedAmount?: string
    evidence?: string | null
  },
): Promise<OrderPaymentView> {
  return apiRequest<OrderPaymentView>(`/payments/${paymentId}/verify`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function checkOrderPaymentWithProvider(paymentId: string): Promise<ProviderCheckResponse> {
  return apiRequest<ProviderCheckResponse>(`/payments/${paymentId}/check`, {
    method: 'POST',
  })
}

export function fetchPendingPayments(): Promise<PendingPaymentView[]> {
  return apiRequest<PendingPaymentView[]>('/payments/pending')
}

export function requestOrderRefund(
  orderId: string,
  body: { amount: string; reason: string; note?: string | null },
): Promise<RefundView> {
  return apiRequest<RefundView>(`/orders/${orderId}/refunds`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function approveRefund(refundId: string, note?: string | null): Promise<RefundView> {
  return apiRequest<RefundView>(`/refunds/${refundId}/approve`, {
    method: 'POST',
    body: JSON.stringify({ note: note || null }),
  })
}

export function rejectRefund(refundId: string, note?: string | null): Promise<RefundView> {
  return apiRequest<RefundView>(`/refunds/${refundId}/reject`, {
    method: 'POST',
    body: JSON.stringify({ note: note || null }),
  })
}

export function payRefund(
  refundId: string,
  body: { payoutMethod: RefundPayoutMethod; payoutReference?: string | null; note?: string | null },
): Promise<RefundView> {
  return apiRequest<RefundView>(`/refunds/${refundId}/pay`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function fetchRefunds(
  params: { status?: RefundStatus | ''; orderNumber?: string } = {},
): Promise<RefundView[]> {
  return apiRequest<RefundView[]>(
    `/refunds${toQueryString({
      status: params.status || undefined,
      orderNumber: params.orderNumber?.trim() || undefined,
    })}`,
  )
}

export function fetchRefundsDue(): Promise<RefundDueRowView[]> {
  return apiRequest<RefundDueRowView[]>('/refunds/due')
}

export function recordCodCollection(
  orderId: string,
  body: { courier: string; amount: string; collectedOn?: string; note?: string | null },
): Promise<CodCollectionView> {
  return apiRequest<CodCollectionView>(`/orders/${orderId}/cod-collections`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function createCourierSettlement(body: {
  courier: string
  reference: string
  receivedOn?: string
  receiveMethod: 'BANK' | 'BKASH' | 'NAGAD' | 'CASH' | string
  amountReceived: string
  note?: string | null
  lines: Array<{ orderId: string; codCollectedAmount: string; fee: string }>
}): Promise<SettlementDetailView> {
  return apiRequest<SettlementDetailView>('/cod/settlements', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function fetchCodOutstanding(): Promise<CodOutstandingResponse> {
  return apiRequest<CodOutstandingResponse>('/cod/outstanding')
}

export function fetchCourierSettlements(): Promise<SettlementSummaryView[]> {
  return apiRequest<SettlementSummaryView[]>('/cod/settlements')
}

export function fetchCourierSettlementDetail(settlementId: string): Promise<SettlementDetailView> {
  return apiRequest<SettlementDetailView>(`/cod/settlements/${settlementId}`)
}

export function fetchPaymentHistory(
  filters: PaymentHistoryFilters = {},
): Promise<PageResponse<PaymentHistoryRowView>> {
  return apiRequest<PageResponse<PaymentHistoryRowView>>(
    `/payments/history${toQueryString({
      type: filters.type || undefined,
      method: filters.method || undefined,
      status: filters.status || undefined,
      locationId: filters.locationId || undefined,
      fromDate: filters.fromDate || undefined,
      toDate: filters.toDate || undefined,
      q: filters.q?.trim() || undefined,
      page: filters.page ?? 0,
      size: filters.size ?? 20,
    })}`,
  )
}

export function fetchPaymentSummary(
  filters: Omit<PaymentHistoryFilters, 'page' | 'size'> = {},
): Promise<PaymentSummaryResponse> {
  return apiRequest<PaymentSummaryResponse>(
    `/payments/summary${toQueryString({
      type: filters.type || undefined,
      method: filters.method || undefined,
      status: filters.status || undefined,
      locationId: filters.locationId || undefined,
      fromDate: filters.fromDate || undefined,
      toDate: filters.toDate || undefined,
      q: filters.q?.trim() || undefined,
    })}`,
  )
}

export function simulateDemoPayment(body: {
  method: OnlinePaymentMethod
  reference: string
  amount: string
  senderHint?: string
  variant?: 'MATCHING' | 'MISMATCH' | 'NOT_FOUND'
}): Promise<SimulatePaymentResponse> {
  return apiRequest<SimulatePaymentResponse>('/demo/simulate-payment', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}
