import type {
  CashSessionSummary,
  PaymentMethod,
  SaleDetail,
  SaleReturnRecord,
  SaleSummary,
} from '../api/models'
import type {
  CloseCashSessionRequest,
  CreateReturnRequest,
  CreateSaleRequest,
  OpenCashSessionRequest,
  PriceCheckRequest,
} from '../api/pos'
import {
  buildEmptySession,
  buildInitialMockVariants,
  buildSeededSales,
  toLookupResponse,
  type MockVariantRecord,
} from './posMockData'

const MANAGER_APPROVAL_PIN = '1234'
const DEFAULT_CASHIER_DISCOUNT_LIMIT_PERCENT = 10

interface MockStore {
  variants: MockVariantRecord[]
  sessions: Map<string, CashSessionSummary>
  sales: SaleDetail[]
  nextReceiptNumber: number
  nextSessionSeq: number
  nextReturnSeq: number
}

function createStore(): MockStore {
  return {
    variants: buildInitialMockVariants(),
    sessions: new Map(),
    sales: buildSeededSales(),
    nextReceiptNumber: 103,
    nextSessionSeq: 1,
    nextReturnSeq: 1,
  }
}

let store: MockStore = createStore()

export function resetPosMockStore() {
  store = createStore()
}

function problemResponse(
  status: number,
  errorCode: string,
  detail: string,
  instance: string,
): Response {
  const payload = {
    type: `https://merqo.dev/problems/${errorCode.toLowerCase().replaceAll('_', '-')}`,
    title: errorCode,
    status,
    detail,
    instance,
    errorCode,
    correlationId: `mock-${Date.now().toString(36)}`,
    timestamp: new Date().toISOString(),
    fieldErrors: {},
    errors: [],
  }
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  })
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function toCents(decimalStr: string | null | undefined): number {
  if (!decimalStr) return 0
  const n = Number(decimalStr)
  return Number.isNaN(n) ? 0 : Math.round(n * 100)
}

function fromCents(cents: number): string {
  return (cents / 100).toFixed(2)
}

