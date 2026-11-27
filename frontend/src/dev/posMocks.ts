import type {
  ApprovalTokenResponse,
  BelowCostWarning,
  CashSessionSummary,
  InspectionItem,
  PaymentMethod,
  ResolveInspectionResponse,
  SaleDetail,
  SaleReturnRecord,
  SaleSummary,
} from '../api/models'
import type {
  CloseCashSessionRequest,
  CreateApprovalRequest,
  CreateReturnRequest,
  CreateSaleRequest,
  OpenCashSessionRequest,
  ResolveInspectionRequest,
  SetApprovalPinRequest,
} from '../api/pos'
import {
  buildEmptySession,
  buildInitialMockInspections,
  buildInitialMockVariants,
  buildSeededSales,
  toLookupResponse,
  type MockVariantRecord,
} from './posMockData'

const DEFAULT_APPROVAL_PIN = '5839'
const DEFAULT_CASHIER_DISCOUNT_LIMIT_PERCENT = 10

interface ApprovalTokenEntry {
  token: string
  locationId: string
  maxDiscountPercent: number
  used: boolean
}

interface IdempotencyEntry {
  payloadHash: string
  response: unknown
  status: number
}

interface MockStore {
  variants: MockVariantRecord[]
  sessions: Map<string, CashSessionSummary>
  sales: SaleDetail[]
  returnsBySale: Map<string, SaleReturnRecord[]>
  reprintCounts: Map<string, number>
  inspections: InspectionItem[]
  usedReferences: Set<string>
  approvalPin: string
  failedPinAttempts: number
  approvalTokens: Map<string, ApprovalTokenEntry>
  idempotency: Map<string, IdempotencyEntry>
  nextReceiptNumber: number
  nextSessionSeq: number
  nextReturnSeq: number
  nextTokenSeq: number
}

