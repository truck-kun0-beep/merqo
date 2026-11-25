import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { fetchLocations } from '../api/business'
import {
  checkSalePrices,
  closeCashSession,
  createSale,
  createSaleReturn,
  fetchCurrentCashSession,
  fetchSaleByReceipt,
  openCashSession,
  posLookupBarcode,
  reprintSaleReceipt,
} from '../api/pos'
import type {
  BelowCostWarning,
  CashSessionSummary,
  PaymentMethod,
  PosDiscountType,
  PosLookupResponse,
  ReceiptData,
  SaleDetail,
  SaleReturnRecord,
} from '../api/models'
import { ApiError } from '../api/types'
import { formatMinorUnits, parseMinorUnits } from '../lib/money'
import { calculateCartTotals, validatePayment, type PosCartLine } from '../lib/posCart'
import { filterLocationsByPermission, hasLocationPermission } from '../lib/scopes'
import { useSession } from '../session/sessionContext'
import { ScanInput } from '../components/ScanInput'
import { Receipt } from '../components/Receipt'
import {
  Badge,
  Button,
  ConfirmDialog,
  DateText,
  ErrorState,
  FormField,
  Input,
  Modal,
  MoneyText,
  PageHeader,
  Select,
  StatusPill,
  useToast,
} from '../components/ui'

/**
 * Keyboard-first Point of Sale screen: barcode scanning, integer-money cart,
 * cashier discount limits + manager PIN modal, single-method payment,
 * cash session open/reconciliation close, receipt printing/reprint, and returns.
 */