function toSaleSummary(sale: SaleDetail): SaleSummary {
  return {
    id: sale.id,
    receiptNumber: sale.receiptNumber,
    locationId: sale.locationId,
    locationName: sale.locationName,
    cashSessionId: sale.cashSessionId,
    cashierUsername: sale.cashierUsername,
    cashierName: sale.cashierName,
    soldAt: sale.soldAt,
    status: sale.status,
    subtotal: sale.subtotal,
    discountTotal: sale.discountTotal,
    taxTotal: sale.taxTotal,
    total: sale.total,
    paymentMethod: sale.paymentMethod,
    paymentReference: sale.paymentReference,
    itemCount: sale.itemCount,
  }
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

async function handlePosOrSalesRequest(
  url: URL,
  init?: RequestInit,
): Promise<Response> {
  const path = url.pathname.replace(/^\/api\/v1/, '')
  const method = (init?.method ?? 'GET').toUpperCase()
  const rawBody = typeof init?.body === 'string' ? init.body : undefined
  const body = rawBody ? (JSON.parse(rawBody) as Record<string, unknown>) : {}

  // Simulated slow response when ?slow=1 or barcode starts with SLOW
  if (
    url.searchParams.get('slow') === '1' ||
    url.searchParams.get('barcode') === '9999999999991'
  ) {
    await delay(1000)
  }

  // 1. GET /pos/lookup
  if (method === 'GET' && path === '/pos/lookup') {
    const barcode = (url.searchParams.get('barcode') ?? '').trim()
    const locationId = url.searchParams.get('locationId') ?? 'loc-1'
    const match = store.variants.find((v) => v.barcodes.includes(barcode))
    if (!match) {
      return problemResponse(
        404,
        'BARCODE_NOT_FOUND',
        `No active variant carries barcode "${barcode}".`,
        url.pathname,
      )
    }
    return jsonResponse(toLookupResponse(match, barcode, locationId))
  }

  // 2. GET /pos/sessions/current
  if (method === 'GET' && path === '/pos/sessions/current') {
    const locationId = url.searchParams.get('locationId') ?? ''
    const existing = Array.from(store.sessions.values()).find(
      (s) => s.locationId === locationId && s.status === 'OPEN',
    )
    return jsonResponse({ session: existing ?? null })
  }

  // 3. POST /pos/sessions/open
  if (method === 'POST' && path === '/pos/sessions/open') {
    const req = body as unknown as OpenCashSessionRequest
    const alreadyOpen = Array.from(store.sessions.values()).find(
      (s) => s.locationId === req.locationId && s.status === 'OPEN',
    )
    if (alreadyOpen) {
      return problemResponse(
        409,
        'CONFLICT',
        'A cash session is already open at this location.',
        url.pathname,
      )
    }
    const id = `sess-${store.nextSessionSeq++}`
    const session = buildEmptySession(id, req.locationId, fromCents(toCents(req.openingCash)))
    store.sessions.set(id, session)
    return jsonResponse(session, 201)
  }

  // 4. POST /pos/sessions/{id}/close
  const closeMatch = path.match(/^\/pos\/sessions\/([^/]+)\/close$/)
  if (method === 'POST' && closeMatch) {
    const sessionId = closeMatch[1]
    const session = store.sessions.get(sessionId)
    if (!session || session.status !== 'OPEN') {
      return problemResponse(409, 'CONFLICT', 'Session is not open.', url.pathname)
    }
    const req = body as unknown as CloseCashSessionRequest
    const countedCents = toCents(req.countedCash)
    const expectedCashCents = toCents(session.expectedCash)
    const diffCents = countedCents - expectedCashCents
    const closed: CashSessionSummary = {
      ...session,
      status: 'CLOSED',
      closedAt: new Date().toISOString(),
      countedCash: fromCents(countedCents),
      variance: fromCents(diffCents),
      byMethod: session.byMethod.map((entry) =>
        entry.method === 'CASH'
          ? {
              ...entry,
              counted: fromCents(countedCents),
              difference: fromCents(diffCents),
            }
          : entry,
      ),
    }
    store.sessions.set(sessionId, closed)
    return jsonResponse(closed)
  }

  // 5. POST /pos/price-check
  if (method === 'POST' && path === '/pos/price-check') {
    const req = body as unknown as PriceCheckRequest
    const warnings = computeBelowCostWarnings(req)
    return jsonResponse({ belowCostWarnings: warnings })
  }

  // 6. POST /sales
  if (method === 'POST' && path === '/sales') {
    const req = body as unknown as CreateSaleRequest
    const openSession = store.sessions.get(req.cashSessionId)
    if (!openSession || openSession.status !== 'OPEN' || openSession.locationId !== req.locationId) {
      return problemResponse(
        409,
        'POS_SESSION_REQUIRED',
        'Open a cash session for this location before completing a sale.',
        url.pathname,
      )
    }

    // Payment validation
    if (req.payment.method === 'BKASH' || req.payment.method === 'NAGAD') {
      if (!req.payment.trxId || req.payment.trxId.trim().length < 4) {
        return problemResponse(
          422,
          'VALIDATION_FAILED',
          `${req.payment.method} requires a valid transaction ID (TrxID).`,
          url.pathname,
        )
      }
    }
    if (req.payment.method === 'CARD' && req.payment.cardReference) {
      const digitsOnly = req.payment.cardReference.replace(/\D/g, '')
      if (digitsOnly.length > 6 && !req.payment.cardReference.toUpperCase().startsWith('AUTH')) {
        return problemResponse(
          422,
          'VALIDATION_FAILED',
          'Do not enter a full card number. Provide only the last 4 digits or approval code.',
          url.pathname,
        )
      }
    }

    // Stock & pricing calculation
    let subtotalCents = 0
    let promoDiscountCents = 0
    let manualLineDiscountCents = 0
    let taxCents = 0
    let totalListBeforeDiscountCents = 0

    const saleLines: SaleDetail['lines'] = []

    for (let i = 0; i < req.lines.length; i++) {
      const lineReq = req.lines[i]
      const variant = store.variants.find((v) => v.variantId === lineReq.variantId)
      if (!variant) {
        return problemResponse(404, 'NOT_FOUND', 'Variant not found.', url.pathname)
      }
      const available =
        variant.stockByLocation[req.locationId] ?? variant.stockByLocation.default ?? 10
      if (lineReq.quantity > available) {
        return problemResponse(
          409,
          'INSUFFICIENT_STOCK',
          `Not enough stock for ${variant.sku}. Available: ${available}, requested: ${lineReq.quantity}.`,
          url.pathname,
        )
      }

      const unitListCents = toCents(variant.listPrice)
      const unitPromoCents = toCents(variant.promotionDiscount)
      const lineGrossCents = unitListCents * lineReq.quantity
      const linePromoCents = unitPromoCents * lineReq.quantity
      const afterPromoCents = Math.max(0, lineGrossCents - linePromoCents)

      let lineManualCents = 0
      if (lineReq.manualDiscountType === 'PERCENT' && lineReq.manualDiscountValue) {
        lineManualCents = Math.round(
          (afterPromoCents * Number(lineReq.manualDiscountValue)) / 100,
        )
      } else if (lineReq.manualDiscountType === 'FIXED' && lineReq.manualDiscountValue) {
        lineManualCents = toCents(lineReq.manualDiscountValue)
      }
      lineManualCents = Math.min(afterPromoCents, Math.max(0, lineManualCents))

      const lineNetCents = afterPromoCents - lineManualCents
      const taxRate = Number(variant.taxRatePercent) || 0
      const lineTaxCents = Math.round((lineNetCents * taxRate) / 100)

      totalListBeforeDiscountCents += afterPromoCents
      subtotalCents += lineNetCents
      promoDiscountCents += linePromoCents
      manualLineDiscountCents += lineManualCents
      taxCents += lineTaxCents

      saleLines.push({
        id: `sl-${Date.now()}-${i}`,
        variantId: variant.variantId,
        sku: variant.sku,
        productName: variant.productName,
        variantLabel: variant.variantLabel,
        quantity: lineReq.quantity,
        returnedQuantity: 0,
        unitPrice: variant.listPrice,
        promotionDiscount: fromCents(linePromoCents),
        manualDiscount: fromCents(lineManualCents),
        tax: fromCents(lineTaxCents),
        lineTotal: fromCents(lineNetCents + lineTaxCents),
      })
    }

    let billDiscountCents = 0
    if (req.billDiscountType === 'PERCENT' && req.billDiscountValue) {
      billDiscountCents = Math.round((subtotalCents * Number(req.billDiscountValue)) / 100)
    } else if (req.billDiscountType === 'FIXED' && req.billDiscountValue) {
      billDiscountCents = toCents(req.billDiscountValue)
    }
    billDiscountCents = Math.min(subtotalCents, Math.max(0, billDiscountCents))

    const totalManualCents = manualLineDiscountCents + billDiscountCents
    const effectiveManualPercent =
      totalListBeforeDiscountCents > 0
        ? (totalManualCents / totalListBeforeDiscountCents) * 100
        : 0

    if (effectiveManualPercent > DEFAULT_CASHIER_DISCOUNT_LIMIT_PERCENT) {
      if (req.managerPin !== MANAGER_APPROVAL_PIN) {
        return problemResponse(
          422,
          'DISCOUNT_LIMIT_EXCEEDED',
          `Manual discount (${effectiveManualPercent.toFixed(1)}%) exceeds your ${DEFAULT_CASHIER_DISCOUNT_LIMIT_PERCENT}% limit. Enter manager approval PIN.`,
          url.pathname,
        )
      }
    }

    const discountedSubtotalCents = subtotalCents - billDiscountCents
    const adjustedTaxCents =
      subtotalCents > 0
        ? Math.round((taxCents * discountedSubtotalCents) / subtotalCents)
        : 0
    const totalCents = discountedSubtotalCents + adjustedTaxCents

    if (req.payment.method === 'CASH') {
      const givenCents = toCents(req.payment.amountGiven)
      if (givenCents < totalCents) {
        return problemResponse(
          422,
          'VALIDATION_FAILED',
          'Cash given must cover the total amount.',
          url.pathname,
        )
      }
    }

    // Deduct stock
    for (const lineReq of req.lines) {
      const variant = store.variants.find((v) => v.variantId === lineReq.variantId)!
      const current =
        variant.stockByLocation[req.locationId] ?? variant.stockByLocation.default ?? 10
      variant.stockByLocation[req.locationId] = current - lineReq.quantity
    }

    const receiptNumber = `MQ-2026-${String(store.nextReceiptNumber++).padStart(6, '0')}`
    const soldAt = new Date().toISOString()
    const totalDiscountCents = promoDiscountCents + totalManualCents
    const paymentRef =
      req.payment.method === 'CASH'
        ? null
        : (req.payment.trxId ?? req.payment.cardReference ?? null)

    const saleId = `sale-${Date.now()}`
    const sale: SaleDetail = {
      id: saleId,
      receiptNumber,
      locationId: req.locationId,
      locationName: 'Active Branch',
      cashSessionId: openSession.id,
      cashierUsername: openSession.cashierUsername,
      cashierName: 'Counter Cashier',
      soldAt,
      status: 'COMPLETED',
      subtotal: fromCents(discountedSubtotalCents),
      discountTotal: fromCents(totalDiscountCents),
      taxTotal: fromCents(adjustedTaxCents),
      total: fromCents(totalCents),
      paymentMethod: req.payment.method,
      paymentReference: paymentRef,
      itemCount: req.lines.reduce((acc, l) => acc + l.quantity, 0),
      lines: saleLines,
      returns: [],
      belowCostWarnings: computeBelowCostWarnings(req),
      receipt: {
        businessName: 'Merqo Store',
        branchName: 'Active Branch',
        branchAddress: 'Road 11, Banani, Dhaka',
        branchPhone: '+880 1700-000111',
        receiptNumber,
        issuedAt: soldAt,
        cashierName: 'Counter Cashier',
        lines: saleLines.map((l) => ({
          name: l.productName,
          variant: l.variantLabel,
          quantity: l.quantity,
          unitPrice: l.unitPrice,
          discount: fromCents(toCents(l.promotionDiscount) + toCents(l.manualDiscount)),
          lineTotal: l.lineTotal,
        })),
        subtotal: fromCents(discountedSubtotalCents),
        discount: fromCents(totalDiscountCents),
        tax: fromCents(adjustedTaxCents),
        total: fromCents(totalCents),
        paymentMethod: req.payment.method,
        paymentReference: paymentRef,
        footerText: 'Thank you for your purchase.',
        isDuplicate: false,
      },
    }

    store.sales.unshift(sale)

    // Update cash session totals
    const nextExpectedCash =
      req.payment.method === 'CASH'
        ? fromCents(toCents(openSession.expectedCash) + totalCents)
        : openSession.expectedCash
    store.sessions.set(openSession.id, {
      ...openSession,
      expectedCash: nextExpectedCash,
      saleCount: openSession.saleCount + 1,
      byMethod: openSession.byMethod.map((entry) =>
        entry.method === req.payment.method
          ? { ...entry, expected: fromCents(toCents(entry.expected) + totalCents) }
          : entry,
      ),
    })

    return jsonResponse(sale, 201)
  }

  // 7. GET /sales
  if (method === 'GET' && path === '/sales') {
    const locationId = url.searchParams.get('locationId')
    const q = (url.searchParams.get('q') ?? '').trim().toLowerCase()
    const fromDate = url.searchParams.get('fromDate')
    const toDate = url.searchParams.get('toDate')
    const page = Number(url.searchParams.get('page') ?? '0')
    const size = Number(url.searchParams.get('size') ?? '20')

    let filtered = store.sales
    if (locationId) {
      // Include seeded sales if matching locationId or if store seeded default
      filtered = filtered.filter(
        (s) => s.locationId === locationId || s.id.startsWith('sale-seed-'),
      )
    }
    if (q) {
      filtered = filtered.filter((s) => s.receiptNumber.toLowerCase().includes(q))
    }
    if (fromDate) {
      filtered = filtered.filter((s) => s.soldAt.slice(0, 10) >= fromDate)
    }
    if (toDate) {
      filtered = filtered.filter((s) => s.soldAt.slice(0, 10) <= toDate)
    }

    const totalItems = filtered.length
    const totalPages = Math.max(1, Math.ceil(totalItems / size))
    const items = filtered.slice(page * size, (page + 1) * size).map(toSaleSummary)

    return jsonResponse({
      items,
      page,
      size,
      totalItems,
      totalPages,
      hasNext: page + 1 < totalPages,
    })
  }

  // 8. GET /sales/by-receipt/{receiptNumber}
  const byReceiptMatch = path.match(/^\/sales\/by-receipt\/([^/]+)$/)
  if (method === 'GET' && byReceiptMatch) {
    const receiptNumber = decodeURIComponent(byReceiptMatch[1]).trim()
    const sale = store.sales.find(
      (s) => s.receiptNumber.toLowerCase() === receiptNumber.toLowerCase(),
    )
    if (!sale) {
      return problemResponse(
        404,
        'NOT_FOUND',
        `No sale found with receipt number "${receiptNumber}".`,
        url.pathname,
      )
    }
    return jsonResponse(sale)
  }

  // 9. GET /sales/{id}
  const getSaleMatch = path.match(/^\/sales\/([^/]+)$/)
  if (method === 'GET' && getSaleMatch) {
    const sale = store.sales.find((s) => s.id === getSaleMatch[1])
    if (!sale) {
      return problemResponse(404, 'NOT_FOUND', 'Sale not found.', url.pathname)
    }
    return jsonResponse(sale)
  }

  // 10. POST /sales/{id}/reprint
  const reprintMatch = path.match(/^\/sales\/([^/]+)\/reprint$/)
  if (method === 'POST' && reprintMatch) {
    const sale = store.sales.find((s) => s.id === reprintMatch[1])
    if (!sale) {
      return problemResponse(404, 'NOT_FOUND', 'Sale not found.', url.pathname)
    }
    return jsonResponse({ ...sale.receipt, isDuplicate: true })
  }

  // 11. POST /sales/{id}/returns
  const returnMatch = path.match(/^\/sales\/([^/]+)\/returns$/)
  if (method === 'POST' && returnMatch) {
    const sale = store.sales.find((s) => s.id === returnMatch[1])
    if (!sale) {
      return problemResponse(404, 'NOT_FOUND', 'Sale not found.', url.pathname)
    }
    if (sale.status === 'RETURNED') {
      return problemResponse(
        409,
        'ALREADY_RETURNED',
        'All items on this receipt have already been returned.',
        url.pathname,
      )
    }

    const req = body as unknown as CreateReturnRequest
    if (!req.reason || !req.reason.trim()) {
      return problemResponse(422, 'VALIDATION_FAILED', 'Return reason is required.', url.pathname)
    }
    if (!req.lines || req.lines.length === 0) {
      return problemResponse(
        422,
        'VALIDATION_FAILED',
        'Select at least one line to return.',
        url.pathname,
      )
    }

    let totalRefundCents = 0
    const returnLines: SaleReturnRecord['lines'] = []

    for (const lineReq of req.lines) {
      const saleLine = sale.lines.find((l) => l.id === lineReq.saleLineId)
      if (!saleLine) {
        return problemResponse(404, 'NOT_FOUND', 'Sale line not found.', url.pathname)
      }
      const remaining = saleLine.quantity - saleLine.returnedQuantity
      if (remaining <= 0) {
        return problemResponse(
          409,
          'ALREADY_RETURNED',
          `${saleLine.sku} has already been fully returned.`,
          url.pathname,
        )
      }
      if (lineReq.quantity <= 0 || lineReq.quantity > remaining) {
        return problemResponse(
          422,
          'VALIDATION_FAILED',
          `Return quantity for ${saleLine.sku} must be between 1 and ${remaining}.`,
          url.pathname,
        )
      }
      const perUnitCents = Math.round(toCents(saleLine.lineTotal) / saleLine.quantity)
      const refundCents = perUnitCents * lineReq.quantity
      totalRefundCents += refundCents
      returnLines.push({
        saleLineId: saleLine.id,
        sku: saleLine.sku,
        quantity: lineReq.quantity,
        refundAmount: fromCents(refundCents),
      })
    }

    // Apply return
    for (const rLine of returnLines) {
      const saleLine = sale.lines.find((l) => l.id === rLine.saleLineId)!
      saleLine.returnedQuantity += rLine.quantity
    }
    const allReturned = sale.lines.every((l) => l.returnedQuantity >= l.quantity)
    sale.status = allReturned ? 'RETURNED' : 'PARTIALLY_RETURNED'

    const record: SaleReturnRecord = {
      id: `ret-${store.nextReturnSeq}`,
      refundNumber: `RFN-2026-${String(store.nextReturnSeq++).padStart(6, '0')}`,
      returnedAt: new Date().toISOString(),
      actorUsername: 'counter.staff',
      reason: req.reason.trim(),
      totalRefund: fromCents(totalRefundCents),
      lines: returnLines,
    }
    sale.returns.push(record)

    // Update active session refund total if matching
    const openSession = store.sessions.get(sale.cashSessionId)
    if (openSession && openSession.status === 'OPEN') {
      const nextRefundTotal = fromCents(toCents(openSession.refundTotal) + totalRefundCents)
      const nextExpectedCash =
        sale.paymentMethod === 'CASH'
          ? fromCents(toCents(openSession.expectedCash) - totalRefundCents)
          : openSession.expectedCash
      store.sessions.set(openSession.id, {
        ...openSession,
        refundTotal: nextRefundTotal,
        expectedCash: nextExpectedCash,
      })
    }

    return jsonResponse(record, 201)
  }

  return problemResponse(404, 'NOT_FOUND', `No mock route for ${method} ${path}`, url.pathname)
}

function computeBelowCostWarnings(req: PriceCheckRequest) {
  const warnings: Array<{
    variantId: string
    sku: string
    netUnitPrice: string
    costPrice: string
  }> = []

  for (const lineReq of req.lines) {
    const variant = store.variants.find((v) => v.variantId === lineReq.variantId)
    if (!variant) continue
    const unitListCents = toCents(variant.listPrice)
    const unitPromoCents = toCents(variant.promotionDiscount)
    const afterPromoCents = Math.max(0, unitListCents - unitPromoCents)
    let lineManualCents = 0
    if (lineReq.manualDiscountType === 'PERCENT' && lineReq.manualDiscountValue) {
      lineManualCents = Math.round(
        ((afterPromoCents * lineReq.quantity) * Number(lineReq.manualDiscountValue)) / 100,
      )
    } else if (lineReq.manualDiscountType === 'FIXED' && lineReq.manualDiscountValue) {
      lineManualCents = toCents(lineReq.manualDiscountValue)
    }
    const perUnitNetCents = Math.max(
      0,
      Math.round((afterPromoCents * lineReq.quantity - lineManualCents) / Math.max(1, lineReq.quantity)),
    )
    const costCents = toCents(variant.costPrice)
    if (perUnitNetCents < costCents) {
      warnings.push({
        variantId: variant.variantId,
        sku: variant.sku,
        netUnitPrice: fromCents(perUnitNetCents),
        costPrice: variant.costPrice,
      })
    }
  }
  return warnings
}

let installed = false

/**
 * Wraps `globalThis.fetch` for `/api/v1/pos/*` and `/api/v1/sales/*` only.
 * All other API requests pass directly through to the real `fetch`.
 */
export function installPosMocks(): () => void {
  if (installed) {
    return () => {}
  }
  const originalFetch = globalThis.fetch.bind(globalThis)
  installed = true

  globalThis.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const rawUrl =
      typeof input === 'string'
        ? input
        : input instanceof URL
          ? input.toString()
          : input.url
    const parsed = new URL(rawUrl, 'http://localhost')

    if (
      parsed.pathname.startsWith('/api/v1/pos/') ||
      parsed.pathname === '/api/v1/pos' ||
      parsed.pathname.startsWith('/api/v1/sales/') ||
      parsed.pathname === '/api/v1/sales'
    ) {
      return handlePosOrSalesRequest(parsed, init)
    }

    return originalFetch(input, init)
  }

  return () => {
    globalThis.fetch = originalFetch
    installed = false
  }
}

export type MockPaymentMethod = PaymentMethod