function createStore(): MockStore {
  return {
    variants: buildInitialMockVariants(),
    sessions: new Map(),
    sales: buildSeededSales(),
    returnsBySale: new Map(),
    reprintCounts: new Map(),
    inspections: buildInitialMockInspections(),
    usedReferences: new Set(['BKX998812']),
    approvalPin: DEFAULT_APPROVAL_PIN,
    failedPinAttempts: 0,
    approvalTokens: new Map(),
    idempotency: new Map(),
    nextReceiptNumber: 103,
    nextSessionSeq: 1,
    nextReturnSeq: 1,
    nextTokenSeq: 1,
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
    cashierUsername: sale.cashierUsername,
    createdAt: sale.createdAt,
    itemCount: sale.lines.reduce((acc, l) => acc + l.quantity, 0),
    total: sale.total,
    paymentMethod: sale.paymentMethod,
  }
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function readHeader(headers: HeadersInit | undefined, name: string): string | null {
  if (!headers) return null
  if (headers instanceof Headers) {
    return headers.get(name)
  }
  const target = name.toLowerCase()
  if (Array.isArray(headers)) {
    const pair = headers.find(([k]) => k.toLowerCase() === target)
    return pair ? pair[1] : null
  }
  for (const [k, v] of Object.entries(headers)) {
    if (k.toLowerCase() === target) return v
  }
  return null
}

function isRejectedPan(raw: string): boolean {
  const digits = raw.replace(/\D/g, '')
  return digits.length === 13 || digits.length === 16 || digits.length === 19 || digits.length >= 12
}

async function handleMockRequest(url: URL, init?: RequestInit): Promise<Response> {
  const path = url.pathname.replace(/^\/api\/v1/, '')
  const method = (init?.method ?? 'GET').toUpperCase()
  const rawBody = typeof init?.body === 'string' ? init.body : undefined
  const body = rawBody ? (JSON.parse(rawBody) as Record<string, unknown>) : {}

  if (url.searchParams.get('slow') === '1' || url.searchParams.get('barcode') === '9999999999991') {
    await delay(1000)
  }

  // 1. GET /catalog/lookup?barcode=...&scope=POS&locationId=...
  if (method === 'GET' && path === '/catalog/lookup') {
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

  // 3. POST /pos/sessions
  if (method === 'POST' && path === '/pos/sessions') {
    const req = body as unknown as OpenCashSessionRequest
    const alreadyOpen = Array.from(store.sessions.values()).find(
      (s) => s.locationId === req.locationId && s.status === 'OPEN',
    )
    if (alreadyOpen) {
      return problemResponse(
        409,
        'SESSION_ALREADY_OPEN',
        'A cash session is already open at this location.',
        url.pathname,
      )
    }
    const seq = store.nextSessionSeq++
    const id = `sess-${seq}`
    const session = buildEmptySession(
      id,
      `CS-2026-${String(seq).padStart(4, '0')}`,
      req.locationId,
      fromCents(toCents(req.openingCash)),
    )
    store.sessions.set(id, session)
    return jsonResponse(session, 201)
  }

  // 4. GET /pos/sessions/{id}/summary
  const summaryMatch = path.match(/^\/pos\/sessions\/([^/]+)\/summary$/)
  if (method === 'GET' && summaryMatch) {
    const session = store.sessions.get(summaryMatch[1])
    if (!session) {
      return problemResponse(404, 'NOT_FOUND', 'Cash session not found.', url.pathname)
    }
    return jsonResponse(session)
  }

  // 5. POST /pos/sessions/{id}/close
  const closeMatch = path.match(/^\/pos\/sessions\/([^/]+)\/close$/)
  if (method === 'POST' && closeMatch) {
    const sessionId = closeMatch[1]
    const session = store.sessions.get(sessionId)
    if (!session) {
      return problemResponse(404, 'NOT_FOUND', 'Session not found.', url.pathname)
    }
    if (session.status !== 'OPEN') {
      return problemResponse(409, 'SESSION_NOT_OPEN', 'Session is not open.', url.pathname)
    }
    const req = body as unknown as CloseCashSessionRequest
    const countedCashCents = toCents(req.countedCash)
    const countedByMethod: Record<PaymentMethod, string | null> = {
      CASH: fromCents(countedCashCents),
      BKASH: req.countedBkash !== undefined ? fromCents(toCents(req.countedBkash)) : null,
      NAGAD: req.countedNagad !== undefined ? fromCents(toCents(req.countedNagad)) : null,
      CARD: req.countedCard !== undefined ? fromCents(toCents(req.countedCard)) : null,
    }
    const expectedCashCents = toCents(session.expectedCash)
    const diffCents = countedCashCents - expectedCashCents
    const closed: CashSessionSummary = {
      ...session,
      status: 'CLOSED',
      closedAt: new Date().toISOString(),
      countedCash: fromCents(countedCashCents),
      variance: fromCents(diffCents),
      closeNote: req.note ?? null,
      byMethod: session.byMethod.map((entry) => {
        const counted = countedByMethod[entry.method]
        if (counted === null) return entry
        const diff = toCents(counted) - toCents(entry.expected)
        return {
          ...entry,
          counted,
          difference: fromCents(diff),
        }
      }),
    }
    store.sessions.set(sessionId, closed)
    return jsonResponse(closed)
  }

  // 6. PUT /auth/approval-pin
  if (method === 'PUT' && path === '/auth/approval-pin') {
    const req = body as unknown as SetApprovalPinRequest
    if (!req.currentPassword) {
      return problemResponse(401, 'INVALID_CREDENTIALS', 'Current password is incorrect.', url.pathname)
    }
    if (!/^\d{4,8}$/.test(req.pin)) {
      return problemResponse(
        422,
        'PIN_POLICY',
        'Approval PIN must be 4 to 8 digits.',
        url.pathname,
      )
    }
    if (new Set(req.pin.split('')).size <= 1) {
      return problemResponse(
        422,
        'PIN_POLICY',
        'Approval PIN cannot repeat the same digit.',
        url.pathname,
      )
    }
    store.approvalPin = req.pin
    store.failedPinAttempts = 0
    return new Response(null, { status: 204 })
  }

  // 7. POST /pos/approvals
  if (method === 'POST' && path === '/pos/approvals') {
    const req = body as unknown as CreateApprovalRequest
    if (store.failedPinAttempts >= 5) {
      return problemResponse(
        423,
        'PIN_LOCKED',
        'Too many invalid PIN attempts. Try again in 15 minutes.',
        url.pathname,
      )
    }
    if (req.pin !== store.approvalPin) {
      store.failedPinAttempts++
      return problemResponse(422, 'APPROVAL_INVALID', 'Invalid approval PIN.', url.pathname)
    }
    store.failedPinAttempts = 0
    const token = `apv-${store.nextTokenSeq++}-${Date.now().toString(36)}`
    store.approvalTokens.set(token, {
      token,
      locationId: req.locationId,
      maxDiscountPercent: Number(req.maxDiscountPercent),
      used: false,
    })
    const resp: ApprovalTokenResponse = {
      approvalToken: token,
      expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
      maxDiscountPercent: String(req.maxDiscountPercent),
    }
    return jsonResponse(resp, 201)
  }

  // 8. POST /pos/sales
  if (method === 'POST' && path === '/pos/sales') {
    const req = body as unknown as CreateSaleRequest
    const idemKey = readHeader(init?.headers, 'Idempotency-Key') ?? req.idempotencyKey
    if (idemKey) {
      const normalized = JSON.stringify({ ...req, idempotencyKey: undefined })
      const existing = store.idempotency.get(`sale:${idemKey}`)
      if (existing) {
        if (existing.payloadHash !== normalized) {
          return problemResponse(
            409,
            'IDEMPOTENCY_CONFLICT',
            'Idempotency-Key was reused with a different request payload.',
            url.pathname,
          )
        }
        return jsonResponse(existing.response, existing.status)
      }
    }

    if (!req.lines || req.lines.length === 0) {
      return problemResponse(422, 'EMPTY_CART', 'Sale must contain at least one line.', url.pathname)
    }

    const openSession = Array.from(store.sessions.values()).find(
      (s) => s.locationId === req.locationId && s.status === 'OPEN',
    )
    if (!openSession) {
      return problemResponse(
        409,
        'POS_SESSION_REQUIRED',
        'Open a cash session for this location before completing a sale.',
        url.pathname,
      )
    }

    const paymentRef = req.payment.reference?.trim() || null
    if (req.payment.method === 'BKASH' || req.payment.method === 'NAGAD') {
      if (!paymentRef) {
        return problemResponse(
          422,
          'PAYMENT_REFERENCE_REQUIRED',
          `${req.payment.method} requires a transaction ID (TrxID).`,
          url.pathname,
        )
      }
      if (store.usedReferences.has(paymentRef.toUpperCase())) {
        return problemResponse(
          409,
          'DUPLICATE_REFERENCE',
          `Transaction reference "${paymentRef}" has already been recorded.`,
          url.pathname,
        )
      }
    }
    if (paymentRef && isRejectedPan(paymentRef)) {
      return problemResponse(
        422,
        'CARD_NUMBER_REJECTED',
        'Full card numbers must never be sent or stored.',
        url.pathname,
      )
    }

    let subtotalCents = 0
    let promoDiscountCents = 0
    let manualLineDiscountCents = 0
    let taxCents = 0
    let totalAfterPromoCents = 0

    const saleLines: SaleDetail['lines'] = []
    const belowCostWarnings: BelowCostWarning[] = []

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
      const afterPromoCents = Math.max(0, unitListCents - unitPromoCents) * lineReq.quantity
      const linePromoCents = unitPromoCents * lineReq.quantity

      let lineManualCents = 0
      if (lineReq.manualDiscountType === 'PERCENT' && lineReq.manualDiscountValue) {
        lineManualCents = Math.round((afterPromoCents * Number(lineReq.manualDiscountValue)) / 100)
      } else if (lineReq.manualDiscountType === 'FIXED' && lineReq.manualDiscountValue) {
        lineManualCents = toCents(lineReq.manualDiscountValue)
      }
      lineManualCents = Math.min(afterPromoCents, Math.max(0, lineManualCents))

      const lineNetCents = afterPromoCents - lineManualCents
      const taxRate = Number(variant.taxRatePercent) || 0
      const lineTaxCents = Math.round((lineNetCents * taxRate) / 100)
      const netUnitCents = Math.round(lineNetCents / Math.max(1, lineReq.quantity))
      const costCents = toCents(variant.costPrice)

      if (netUnitCents < costCents) {
        belowCostWarnings.push({
          variantId: variant.variantId,
          sku: variant.sku,
          netUnitPrice: fromCents(netUnitCents),
          costPrice: variant.costPrice,
        })
      }

      totalAfterPromoCents += afterPromoCents
      subtotalCents += lineNetCents
      promoDiscountCents += linePromoCents
      manualLineDiscountCents += lineManualCents
      taxCents += lineTaxCents

      saleLines.push({
        id: `sl-${Date.now()}-${i}`,
        lineIndex: i,
        variantId: variant.variantId,
        productName: variant.productName,
        sku: variant.sku,
        barcode: variant.barcodes[0] ?? null,
        color: variant.color,
        size: variant.size,
        quantity: lineReq.quantity,
        listPrice: variant.listPrice,
        promotionName: variant.promotionName,
        promotionDiscount: variant.promotionDiscount,
        manualDiscount: fromCents(lineManualCents),
        billDiscountAllocated: '0.00',
        netUnitPrice: fromCents(netUnitCents),
        taxRatePercent: variant.taxRatePercent,
        taxInclusive: variant.taxInclusive,
        taxAmount: fromCents(lineTaxCents),
        lineSubtotal: fromCents(lineNetCents),
        lineTotal: fromCents(lineNetCents + lineTaxCents),
        unitCost: variant.costPrice,
        belowCost: netUnitCents < costCents,
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
      totalAfterPromoCents > 0 ? (totalManualCents / totalAfterPromoCents) * 100 : 0

    if (effectiveManualPercent > DEFAULT_CASHIER_DISCOUNT_LIMIT_PERCENT) {
      const tokenEntry = req.approvalToken ? store.approvalTokens.get(req.approvalToken) : undefined
      if (
        !tokenEntry ||
        tokenEntry.used ||
        tokenEntry.locationId !== req.locationId ||
        effectiveManualPercent > tokenEntry.maxDiscountPercent + 0.01
      ) {
        return problemResponse(
          422,
          req.approvalToken ? 'APPROVAL_INVALID' : 'DISCOUNT_LIMIT_EXCEEDED',
          req.approvalToken
            ? 'Approval token is invalid or expired.'
            : `Manual discount exceeds ${DEFAULT_CASHIER_DISCOUNT_LIMIT_PERCENT}% limit.`,
          url.pathname,
        )
      }
      tokenEntry.used = true
    }

    const discountedSubtotalCents = subtotalCents - billDiscountCents
    const adjustedTaxCents =
      subtotalCents > 0 ? Math.round((taxCents * discountedSubtotalCents) / subtotalCents) : 0
    const totalCents = discountedSubtotalCents + adjustedTaxCents

    let changeGivenCents = 0
    let tenderedStr: string | null = null
    if (req.payment.method === 'CASH') {
      const tenderedCents = toCents(req.payment.tendered)
      if (tenderedCents < totalCents) {
        return problemResponse(
          422,
          'PAYMENT_INSUFFICIENT',
          'Cash tendered must cover the total amount.',
          url.pathname,
        )
      }
      changeGivenCents = tenderedCents - totalCents
      tenderedStr = fromCents(tenderedCents)
    }

    // Deduct stock and record unique payment reference
    for (const lineReq of req.lines) {
      const variant = store.variants.find((v) => v.variantId === lineReq.variantId)!
      const current =
        variant.stockByLocation[req.locationId] ?? variant.stockByLocation.default ?? 10
      variant.stockByLocation[req.locationId] = current - lineReq.quantity
    }
    if ((req.payment.method === 'BKASH' || req.payment.method === 'NAGAD') && paymentRef) {
      store.usedReferences.add(paymentRef.toUpperCase())
    }

    const receiptNumber = `MQ-2026-${String(store.nextReceiptNumber++).padStart(6, '0')}`
    const createdAt = new Date().toISOString()
    const totalDiscountCents = promoDiscountCents + totalManualCents
    const saleId = `sale-${Date.now()}-${store.nextReceiptNumber}`

    const sale: SaleDetail = {
      id: saleId,
      receiptNumber,
      locationId: req.locationId,
      locationName: 'Active Branch',
      cashSessionId: openSession.id,
      cashierId: openSession.cashierId,
      cashierUsername: openSession.cashierUsername,
      createdAt,
      subtotal: fromCents(discountedSubtotalCents),
      promotionDiscount: fromCents(promoDiscountCents),
      manualDiscount: fromCents(totalManualCents),
      taxTotal: fromCents(adjustedTaxCents),
      total: fromCents(totalCents),
      paymentMethod: req.payment.method,
      changeGiven: fromCents(changeGivenCents),
      payment: {
        method: req.payment.method,
        amount: fromCents(totalCents),
        tendered: tenderedStr,
        changeGiven: fromCents(changeGivenCents),
        reference: paymentRef,
      },
      lines: saleLines,
      note: req.note ?? null,
      belowCostWarnings,
      receipt: {
        businessName: 'Merqo Store',
        branchName: 'Active Branch',
        branchAddress: 'Road 11, Banani, Dhaka',
        branchPhone: '+880 1700-000111',
        receiptNumber,
        issuedAt: createdAt,
        cashierName: 'Counter Cashier',
        lines: saleLines.map((l) => ({
          name: l.productName,
          variant: [l.color, l.size].filter(Boolean).join(' / ') || null,
          quantity: l.quantity,
          unitPrice: l.listPrice,
          discount: fromCents(toCents(l.promotionDiscount) * l.quantity + toCents(l.manualDiscount)),
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
    store.returnsBySale.set(sale.id, [])

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

    if (idemKey) {
      store.idempotency.set(`sale:${idemKey}`, {
        payloadHash: JSON.stringify({ ...req, idempotencyKey: undefined }),
        response: sale,
        status: 201,
      })
    }

    return jsonResponse(sale, 201)
  }

  // 9. GET /pos/sales
  if (method === 'GET' && path === '/pos/sales') {
    const locationId = url.searchParams.get('locationId')
    const q = (url.searchParams.get('q') ?? '').trim().toLowerCase()
    const fromDate = url.searchParams.get('fromDate')
    const toDate = url.searchParams.get('toDate')
    const page = Number(url.searchParams.get('page') ?? '0')
    const size = Number(url.searchParams.get('size') ?? '20')

    let filtered = store.sales
    if (locationId) {
      filtered = filtered.filter(
        (s) => s.locationId === locationId || s.id.startsWith('sale-seed-'),
      )
    }
    if (q) {
      filtered = filtered.filter((s) => s.receiptNumber.toLowerCase().includes(q))
    }
    if (fromDate) {
      filtered = filtered.filter((s) => s.createdAt.slice(0, 10) >= fromDate)
    }
    if (toDate) {
      filtered = filtered.filter((s) => s.createdAt.slice(0, 10) <= toDate)
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

  // 10. GET /pos/sales/lookup?receiptNumber=...
  if (method === 'GET' && path === '/pos/sales/lookup') {
    const receiptNumber = (url.searchParams.get('receiptNumber') ?? '').trim()
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

  // 11. GET /pos/sales/{id}
  const getSaleMatch = path.match(/^\/pos\/sales\/([^/]+)$/)
  if (method === 'GET' && getSaleMatch) {
    const sale = store.sales.find((s) => s.id === getSaleMatch[1])
    if (!sale) {
      return problemResponse(404, 'NOT_FOUND', 'Sale not found.', url.pathname)
    }
    return jsonResponse(sale)
  }

  // 12. GET /pos/sales/{id}/receipt
  const receiptMatch = path.match(/^\/pos\/sales\/([^/]+)\/receipt$/)
  if (method === 'GET' && receiptMatch) {
    const sale = store.sales.find((s) => s.id === receiptMatch[1])
    if (!sale) {
      return problemResponse(404, 'NOT_FOUND', 'Sale not found.', url.pathname)
    }
    return jsonResponse({ ...sale.receipt, isDuplicate: false })
  }

  // 13. POST /pos/sales/{id}/reprint
  const reprintMatch = path.match(/^\/pos\/sales\/([^/]+)\/reprint$/)
  if (method === 'POST' && reprintMatch) {
    const sale = store.sales.find((s) => s.id === reprintMatch[1])
    if (!sale) {
      return problemResponse(404, 'NOT_FOUND', 'Sale not found.', url.pathname)
    }
    const nextCount = (store.reprintCounts.get(sale.id) ?? 0) + 1
    store.reprintCounts.set(sale.id, nextCount)
    return jsonResponse({ ...sale.receipt, isDuplicate: true, reprintCount: nextCount })
  }

  // 14. GET /pos/sales/{id}/returns
  const listReturnsMatch = path.match(/^\/pos\/sales\/([^/]+)\/returns$/)
  if (method === 'GET' && listReturnsMatch) {
    const saleId = listReturnsMatch[1]
    const sale = store.sales.find((s) => s.id === saleId)
    if (!sale) {
      return problemResponse(404, 'NOT_FOUND', 'Sale not found.', url.pathname)
    }
    return jsonResponse(store.returnsBySale.get(saleId) ?? [])
  }

  // 15. POST /pos/sales/{id}/returns
  const returnMatch = path.match(/^\/pos\/sales\/([^/]+)\/returns$/)
  if (method === 'POST' && returnMatch) {
    const saleId = returnMatch[1]
    const sale = store.sales.find((s) => s.id === saleId)
    if (!sale) {
      return problemResponse(404, 'NOT_FOUND', 'Sale not found.', url.pathname)
    }

    const req = body as unknown as CreateReturnRequest
    const idemKey = readHeader(init?.headers, 'Idempotency-Key') ?? req.idempotencyKey
    if (idemKey) {
      const normalized = JSON.stringify({ ...req, idempotencyKey: undefined })
      const existing = store.idempotency.get(`ret:${saleId}:${idemKey}`)
      if (existing) {
        if (existing.payloadHash !== normalized) {
          return problemResponse(
            409,
            'IDEMPOTENCY_CONFLICT',
            'Idempotency-Key was reused with a different return payload.',
            url.pathname,
          )
        }
        return jsonResponse(existing.response, existing.status)
      }
    }

    if (req.reason === 'OTHER' && (!req.note || !req.note.trim())) {
      return problemResponse(422, 'VALIDATION_FAILED', 'Note is required when reason is OTHER.', url.pathname)
    }
    if (!req.lines || req.lines.length === 0) {
      return problemResponse(422, 'VALIDATION_FAILED', 'Select at least one line to return.', url.pathname)
    }

    const priorReturns = store.returnsBySale.get(saleId) ?? []
    const returnedQtyByLine = new Map<string, number>()
    for (const r of priorReturns) {
      for (const rl of r.lines) {
        returnedQtyByLine.set(rl.saleLineId, (returnedQtyByLine.get(rl.saleLineId) ?? 0) + rl.quantity)
      }
    }

    let totalRefundCents = 0
    const returnLines: SaleReturnRecord['lines'] = []

    for (let i = 0; i < req.lines.length; i++) {
      const lineReq = req.lines[i]
      const saleLine = sale.lines.find((l) => l.id === lineReq.saleLineId)
      if (!saleLine) {
        return problemResponse(404, 'NOT_FOUND', 'Sale line not found.', url.pathname)
      }
      const alreadyReturned = returnedQtyByLine.get(saleLine.id) ?? 0
      const remaining = saleLine.quantity - alreadyReturned
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
          'RETURN_QUANTITY_EXCEEDED',
          `Return quantity for ${saleLine.sku} must be between 1 and ${remaining}.`,
          url.pathname,
        )
      }
      const perUnitCents = Math.round(toCents(saleLine.lineTotal) / saleLine.quantity)
      const refundCents = perUnitCents * lineReq.quantity
      totalRefundCents += refundCents
      returnLines.push({
        id: `ret-line-${Date.now()}-${i}`,
        saleLineId: saleLine.id,
        variantId: saleLine.variantId,
        sku: saleLine.sku,
        quantity: lineReq.quantity,
        refundAmount: fromCents(refundCents),
        disposition: lineReq.disposition,
      })

      if (lineReq.disposition === 'INSPECTION') {
        const existingInsp = store.inspections.find(
          (item) => item.locationId === sale.locationId && item.variantId === saleLine.variantId,
        )
        if (existingInsp) {
          existingInsp.inInspection += lineReq.quantity
          existingInsp.onHand += lineReq.quantity
        } else {
          store.inspections.push({
            locationId: sale.locationId,
            variantId: saleLine.variantId,
            sku: saleLine.sku,
            productName: saleLine.productName,
            color: saleLine.color,
            size: saleLine.size,
            inInspection: lineReq.quantity,
            onHand: lineReq.quantity,
            available: 0,
          })
        }
      }
    }

    const record: SaleReturnRecord = {
      id: `ret-${store.nextReturnSeq}`,
      returnNumber: `RTN-2026-${String(store.nextReturnSeq++).padStart(6, '0')}`,
      saleId: sale.id,
      locationId: sale.locationId,
      returnedAt: new Date().toISOString(),
      actorUsername: 'counter.staff',
      reason: req.reason,
      note: req.note?.trim() || null,
      refundMethod: sale.paymentMethod,
      refundAmount: fromCents(totalRefundCents),
      lines: returnLines,
    }

    store.returnsBySale.set(sale.id, [record, ...priorReturns])

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

    if (idemKey) {
      store.idempotency.set(`ret:${saleId}:${idemKey}`, {
        payloadHash: JSON.stringify({ ...req, idempotencyKey: undefined }),
        response: record,
        status: 201,
      })
    }

    return jsonResponse(record, 201)
  }

  // 16. GET /inventory/inspections
  if (method === 'GET' && path === '/inventory/inspections') {
    const locationId = url.searchParams.get('locationId')
    const rows = locationId
      ? store.inspections.filter((i) => i.locationId === locationId && i.inInspection > 0)
      : store.inspections.filter((i) => i.inInspection > 0)
    return jsonResponse(rows)
  }

  // 17. POST /inventory/inspections/resolve
  if (method === 'POST' && path === '/inventory/inspections/resolve') {
    const req = body as unknown as ResolveInspectionRequest
    const row = store.inspections.find(
      (i) => i.locationId === req.locationId && i.variantId === req.variantId,
    )
    if (!row || req.quantity <= 0 || req.quantity > row.inInspection) {
      return problemResponse(422, 'VALIDATION_FAILED', 'Invalid inspection quantity.', url.pathname)
    }
    row.inInspection -= req.quantity
    if (req.outcome === 'RESTOCK') {
      row.available += req.quantity
    } else {
      row.onHand = Math.max(0, row.onHand - req.quantity)
    }
    const resp: ResolveInspectionResponse = {
      locationId: row.locationId,
      variantId: row.variantId,
      outcome: req.outcome,
      quantity: req.quantity,
      onHand: row.onHand,
      inInspection: row.inInspection,
      isFlagged: false,
      valueAtCost: '0.00',
    }
    return jsonResponse(resp)
  }

  return problemResponse(404, 'NOT_FOUND', `No mock route for ${method} ${path}`, url.pathname)
}

let installed = false

/**
 * Wraps `globalThis.fetch` for POS, lookup, approval PIN, and inspection endpoints
 * when explicitly enabled in dev (`VITE_POS_MOCKS=1`) or explicitly installed by a test.
 */
export function installPosMocks(): () => void {
  if (installed) {
    return () => {}
  }
  const originalFetch = globalThis.fetch.bind(globalThis)
  installed = true

  globalThis.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const rawUrl =
      typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url
    const parsed = new URL(rawUrl, 'http://localhost')

    if (
      parsed.pathname === '/api/v1/catalog/lookup' ||
      parsed.pathname === '/api/v1/auth/approval-pin' ||
      parsed.pathname.startsWith('/api/v1/pos/') ||
      parsed.pathname === '/api/v1/pos' ||
      parsed.pathname.startsWith('/api/v1/inventory/inspections')
    ) {
      return handleMockRequest(parsed, init)
    }

    return originalFetch(input, init)
  }

  return () => {
    globalThis.fetch = originalFetch
    installed = false
  }
}

export type MockPaymentMethod = PaymentMethod