export default function PosPage() {
  const { user } = useSession()
  const queryClient = useQueryClient()
  const { toast } = useToast()

  const locationsQuery = useQuery({ queryKey: ['locations'], queryFn: fetchLocations })
  const posLocations = useMemo(
    () => filterLocationsByPermission(user, 'POS_SELL', locationsQuery.data ?? []),
    [user, locationsQuery.data],
  )

  const [selectedLocationId, setSelectedLocationId] = useState('')
  const locationId = selectedLocationId || posLocations[0]?.id || ''

  const [cart, setCart] = useState<PosCartLine[]>([])
  const [billDiscountType, setBillDiscountType] = useState<PosDiscountType | null>(null)
  const [billDiscountValue, setBillDiscountValue] = useState('')
  const [belowCostWarnings, setBelowCostWarnings] = useState<BelowCostWarning[]>([])

  // Dialogs
  const [clearConfirmOpen, setClearConfirmOpen] = useState(false)
  const [payOpen, setPayOpen] = useState(false)
  const [pinModalOpen, setPinModalOpen] = useState(false)
  const [openSessionModal, setOpenSessionModal] = useState(false)
  const [closeSessionModal, setCloseSessionModal] = useState(false)
  const [receiptModal, setReceiptModal] = useState<{
    saleId?: string
    receipt: ReceiptData
  } | null>(null)
  const [lastReceipt, setLastReceipt] = useState<{
    saleId: string
    receipt: ReceiptData
  } | null>(null)
  const [returnModalOpen, setReturnModalOpen] = useState(false)
  const [saleError, setSaleError] = useState<unknown>(null)

  const [pendingPayment, setPendingPayment] = useState<{
    method: PaymentMethod
    amountGiven: string
    trxId: string
    cardReference: string
  } | null>(null)

  const scanContainerRef = useRef<HTMLDivElement>(null)

  const anyDialogOpen =
    clearConfirmOpen ||
    payOpen ||
    pinModalOpen ||
    openSessionModal ||
    closeSessionModal ||
    Boolean(receiptModal) ||
    returnModalOpen

  const sessionQuery = useQuery({
    queryKey: ['pos', 'session', locationId],
    queryFn: () => fetchCurrentCashSession(locationId),
    enabled: Boolean(locationId),
  })
  const cashSession = sessionQuery.data?.session ?? null

  const canDiscount = hasLocationPermission(user, 'POS_DISCOUNT', locationId)
  const canApproveDiscount = hasLocationPermission(user, 'POS_DISCOUNT_APPROVE', locationId)
  const canCashSession = hasLocationPermission(user, 'POS_CASH_SESSION', locationId)
  const canReprint = hasLocationPermission(user, 'POS_REPRINT', locationId)
  const canRefund = hasLocationPermission(user, 'POS_REFUND', locationId)

  const maxDiscountPercent = Number(user?.maxDiscountPercent ?? '0') || 0
  const totals = useMemo(
    () => calculateCartTotals(cart, billDiscountType, billDiscountValue),
    [cart, billDiscountType, billDiscountValue],
  )

  const priceCheckMutation = useMutation({
    mutationFn: checkSalePrices,
    onSuccess: (res) => setBelowCostWarnings(res.belowCostWarnings ?? []),
  })

  function triggerServerPriceCheck(
    nextCart: PosCartLine[],
    nextBillType: PosDiscountType | null,
    nextBillVal: string,
  ) {
    if (!locationId || nextCart.length === 0) {
      setBelowCostWarnings([])
      return
    }
    priceCheckMutation.mutate({
      locationId,
      lines: nextCart.map((l) => ({
        variantId: l.variantId,
        quantity: l.quantity,
        manualDiscountType: l.manualDiscountType,
        manualDiscountValue: l.manualDiscountValue || null,
      })),
      billDiscountType: nextBillType,
      billDiscountValue: nextBillVal || null,
    })
  }

  function addScannedItem(item: PosLookupResponse) {
    setSaleError(null)
    setCart((prev) => {
      const existingIndex = prev.findIndex((line) => line.variantId === item.variantId)
      let next: PosCartLine[]
      if (existingIndex >= 0) {
        next = prev.map((line, idx) =>
          idx === existingIndex ? { ...line, quantity: line.quantity + 1 } : line,
        )
      } else {
        next = [
          ...prev,
          {
            variantId: item.variantId,
            productName: item.productName,
            sku: item.sku,
            variantLabel: item.variantLabel,
            unitPrice: item.listPrice,
            promotionName: item.promotionName,
            promotionDiscount: item.promotionDiscount,
            taxRatePercent: item.taxRatePercent,
            taxInclusive: item.taxInclusive,
            availableStock: item.availableStock,
            quantity: 1,
            manualDiscountType: null,
            manualDiscountValue: '',
          },
        ]
      }
      if (item.belowCost) {
        triggerServerPriceCheck(next, billDiscountType, billDiscountValue)
      }
      return next
    })
  }

  function updateLineQty(variantId: string, nextQty: number) {
    if (Number.isNaN(nextQty) || nextQty < 1) return
    const qty = Math.trunc(nextQty)
    setCart((prev) => {
      const next = prev.map((l) => (l.variantId === variantId ? { ...l, quantity: qty } : l))
      triggerServerPriceCheck(next, billDiscountType, billDiscountValue)
      return next
    })
  }

  function updateLineDiscount(variantId: string, type: PosDiscountType | null, value: string) {
    setCart((prev) => {
      const next = prev.map((l) =>
        l.variantId === variantId
          ? { ...l, manualDiscountType: type, manualDiscountValue: value }
          : l,
      )
      triggerServerPriceCheck(next, billDiscountType, billDiscountValue)
      return next
    })
  }

  function removeLine(variantId: string) {
    setCart((prev) => {
      const next = prev.filter((l) => l.variantId !== variantId)
      triggerServerPriceCheck(next, billDiscountType, billDiscountValue)
      return next
    })
  }

  function clearCart() {
    setCart([])
    setBillDiscountType(null)
    setBillDiscountValue('')
    setBelowCostWarnings([])
    setSaleError(null)
    setClearConfirmOpen(false)
  }

  const saleMutation = useMutation({
    mutationFn: async (input: {
      payment: {
        method: PaymentMethod
        amountGiven: string
        trxId: string
        cardReference: string
      }
      managerPin?: string
    }) => {
      if (!cashSession) {
        throw new ApiError({
          type: 'about:blank',
          title: 'Cash session required',
          status: 409,
          detail: 'Open a cash session before completing a sale.',
          instance: '/api/v1/sales',
          errorCode: 'POS_SESSION_REQUIRED',
          correlationId: 'client-check',
          timestamp: new Date().toISOString(),
          fieldErrors: {},
          errors: [],
        })
      }
      return createSale({
        locationId,
        cashSessionId: cashSession.id,
        lines: cart.map((l) => ({
          variantId: l.variantId,
          quantity: l.quantity,
          manualDiscountType: l.manualDiscountType,
          manualDiscountValue: l.manualDiscountValue || null,
        })),
        billDiscountType,
        billDiscountValue: billDiscountValue || null,
        payment: {
          method: input.payment.method,
          amountGiven: input.payment.method === 'CASH' ? input.payment.amountGiven : null,
          trxId:
            input.payment.method === 'BKASH' || input.payment.method === 'NAGAD'
              ? input.payment.trxId.trim()
              : null,
          cardReference:
            input.payment.method === 'CARD' ? input.payment.cardReference.trim() : null,
        },
        managerPin: input.managerPin ?? null,
      })
    },
    onSuccess: (completed: SaleDetail) => {
      setPayOpen(false)
      setPinModalOpen(false)
      setPendingPayment(null)
      setSaleError(null)
      setCart([])
      setBillDiscountType(null)
      setBillDiscountValue('')
      setBelowCostWarnings(completed.belowCostWarnings ?? [])
      setLastReceipt({ saleId: completed.id, receipt: completed.receipt })
      setReceiptModal({ saleId: completed.id, receipt: completed.receipt })
      queryClient.invalidateQueries({ queryKey: ['pos', 'session', locationId] })
      toast({ title: `Sale ${completed.receiptNumber} completed`, tone: 'success' })
    },
    onError: (caught) => {
      if (caught instanceof ApiError && caught.errorCode === 'DISCOUNT_LIMIT_EXCEEDED') {
        setPayOpen(false)
        setPinModalOpen(true)
      } else {
        setSaleError(caught)
      }
    },
  })

  const reprintMutation = useMutation({
    mutationFn: (saleId: string) => reprintSaleReceipt(saleId),
    onSuccess: (dupReceipt, saleId) => {
      setReceiptModal({ saleId, receipt: dupReceipt })
    },
  })

  // Keyboard shortcuts: F2 focus scan, F4 pay, Ctrl+Backspace clear cart
  useEffect(() => {
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'F2') {
        event.preventDefault()
        const input = scanContainerRef.current?.querySelector('input')
        input?.focus()
      } else if (event.key === 'F4') {
        event.preventDefault()
        if (!anyDialogOpen && cart.length > 0 && cashSession) {
          setPayOpen(true)
        }
      } else if (event.ctrlKey && event.key === 'Backspace') {
        if (cart.length > 0 && !anyDialogOpen) {
          event.preventDefault()
          setClearConfirmOpen(true)
        }
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [anyDialogOpen, cart.length, cashSession])

  function handleAttemptPayment(payment: {
    method: PaymentMethod
    amountGiven: string
    trxId: string
    cardReference: string
  }) {
    setSaleError(null)
    if (totals.effectiveManualDiscountPercent > maxDiscountPercent && !canApproveDiscount) {
      setPendingPayment(payment)
      setPayOpen(false)
      setPinModalOpen(true)
      return
    }
    setPendingPayment(payment)
    saleMutation.mutate({ payment })
  }

  return (
    <div className="space-y-4">
      <PageHeader
        title="Point of Sale"
        description="Keyboard-first counter checkout."
        actions={
          <div className="flex flex-wrap items-center gap-2">
            {posLocations.length > 1 ? (
              <Select
                aria-label="POS location"
                value={locationId}
                onChange={(e) => {
                  setSelectedLocationId(e.target.value)
                  clearCart()
                }}
                className="w-48"
              >
                {posLocations.map((loc) => (
                  <option key={loc.id} value={loc.id}>
                    {loc.name} ({loc.code})
                  </option>
                ))}
              </Select>
            ) : null}

            {lastReceipt ? (
              <Button variant="outline" size="sm" onClick={() => setReceiptModal(lastReceipt)}>
                Last receipt ({lastReceipt.receipt.receiptNumber})
              </Button>
            ) : null}

            <Button variant="outline" size="sm" onClick={() => setReturnModalOpen(true)}>
              Lookup / Return sale
            </Button>
          </div>
        }
      />

      {/* Cash session status bar */}
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-slate-200 bg-white px-4 py-3 shadow-xs">
        <div className="flex flex-wrap items-center gap-3 text-sm">
          <span className="font-medium text-slate-700">Cash session:</span>
          {sessionQuery.isPending ? (
            <span className="text-xs text-slate-400">Checking...</span>
          ) : cashSession ? (
            <>
              <StatusPill status="ACTIVE" label="Open" />
              <span className="text-xs text-slate-600">
                Opening: <MoneyText amount={cashSession.openingCash} /> · Expected cash:{' '}
                <MoneyText
                  amount={cashSession.expectedCash}
                  className="font-semibold text-slate-900"
                />{' '}
                · {cashSession.saleCount} sale(s)
              </span>
            </>
          ) : (
            <>
              <Badge tone="warning">No active session</Badge>
              <span className="text-xs text-slate-500">
                Open a cash session before ringing up sales.
              </span>
            </>
          )}
        </div>
        {canCashSession ? (
          <div className="flex items-center gap-2">
            {!cashSession ? (
              <Button size="sm" onClick={() => setOpenSessionModal(true)}>
                Open cash session
              </Button>
            ) : (
              <Button variant="outline" size="sm" onClick={() => setCloseSessionModal(true)}>
                Close session
              </Button>
            )}
          </div>
        ) : null}
      </div>

      {/* Keyboard shortcut legend */}
      <div
        aria-label="Keyboard shortcuts"
        className="flex flex-wrap items-center gap-x-4 gap-y-1 rounded-lg border border-slate-200 bg-slate-100/70 px-3 py-1.5 text-xs text-slate-600"
      >
        <span>
          <kbd className="rounded border border-slate-300 bg-white px-1.5 py-0.5 font-mono text-[11px]">
            F2
          </kbd>{' '}
          Focus scan
        </span>
        <span>
          <kbd className="rounded border border-slate-300 bg-white px-1.5 py-0.5 font-mono text-[11px]">
            F4
          </kbd>{' '}
          Pay
        </span>
        <span>
          <kbd className="rounded border border-slate-300 bg-white px-1.5 py-0.5 font-mono text-[11px]">
            Esc
          </kbd>{' '}
          Close dialog
        </span>
        <span>
          <kbd className="rounded border border-slate-300 bg-white px-1.5 py-0.5 font-mono text-[11px]">
            Ctrl+Backspace
          </kbd>{' '}
          Clear cart
        </span>
      </div>

      {/* Main POS Workspace */}
      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_360px]">
        <div className="min-w-0 space-y-4">
          <div
            ref={scanContainerRef}
            className="rounded-xl border border-slate-200 bg-white p-4 shadow-xs"
          >
            <ScanInput<PosLookupResponse>
              label="Scan or enter barcode"
              placeholder="Scan item barcode and press Enter..."
              submitLabel="Add"
              scope="POS"
              locationId={locationId}
              restoreFocusWhen={!anyDialogOpen}
              onScan={(code) => posLookupBarcode(code, locationId)}
              onSuccess={addScannedItem}
            />
          </div>

          {belowCostWarnings.length > 0 ? (
            <div
              role="alert"
              className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-950"
            >
              <p className="font-semibold">Below-cost warning from server</p>
              <ul className="mt-1 list-disc pl-5 text-xs">
                {belowCostWarnings.map((w) => (
                  <li key={w.variantId}>
                    <span className="font-mono font-semibold">{w.sku}</span>: net{' '}
                    <MoneyText amount={w.netUnitPrice} /> is below unit cost{' '}
                    <MoneyText amount={w.costPrice} />
                  </li>
                ))}
              </ul>
            </div>
          ) : null}

          {saleError ? <ErrorState title="Could not complete sale" error={saleError} /> : null}

          <div className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-xs">
            <div className="flex items-center justify-between border-b border-slate-200 px-4 py-3">
              <h2 className="text-sm font-semibold text-slate-900">
                Cart ({cart.reduce((sum, l) => sum + l.quantity, 0)} items)
              </h2>
              {cart.length > 0 ? (
                <Button
                  variant="ghost"
                  size="sm"
                  className="text-rose-700 hover:bg-rose-50"
                  onClick={() => setClearConfirmOpen(true)}
                >
                  Clear cart
                </Button>
              ) : null}
            </div>

            {cart.length === 0 ? (
              <div className="px-6 py-12 text-center text-sm text-slate-500">
                Cart is empty. Scan a barcode to start a sale.
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="min-w-full divide-y divide-slate-200 text-sm">
                  <thead className="bg-slate-50 text-xs font-medium tracking-wide text-slate-500 uppercase">
                    <tr>
                      <th scope="col" className="px-4 py-2.5 text-left">
                        Item
                      </th>
                      <th scope="col" className="px-3 py-2.5 text-center">
                        Qty
                      </th>
                      <th scope="col" className="px-3 py-2.5 text-right">
                        Price
                      </th>
                      <th scope="col" className="px-3 py-2.5 text-right">
                        Promo
                      </th>
                      {canDiscount ? (
                        <th scope="col" className="px-3 py-2.5 text-left">
                          Line disc
                        </th>
                      ) : null}
                      <th scope="col" className="px-3 py-2.5 text-right">
                        Line total
                      </th>
                      <th scope="col" className="px-3 py-2.5 text-right">
                        <span className="sr-only">Remove</span>
                      </th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {cart.map((line, index) => {
                      const computed = totals.lines[index]
                      return (
                        <tr key={line.variantId}>
                          <td className="px-4 py-3">
                            <p className="font-medium text-slate-900">{line.productName}</p>
                            <p className="text-xs text-slate-500">
                              {line.variantLabel} · <span className="font-mono">{line.sku}</span>
                            </p>
                            {line.quantity > line.availableStock ? (
                              <Badge tone="danger" className="mt-1">
                                Stock: {line.availableStock}
                              </Badge>
                            ) : null}
                          </td>
                          <td className="px-3 py-3 text-center">
                            <div className="inline-flex items-center gap-1">
                              <Button
                                size="sm"
                                variant="outline"
                                aria-label={`Decrease quantity for ${line.sku}`}
                                onClick={() => updateLineQty(line.variantId, line.quantity - 1)}
                                disabled={line.quantity <= 1}
                                className="h-9 w-9 px-0"
                              >
                                -
                              </Button>
                              <Input
                                type="number"
                                min="1"
                                step="1"
                                aria-label={`Quantity for ${line.sku}`}
                                value={line.quantity}
                                onChange={(e) =>
                                  updateLineQty(line.variantId, Number(e.target.value))
                                }
                                className="h-9 w-14 text-center"
                              />
                              <Button
                                size="sm"
                                variant="outline"
                                aria-label={`Increase quantity for ${line.sku}`}
                                onClick={() => updateLineQty(line.variantId, line.quantity + 1)}
                                className="h-9 w-9 px-0"
                              >
                                +
                              </Button>
                            </div>
                          </td>
                          <td className="px-3 py-3 text-right">
                            <MoneyText amount={line.unitPrice} />
                          </td>
                          <td className="px-3 py-3 text-right text-emerald-700">
                            {computed.promoDiscountMinor > 0 ? (
                              <MoneyText amount={formatMinorUnits(-computed.promoDiscountMinor)} />
                            ) : (
                              <span className="text-slate-400">-</span>
                            )}
                          </td>
                          {canDiscount ? (
                            <td className="px-3 py-3">
                              <div className="flex items-center gap-1">
                                <Select
                                  aria-label={`Discount type for ${line.sku}`}
                                  value={line.manualDiscountType ?? ''}
                                  onChange={(e) =>
                                    updateLineDiscount(
                                      line.variantId,
                                      (e.target.value || null) as PosDiscountType | null,
                                      line.manualDiscountValue,
                                    )
                                  }
                                  className="h-8 w-20 px-1.5 py-1 text-xs"
                                >
                                  <option value="">None</option>
                                  <option value="PERCENT">%</option>
                                  <option value="FIXED">৳</option>
                                </Select>
                                {line.manualDiscountType ? (
                                  <Input
                                    type="number"
                                    min="0"
                                    step="0.01"
                                    aria-label={`Discount value for ${line.sku}`}
                                    value={line.manualDiscountValue}
                                    onChange={(e) =>
                                      updateLineDiscount(
                                        line.variantId,
                                        line.manualDiscountType,
                                        e.target.value,
                                      )
                                    }
                                    className="h-8 w-20 px-2 py-1 text-xs"
                                  />
                                ) : null}
                              </div>
                            </td>
                          ) : null}
                          <td className="px-3 py-3 text-right font-semibold text-slate-900">
                            <MoneyText amount={formatMinorUnits(computed.lineTotalMinor)} />
                          </td>
                          <td className="px-3 py-3 text-right">
                            <button
                              type="button"
                              onClick={() => removeLine(line.variantId)}
                              className="text-xs font-medium text-rose-700 hover:underline"
                            >
                              Remove
                            </button>
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>

        {/* Totals & Checkout Sidebar */}
        <aside className="space-y-4">
          <div className="space-y-4 rounded-xl border border-slate-200 bg-white p-5 shadow-xs">
            <h2 className="text-base font-semibold text-slate-900">Summary</h2>

            {canDiscount ? (
              <div className="space-y-2 rounded-lg border border-slate-200 bg-slate-50 p-3">
                <p className="text-xs font-medium text-slate-700">
                  Whole-bill discount (limit: {maxDiscountPercent}%)
                </p>
                <div className="flex items-center gap-2">
                  <Select
                    aria-label="Bill discount type"
                    value={billDiscountType ?? ''}
                    onChange={(e) => {
                      const nextType = (e.target.value || null) as PosDiscountType | null
                      setBillDiscountType(nextType)
                      triggerServerPriceCheck(cart, nextType, billDiscountValue)
                    }}
                    className="w-24"
                  >
                    <option value="">None</option>
                    <option value="PERCENT">%</option>
                    <option value="FIXED">৳</option>
                  </Select>
                  {billDiscountType ? (
                    <Input
                      type="number"
                      min="0"
                      step="0.01"
                      aria-label="Bill discount value"
                      value={billDiscountValue}
                      onChange={(e) => {
                        setBillDiscountValue(e.target.value)
                        triggerServerPriceCheck(cart, billDiscountType, e.target.value)
                      }}
                    />
                  ) : null}
                </div>
                {totals.effectiveManualDiscountPercent > maxDiscountPercent &&
                !canApproveDiscount ? (
                  <p className="text-xs font-medium text-amber-800">
                    Exceeds your {maxDiscountPercent}% limit (
                    {totals.effectiveManualDiscountPercent}%). Manager PIN will be requested at
                    checkout.
                  </p>
                ) : null}
              </div>
            ) : null}

            <dl className="space-y-2 text-sm tabular-nums">
              <div className="flex justify-between text-slate-600">
                <dt>Subtotal</dt>
                <dd>
                  <MoneyText amount={formatMinorUnits(totals.subtotalMinor)} />
                </dd>
              </div>
              <div className="flex justify-between text-slate-600">
                <dt>Discounts</dt>
                <dd className="text-emerald-700">
                  {totals.discountTotalMinor > 0 ? (
                    <MoneyText amount={formatMinorUnits(-totals.discountTotalMinor)} />
                  ) : (
                    <MoneyText amount="0.00" />
                  )}
                </dd>
              </div>
              <div className="flex justify-between text-slate-600">
                <dt>Tax</dt>
                <dd>
                  <MoneyText amount={formatMinorUnits(totals.taxTotalMinor)} />
                </dd>
              </div>
              <div className="flex justify-between border-t border-slate-200 pt-3 text-lg font-bold text-slate-900">
                <dt>Total</dt>
                <dd>
                  <MoneyText amount={formatMinorUnits(totals.totalMinor)} />
                </dd>
              </div>
            </dl>

            <Button
              size="lg"
              className="w-full"
              disabled={cart.length === 0 || !locationId}
              onClick={() => {
                if (!cashSession) {
                  setSaleError(
                    new ApiError({
                      type: 'about:blank',
                      title: 'Cash session required',
                      status: 409,
                      detail: 'Open a cash session before paying.',
                      instance: '/api/v1/sales',
                      errorCode: 'POS_SESSION_REQUIRED',
                      correlationId: 'client-check',
                      timestamp: new Date().toISOString(),
                      fieldErrors: {},
                      errors: [],
                    }),
                  )
                  return
                }
                setPayOpen(true)
              }}
            >
              Pay <MoneyText amount={formatMinorUnits(totals.totalMinor)} /> (F4)
            </Button>
          </div>
        </aside>
      </div>

      {/* Clear Cart Confirm */}
      <ConfirmDialog
        open={clearConfirmOpen}
        onClose={() => setClearConfirmOpen(false)}
        onConfirm={clearCart}
        title="Clear cart?"
        confirmLabel="Clear cart"
        confirmVariant="danger"
      >
        <p>Remove all {cart.length} line(s) from the current cart?</p>
      </ConfirmDialog>

      {/* Payment Modal */}
      {payOpen ? (
        <PaymentModal
          totalMinor={totals.totalMinor}
          isSubmitting={saleMutation.isPending}
          error={saleMutation.error}
          onClose={() => setPayOpen(false)}
          onSubmit={handleAttemptPayment}
        />
      ) : null}

      {/* Manager Approval PIN Modal */}
      {pinModalOpen && pendingPayment ? (
        <ManagerPinModal
          cashierLimit={maxDiscountPercent}
          appliedPercent={totals.effectiveManualDiscountPercent}
          isSubmitting={saleMutation.isPending}
          error={saleMutation.error}
          onClose={() => {
            setPinModalOpen(false)
            setPendingPayment(null)
          }}
          onApprove={(pin) => saleMutation.mutate({ payment: pendingPayment, managerPin: pin })}
        />
      ) : null}

      {/* Open Cash Session Modal */}
      {openSessionModal ? (
        <OpenCashSessionModal
          locationId={locationId}
          onClose={() => setOpenSessionModal(false)}
          onOpened={() => {
            queryClient.invalidateQueries({ queryKey: ['pos', 'session', locationId] })
            setSaleError(null)
          }}
        />
      ) : null}

      {/* Close Cash Session Reconciliation Modal */}
      {closeSessionModal && cashSession ? (
        <CloseCashSessionModal
          session={cashSession}
          onClose={() => setCloseSessionModal(false)}
          onClosed={() =>
            queryClient.invalidateQueries({ queryKey: ['pos', 'session', locationId] })
          }
        />
      ) : null}

      {/* Receipt Modal */}
      {receiptModal ? (
        <Modal
          open
          onClose={() => setReceiptModal(null)}
          title={`Receipt · ${receiptModal.receipt.receiptNumber}`}
          size="md"
          footer={
            <div className="flex w-full flex-wrap items-center justify-between gap-2">
              <span className="text-xs text-slate-500">
                Tip: choose &ldquo;Save as PDF&rdquo; in your browser print dialog to export.
              </span>
              <div className="flex gap-2">
                {receiptModal.saleId && canReprint && !receiptModal.receipt.isDuplicate ? (
                  <Button
                    variant="outline"
                    onClick={() => reprintMutation.mutate(receiptModal.saleId!)}
                    isLoading={reprintMutation.isPending}
                  >
                    Reprint (DUPLICATE)
                  </Button>
                ) : null}
                <Button onClick={() => window.print()}>Print receipt</Button>
              </div>
            </div>
          }
        >
          <Receipt data={receiptModal.receipt} paperWidth="80mm" />
        </Modal>
      ) : null}

      {/* Sale Lookup & Return Modal */}
      {returnModalOpen ? (
        <SaleLookupAndReturnModal
          canRefund={canRefund}
          canReprint={canReprint}
          onReprint={(saleId) => reprintMutation.mutate(saleId)}
          onClose={() => setReturnModalOpen(false)}
          onReturned={() =>
            queryClient.invalidateQueries({ queryKey: ['pos', 'session', locationId] })
          }
        />
      ) : null}
    </div>
  )
}

function PaymentModal({
  totalMinor,
  isSubmitting,
  error,
  onClose,
  onSubmit,
}: {
  totalMinor: number
  isSubmitting: boolean
  error: unknown
  onClose: () => void
  onSubmit: (payment: {
    method: PaymentMethod
    amountGiven: string
    trxId: string
    cardReference: string
  }) => void
}) {
  const [method, setMethod] = useState<PaymentMethod>('CASH')
  const [amountGiven, setAmountGiven] = useState(formatMinorUnits(totalMinor))
  const [trxId, setTrxId] = useState('')
  const [cardReference, setCardReference] = useState('')
  const [validationError, setValidationError] = useState<string | null>(null)

  const liveChangeMinor = method === 'CASH' ? parseMinorUnits(amountGiven) - totalMinor : 0

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const result = validatePayment(method, totalMinor, {
      amountGiven,
      trxId,
      cardReference,
    })
    if (!result.valid) {
      setValidationError(result.error ?? 'Invalid payment details.')
      return
    }
    setValidationError(null)
    onSubmit({ method, amountGiven, trxId, cardReference })
  }

  return (
    <Modal open onClose={onClose} title="Complete payment">
      <form onSubmit={handleSubmit} className="space-y-4">
        <div className="rounded-lg border border-slate-200 bg-slate-50 px-4 py-3 text-center">
          <p className="text-xs font-medium text-slate-500 uppercase">Amount due</p>
          <p className="mt-1 text-2xl font-bold text-slate-900">
            <MoneyText amount={formatMinorUnits(totalMinor)} />
          </p>
        </div>

        <div
          className="grid grid-cols-2 gap-2 sm:grid-cols-4"
          role="radiogroup"
          aria-label="Payment method"
        >
          {(['CASH', 'BKASH', 'NAGAD', 'CARD'] as const).map((m) => (
            <Button
              key={m}
              type="button"
              variant={method === m ? 'primary' : 'outline'}
              onClick={() => {
                setMethod(m)
                setValidationError(null)
              }}
            >
              {m === 'BKASH' ? 'bKash' : m === 'NAGAD' ? 'Nagad' : m === 'CARD' ? 'Card' : 'Cash'}
            </Button>
          ))}
        </div>

        {method === 'CASH' ? (
          <div className="space-y-2">
            <FormField label="Cash given" required>
              <Input
                autoFocus
                type="number"
                step="0.01"
                min="0"
                value={amountGiven}
                onChange={(e) => setAmountGiven(e.target.value)}
              />
            </FormField>
            <p className="text-sm text-slate-700">
              Change due:{' '}
              <strong className="tabular-nums">
                <MoneyText amount={formatMinorUnits(Math.max(0, liveChangeMinor))} />
              </strong>
            </p>
          </div>
        ) : null}

        {method === 'BKASH' || method === 'NAGAD' ? (
          <FormField label={`${method === 'BKASH' ? 'bKash' : 'Nagad'} TrxID`} required>
            <Input
              autoFocus
              value={trxId}
              placeholder="e.g. BKX901234"
              onChange={(e) => setTrxId(e.target.value)}
            />
          </FormField>
        ) : null}

        {method === 'CARD' ? (
          <FormField
            label="Approval code or last 4 digits"
            hint="Never enter a full card number."
            required
          >
            <Input
              autoFocus
              value={cardReference}
              placeholder="e.g. 4242 or AUTH-991"
              onChange={(e) => setCardReference(e.target.value)}
            />
          </FormField>
        ) : null}

        {validationError ? (
          <p role="alert" className="text-sm font-medium text-rose-700">
            {validationError}
          </p>
        ) : null}
        {error ? <ErrorState title="Payment failed" error={error} /> : null}

        <div className="flex justify-end gap-2 pt-2">
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" isLoading={isSubmitting} loadingText="Completing...">
            Complete sale
          </Button>
        </div>
      </form>
    </Modal>
  )
}

function ManagerPinModal({
  cashierLimit,
  appliedPercent,
  isSubmitting,
  error,
  onClose,
  onApprove,
}: {
  cashierLimit: number
  appliedPercent: number
  isSubmitting: boolean
  error: unknown
  onClose: () => void
  onApprove: (pin: string) => void
}) {
  const [pin, setPin] = useState('')
  const [pinError, setPinError] = useState<string | null>(null)

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (!pin.trim()) {
      setPinError('Enter manager PIN.')
      return
    }
    setPinError(null)
    onApprove(pin.trim())
  }

  return (
    <Modal
      open
      onClose={onClose}
      size="sm"
      title="Manager discount approval"
      description={`Applied manual discount (${appliedPercent}%) exceeds your ${cashierLimit}% limit.`}
    >
      <form onSubmit={handleSubmit} className="space-y-4">
        <FormField label="Manager approval PIN" error={pinError ?? undefined} required>
          <Input
            autoFocus
            type="password"
            inputMode="numeric"
            value={pin}
            onChange={(e) => setPin(e.target.value)}
            placeholder="Enter PIN (mock: 1234)"
          />
        </FormField>

        {error ? <ErrorState title="Approval failed" error={error} /> : null}

        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" isLoading={isSubmitting} loadingText="Verifying...">
            Approve & complete
          </Button>
        </div>
      </form>
    </Modal>
  )
}

function OpenCashSessionModal({
  locationId,
  onClose,
  onOpened,
}: {
  locationId: string
  onClose: () => void
  onOpened: () => void
}) {
  const { toast } = useToast()
  const [openingCash, setOpeningCash] = useState('0.00')

  const mutation = useMutation({
    mutationFn: () => openCashSession({ locationId, openingCash: openingCash.trim() || '0.00' }),
    onSuccess: () => {
      toast({ title: 'Cash session opened', tone: 'success' })
      onOpened()
      onClose()
    },
  })

  return (
    <Modal open onClose={onClose} size="sm" title="Open cash session">
      <form
        onSubmit={(e) => {
          e.preventDefault()
          mutation.mutate()
        }}
        className="space-y-4"
      >
        <FormField label="Opening cash float (৳)" required>
          <Input
            autoFocus
            type="number"
            min="0"
            step="0.01"
            value={openingCash}
            onChange={(e) => setOpeningCash(e.target.value)}
          />
        </FormField>

        {mutation.isError ? (
          <ErrorState title="Could not open session" error={mutation.error} />
        ) : null}

        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" isLoading={mutation.isPending}>
            Open session
          </Button>
        </div>
      </form>
    </Modal>
  )
}

function CloseCashSessionModal({
  session,
  onClose,
  onClosed,
}: {
  session: CashSessionSummary
  onClose: () => void
  onClosed: () => void
}) {
  const { toast } = useToast()
  const [countedCash, setCountedCash] = useState(session.expectedCash)
  const [note, setNote] = useState('')
  const [closedReport, setClosedReport] = useState<CashSessionSummary | null>(null)

  const expectedMinor = parseMinorUnits(session.expectedCash)
  const countedMinor = parseMinorUnits(countedCash)
  const diffMinor = countedMinor - expectedMinor

  const mutation = useMutation({
    mutationFn: () =>
      closeCashSession(session.id, {
        countedCash: formatMinorUnits(countedMinor),
        note: note.trim() || null,
      }),
    onSuccess: (closed) => {
      setClosedReport(closed)
      toast({ title: 'Cash session closed', tone: 'info' })
      onClosed()
    },
  })

  const displaySession = closedReport ?? session

  return (
    <Modal
      open
      onClose={onClose}
      size="md"
      title={closedReport ? 'Session closed · Reconciliation' : 'Close cash session'}
    >
      <div className="space-y-4">
        <div className="overflow-hidden rounded-lg border border-slate-200">
          <table className="min-w-full divide-y divide-slate-200 text-sm tabular-nums">
            <thead className="bg-slate-50 text-xs font-medium text-slate-500 uppercase">
              <tr>
                <th className="px-3 py-2 text-left">Method</th>
                <th className="px-3 py-2 text-right">Expected</th>
                <th className="px-3 py-2 text-right">Counted</th>
                <th className="px-3 py-2 text-right">Difference</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {displaySession.byMethod.map((row) => {
                const isCash = row.method === 'CASH'
                const rowCounted = closedReport
                  ? row.counted
                  : isCash
                    ? formatMinorUnits(countedMinor)
                    : row.expected
                const rowDiff = closedReport
                  ? row.difference
                  : isCash
                    ? formatMinorUnits(diffMinor)
                    : '0.00'
                return (
                  <tr key={row.method}>
                    <td className="px-3 py-2 font-medium text-slate-800">{row.method}</td>
                    <td className="px-3 py-2 text-right">
                      <MoneyText amount={row.expected} />
                    </td>
                    <td className="px-3 py-2 text-right">
                      <MoneyText amount={rowCounted} />
                    </td>
                    <td
                      className={`px-3 py-2 text-right font-semibold ${
                        parseMinorUnits(rowDiff) < 0
                          ? 'text-rose-700'
                          : parseMinorUnits(rowDiff) > 0
                            ? 'text-emerald-700'
                            : 'text-slate-600'
                      }`}
                    >
                      <MoneyText amount={rowDiff} />
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>

        {!closedReport ? (
          <form
            onSubmit={(e) => {
              e.preventDefault()
              mutation.mutate()
            }}
            className="space-y-3"
          >
            <FormField label="Counted cash in drawer (৳)" required>
              <Input
                autoFocus
                type="number"
                min="0"
                step="0.01"
                value={countedCash}
                onChange={(e) => setCountedCash(e.target.value)}
              />
            </FormField>
            <FormField label="Shift note (optional)">
              <Input value={note} onChange={(e) => setNote(e.target.value)} />
            </FormField>

            {mutation.isError ? (
              <ErrorState title="Could not close session" error={mutation.error} />
            ) : null}

            <div className="flex justify-end gap-2 pt-2">
              <Button variant="outline" onClick={onClose}>
                Cancel
              </Button>
              <Button type="submit" isLoading={mutation.isPending}>
                Close & reconcile
              </Button>
            </div>
          </form>
        ) : (
          <div className="flex justify-end">
            <Button onClick={onClose}>Done</Button>
          </div>
        )}
      </div>
    </Modal>
  )
}

function SaleLookupAndReturnModal({
  canRefund,
  canReprint,
  onReprint,
  onClose,
  onReturned,
}: {
  canRefund: boolean
  canReprint: boolean
  onReprint: (saleId: string) => void
  onClose: () => void
  onReturned: () => void
}) {
  const [sale, setSale] = useState<SaleDetail | null>(null)
  const [returnQty, setReturnQty] = useState<Record<string, string>>({})
  const [reason, setReason] = useState('')
  const [refundResult, setRefundResult] = useState<SaleReturnRecord | null>(null)
  const [validationError, setValidationError] = useState<string | null>(null)

  const returnMutation = useMutation({
    mutationFn: () => {
      const selectedLines = (sale?.lines ?? [])
        .map((line) => ({
          saleLineId: line.id,
          quantity: Number(returnQty[line.id] ?? '0'),
        }))
        .filter((l) => l.quantity > 0)
      return createSaleReturn(sale!.id, {
        reason: reason.trim(),
        lines: selectedLines,
      })
    },
    onSuccess: (record) => {
      setRefundResult(record)
      onReturned()
    },
  })

  function handleReturnSubmit(event: FormEvent) {
    event.preventDefault()
    if (!sale) return
    if (!reason.trim()) {
      setValidationError('Enter a reason for the return.')
      return
    }
    const chosen = sale.lines
      .map((line) => ({
        line,
        qty: Number(returnQty[line.id] ?? '0'),
        remaining: line.quantity - line.returnedQuantity,
      }))
      .filter((entry) => entry.qty > 0)

    if (chosen.length === 0) {
      setValidationError('Enter a return quantity for at least one line.')
      return
    }
    for (const entry of chosen) {
      if (!Number.isInteger(entry.qty) || entry.qty > entry.remaining) {
        setValidationError(
          `Return quantity for ${entry.line.sku} cannot exceed ${entry.remaining}.`,
        )
        return
      }
    }
    setValidationError(null)
    returnMutation.mutate()
  }

  return (
    <Modal open onClose={onClose} size="lg" title="Sale lookup & return">
      <div className="space-y-4">
        <ScanInput<SaleDetail>
          label="Scan or enter receipt number"
          placeholder="e.g. MQ-2026-000101"
          submitLabel="Find receipt"
          onScan={(code) => fetchSaleByReceipt(code)}
          onSuccess={(found) => {
            setSale(found)
            setRefundResult(null)
            setReturnQty({})
            setReason('')
            setValidationError(null)
          }}
        />

        {sale && !refundResult ? (
          <div className="space-y-4 rounded-xl border border-slate-200 bg-slate-50 p-4">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div>
                <p className="font-mono text-sm font-semibold text-slate-900">
                  {sale.receiptNumber}
                </p>
                <p className="text-xs text-slate-500">
                  <DateText value={sale.soldAt} withTime /> · {sale.paymentMethod}
                </p>
              </div>
              <div className="flex items-center gap-2">
                <StatusPill status={sale.status} />
                {canReprint ? (
                  <Button size="sm" variant="outline" onClick={() => onReprint(sale.id)}>
                    Reprint (DUPLICATE)
                  </Button>
                ) : null}
              </div>
            </div>

            {canRefund ? (
              <form noValidate onSubmit={handleReturnSubmit} className="space-y-3">
                <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
                  <table className="min-w-full divide-y divide-slate-200 text-xs">
                    <thead className="bg-slate-50 text-slate-500 uppercase">
                      <tr>
                        <th className="px-3 py-2 text-left">Item</th>
                        <th className="px-3 py-2 text-right">Sold</th>
                        <th className="px-3 py-2 text-right">Returnable</th>
                        <th className="px-3 py-2 text-right">Return qty</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100">
                      {sale.lines.map((line) => {
                        const remaining = Math.max(0, line.quantity - line.returnedQuantity)
                        return (
                          <tr key={line.id}>
                            <td className="px-3 py-2">
                              <span className="font-medium text-slate-900">{line.productName}</span>
                              <span className="block font-mono text-slate-500">{line.sku}</span>
                            </td>
                            <td className="px-3 py-2 text-right tabular-nums">{line.quantity}</td>
                            <td className="px-3 py-2 text-right tabular-nums">{remaining}</td>
                            <td className="px-3 py-2 text-right">
                              <Input
                                type="number"
                                min="0"
                                max={remaining}
                                step="1"
                                disabled={remaining === 0}
                                aria-label={`Return quantity for ${line.sku}`}
                                value={returnQty[line.id] ?? '0'}
                                onChange={(e) =>
                                  setReturnQty((prev) => ({
                                    ...prev,
                                    [line.id]: e.target.value,
                                  }))
                                }
                                className="ml-auto h-7 w-20 text-right text-xs"
                              />
                            </td>
                          </tr>
                        )
                      })}
                    </tbody>
                  </table>
                </div>

                <FormField label="Return reason" required>
                  <Input
                    value={reason}
                    placeholder="e.g. Damaged item or wrong size"
                    onChange={(e) => setReason(e.target.value)}
                  />
                </FormField>

                {validationError ? (
                  <p role="alert" className="text-sm font-medium text-rose-700">
                    {validationError}
                  </p>
                ) : null}
                {returnMutation.isError ? (
                  <ErrorState title="Could not process return" error={returnMutation.error} />
                ) : null}

                <div className="flex justify-end gap-2">
                  <Button variant="outline" onClick={onClose}>
                    Close
                  </Button>
                  <Button
                    type="submit"
                    isLoading={returnMutation.isPending}
                    loadingText="Processing..."
                  >
                    Process return
                  </Button>
                </div>
              </form>
            ) : (
              <p className="text-xs text-slate-600">
                You do not hold <code className="font-mono">POS_REFUND</code> to process returns.
              </p>
            )}
          </div>
        ) : null}

        {refundResult ? (
          <div
            role="status"
            className="space-y-3 rounded-xl border border-emerald-200 bg-emerald-50 p-4 text-sm text-emerald-950"
          >
            <div className="flex items-center justify-between">
              <p className="font-semibold">Refund summary · {refundResult.refundNumber}</p>
              <MoneyText amount={refundResult.totalRefund} className="font-bold" />
            </div>
            <p className="text-xs">Reason: {refundResult.reason}</p>
            <ul className="list-disc pl-5 text-xs">
              {refundResult.lines.map((l) => (
                <li key={l.saleLineId}>
                  {l.sku} x {l.quantity} (<MoneyText amount={l.refundAmount} />)
                </li>
              ))}
            </ul>
            <div className="flex justify-end">
              <Button size="sm" onClick={onClose}>
                Done
              </Button>
            </div>
          </div>
        ) : null}
      </div>
    </Modal>
  )
}
