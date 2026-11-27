import { useMemo, useRef, useState, type FormEvent } from 'react'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { fetchLocations } from '../api/business'
import {
  createSaleReturn,
  fetchSale,
  fetchSaleReceipt,
  fetchSales,
  listSaleReturns,
  reprintSaleReceipt,
} from '../api/pos'
import type {
  ReceiptData,
  ReturnDisposition,
  ReturnReason,
  SaleReturnRecord,
  SaleSummary,
} from '../api/models'
import { filterLocationsByPermission, hasLocationPermission } from '../lib/scopes'
import { useSession } from '../session/sessionContext'
import { Receipt } from '../components/Receipt'
import {
  Badge,
  Button,
  DataTable,
  DateText,
  Drawer,
  ErrorState,
  FormField,
  Input,
  Modal,
  MoneyText,
  PageHeader,
  Select,
  Skeleton,
  StatusPill,
  useToast,
  type DataTableColumn,
} from '../components/ui'

function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `idem-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

const RETURN_REASONS: Array<{ value: ReturnReason; label: string }> = [
  { value: 'CHANGED_MIND', label: 'Changed mind' },
  { value: 'WRONG_SIZE', label: 'Wrong size' },
  { value: 'DEFECTIVE', label: 'Defective' },
  { value: 'OTHER', label: 'Other' },
]

/**
 * Sales history screen scoped to the user's permitted locations with date/receipt filters,
 * pagination, detail drawer, original receipt view, duplicate receipt reprint, and line-level returns.
 */
export default function SalesHistoryPage() {
  const { user } = useSession()
  const queryClient = useQueryClient()
  const { toast } = useToast()

  const locationsQuery = useQuery({ queryKey: ['locations'], queryFn: fetchLocations })
  const scopedLocations = useMemo(() => {
    const all = locationsQuery.data ?? []
    const sellLocs = filterLocationsByPermission(user, 'POS_SELL', all)
    const refundLocs = filterLocationsByPermission(user, 'POS_REFUND', all)
    const reprintLocs = filterLocationsByPermission(user, 'POS_REPRINT', all)
    const combined = new Map<string, (typeof all)[number]>()
    for (const loc of [...sellLocs, ...refundLocs, ...reprintLocs]) {
      combined.set(loc.id, loc)
    }
    return Array.from(combined.values())
  }, [user, locationsQuery.data])

  const [locationId, setLocationId] = useState('')
  const [fromDate, setFromDate] = useState('')
  const [toDate, setToDate] = useState('')
  const [search, setSearch] = useState('')
  const [appliedSearch, setAppliedSearch] = useState('')
  const [page, setPage] = useState(0)

  const [selectedSaleId, setSelectedSaleId] = useState<string | null>(null)
  const [activeReceipt, setActiveReceipt] = useState<ReceiptData | null>(null)

  const effectiveLocationId = locationId || scopedLocations[0]?.id || undefined

  const salesQuery = useQuery({
    queryKey: ['sales', effectiveLocationId, fromDate, toDate, appliedSearch, page],
    queryFn: () =>
      fetchSales({
        locationId: effectiveLocationId,
        fromDate: fromDate || undefined,
        toDate: toDate || undefined,
        q: appliedSearch || undefined,
        page,
        size: 20,
      }),
    placeholderData: keepPreviousData,
  })

  const viewReceiptMutation = useMutation({
    mutationFn: (saleId: string) => fetchSaleReceipt(saleId),
    onSuccess: (receipt) => setActiveReceipt(receipt),
  })

  const reprintMutation = useMutation({
    mutationFn: (saleId: string) => reprintSaleReceipt(saleId),
    onSuccess: (receipt) => setActiveReceipt(receipt),
  })

  const columns: DataTableColumn<SaleSummary>[] = [
    {
      key: 'receiptNumber',
      header: 'Receipt',
      render: (s) => (
        <button
          type="button"
          onClick={() => setSelectedSaleId(s.id)}
          className="font-mono text-xs font-semibold text-merqo-700 hover:underline"
        >
          {s.receiptNumber}
        </button>
      ),
    },
    {
      key: 'createdAt',
      header: 'Date',
      render: (s) => (
        <span className="text-xs text-slate-500">
          <DateText value={s.createdAt} withTime />
        </span>
      ),
    },
    {
      key: 'cashier',
      header: 'Cashier',
      render: (s) => <span className="text-slate-700">{s.cashierUsername}</span>,
    },
    {
      key: 'payment',
      header: 'Payment',
      render: (s) => <Badge tone="neutral">{s.paymentMethod}</Badge>,
    },
    {
      key: 'itemCount',
      header: 'Items',
      align: 'right',
      render: (s) => <span className="tabular-nums text-slate-700">{s.itemCount}</span>,
    },
    {
      key: 'total',
      header: 'Total',
      align: 'right',
      render: (s) => <MoneyText amount={s.total} className="font-semibold text-slate-900" />,
    },
    {
      key: 'actions',
      header: 'Actions',
      render: (s) => {
        const allowReprint = hasLocationPermission(user, 'POS_REPRINT', s.locationId)
        return (
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={() => setSelectedSaleId(s.id)}
              className="text-xs font-semibold text-merqo-700 hover:underline"
            >
              Details
            </button>
            {allowReprint ? (
              <button
                type="button"
                onClick={() => reprintMutation.mutate(s.id)}
                className="text-xs font-medium text-slate-600 hover:underline"
              >
                Reprint
              </button>
            ) : null}
          </div>
        )
      },
    },
  ]

  return (
    <div className="space-y-6">
      <PageHeader
        title="Sales history"
        description="Completed receipts, duplicate reprints, and returns within your location scope."
      />

      <form
        className="grid gap-3 rounded-xl border border-slate-200 bg-white p-4 shadow-xs sm:grid-cols-2 lg:grid-cols-5"
        onSubmit={(e) => {
          e.preventDefault()
          setPage(0)
          setAppliedSearch(search.trim())
        }}
      >
        {scopedLocations.length > 0 ? (
          <FormField label="Location">
            <Select
              value={effectiveLocationId ?? ''}
              onChange={(e) => {
                setLocationId(e.target.value)
                setPage(0)
              }}
            >
              {scopedLocations.map((loc) => (
                <option key={loc.id} value={loc.id}>
                  {loc.name} ({loc.code})
                </option>
              ))}
            </Select>
          </FormField>
        ) : null}

        <FormField label="From date">
          <Input
            type="date"
            value={fromDate}
            onChange={(e) => {
              setFromDate(e.target.value)
              setPage(0)
            }}
          />
        </FormField>

        <FormField label="To date">
          <Input
            type="date"
            value={toDate}
            onChange={(e) => {
              setToDate(e.target.value)
              setPage(0)
            }}
          />
        </FormField>

        <FormField label="Receipt number">
          <Input
            type="search"
            value={search}
            placeholder="Search MQ-..."
            onChange={(e) => setSearch(e.target.value)}
          />
        </FormField>

        <div className="flex items-end">
          <Button type="submit" variant="outline" className="w-full sm:w-auto">
            Search
          </Button>
        </div>
      </form>

      {reprintMutation.error ? (
        <ErrorState title="Could not reprint receipt" error={reprintMutation.error} />
      ) : null}
      {viewReceiptMutation.error ? (
        <ErrorState title="Could not load receipt" error={viewReceiptMutation.error} />
      ) : null}

      {salesQuery.error ? (
        <ErrorState
          title="Could not load sales history"
          error={salesQuery.error}
          onRetry={() => salesQuery.refetch()}
        />
      ) : (
        <DataTable
          columns={columns}
          rows={salesQuery.data?.items ?? []}
          getRowKey={(s) => s.id}
          isLoading={salesQuery.isPending}
          emptyTitle="No sales found"
          emptyDescription="Try clearing the date range or receipt search."
          pagination={
            salesQuery.data
              ? {
                  page: salesQuery.data.page,
                  totalPages: salesQuery.data.totalPages,
                  totalItems: salesQuery.data.totalItems,
                  pageSize: salesQuery.data.size,
                  onPageChange: setPage,
                }
              : undefined
          }
        />
      )}

      <SaleDetailDrawer
        saleId={selectedSaleId}
        onClose={() => setSelectedSaleId(null)}
        onViewReceipt={(saleId) => viewReceiptMutation.mutate(saleId)}
        onReprint={(saleId) => reprintMutation.mutate(saleId)}
        onReturned={() => {
          queryClient.invalidateQueries({ queryKey: ['sales'] })
          toast({ title: 'Return recorded', tone: 'success' })
        }}
      />

      {activeReceipt ? (
        <Modal
          open
          onClose={() => setActiveReceipt(null)}
          title={
            activeReceipt.isDuplicate
              ? `Receipt reprint · ${activeReceipt.receiptNumber}`
              : `Receipt · ${activeReceipt.receiptNumber}`
          }
          footer={
            <Button onClick={() => window.print()}>
              {activeReceipt.isDuplicate ? 'Print duplicate' : 'Print receipt'}
            </Button>
          }
        >
          <Receipt data={activeReceipt} paperWidth="80mm" />
        </Modal>
      ) : null}
    </div>
  )
}

function SaleDetailDrawer({
  saleId,
  onClose,
  onViewReceipt,
  onReprint,
  onReturned,
}: {
  saleId: string | null
  onClose: () => void
  onViewReceipt: (saleId: string) => void
  onReprint: (saleId: string) => void
  onReturned: () => void
}) {
  const { user } = useSession()
  const queryClient = useQueryClient()
  const [showReturnForm, setShowReturnForm] = useState(false)
  const [returnQty, setReturnQty] = useState<Record<string, string>>({})
  const [dispositions, setDispositions] = useState<Record<string, ReturnDisposition>>({})
  const [reason, setReason] = useState<ReturnReason>('CHANGED_MIND')
  const [note, setNote] = useState('')
  const [validationError, setValidationError] = useState<string | null>(null)
  const [lastRefund, setLastRefund] = useState<SaleReturnRecord | null>(null)
  const lastReturnAttemptRef = useRef<{ signature: string; key: string } | null>(null)

  const detailQuery = useQuery({
    queryKey: ['sale', saleId],
    queryFn: () => fetchSale(saleId!),
    enabled: Boolean(saleId),
  })

  const returnsQuery = useQuery({
    queryKey: ['sale', saleId, 'returns'],
    queryFn: () => listSaleReturns(saleId!),
    enabled: Boolean(saleId),
  })

  const returnedByLine = useMemo(() => {
    const counts: Record<string, number> = {}
    for (const ret of returnsQuery.data ?? []) {
      for (const line of ret.lines) {
        counts[line.saleLineId] = (counts[line.saleLineId] ?? 0) + line.quantity
      }
    }
    return counts
  }, [returnsQuery.data])

  const returnMutation = useMutation({
    mutationFn: () => {
      const selectedLines = (detailQuery.data?.lines ?? [])
        .map((line) => ({
          saleLineId: line.id,
          quantity: Number(returnQty[line.id] ?? '0'),
          disposition:
            dispositions[line.id] ?? (reason === 'DEFECTIVE' ? 'INSPECTION' : 'RESTOCK'),
        }))
        .filter((l) => l.quantity > 0)

      const payload = {
        reason,
        note: note.trim() || undefined,
        lines: selectedLines,
      }
      const signature = JSON.stringify({ saleId, payload })
      let key = lastReturnAttemptRef.current?.key
      if (!key || lastReturnAttemptRef.current?.signature !== signature) {
        key = newIdempotencyKey()
        lastReturnAttemptRef.current = { signature, key }
      }
      return createSaleReturn(saleId!, payload, key)
    },
    onSuccess: (record) => {
      lastReturnAttemptRef.current = null
      setLastRefund(record)
      setShowReturnForm(false)
      setReturnQty({})
      setDispositions({})
      setNote('')
      queryClient.invalidateQueries({ queryKey: ['sale', saleId] })
      queryClient.invalidateQueries({ queryKey: ['sale', saleId, 'returns'] })
      onReturned()
    },
  })

  const sale = detailQuery.data
  const returnsList = returnsQuery.data ?? []
  const canReprint = sale ? hasLocationPermission(user, 'POS_REPRINT', sale.locationId) : false
  const canRefund = sale ? hasLocationPermission(user, 'POS_REFUND', sale.locationId) : false

  const totalSold = sale?.lines.reduce((sum, l) => sum + l.quantity, 0) ?? 0
  const totalReturned = Object.values(returnedByLine).reduce((sum, q) => sum + q, 0)
  const hasReturnable =
    sale?.lines.some((l) => l.quantity - (returnedByLine[l.id] ?? 0) > 0) ?? false

  const derivedStatus =
    totalReturned === 0
      ? 'COMPLETED'
      : totalReturned >= totalSold
        ? 'RETURNED'
        : 'PARTIALLY_RETURNED'

  const combinedDiscounts = sale
    ? (Number(sale.promotionDiscount) + Number(sale.manualDiscount)).toFixed(2)
    : '0.00'

  function handleReturnSubmit(event: FormEvent) {
    event.preventDefault()
    if (!sale) return
    if (reason === 'OTHER' && !note.trim()) {
      setValidationError('A note is required when return reason is Other.')
      return
    }
    const chosen = sale.lines
      .map((line) => ({
        line,
        qty: Number(returnQty[line.id] ?? '0'),
        remaining: line.quantity - (returnedByLine[line.id] ?? 0),
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
    <Drawer
      open={Boolean(saleId)}
      onClose={() => {
        setShowReturnForm(false)
        setLastRefund(null)
        lastReturnAttemptRef.current = null
        onClose()
      }}
      title={sale ? `Sale ${sale.receiptNumber}` : 'Sale details'}
      description={sale ? `${sale.locationName} · ${sale.cashierUsername}` : undefined}
    >
      {detailQuery.isPending ? <Skeleton className="h-48 w-full" /> : null}
      {detailQuery.error ? (
        <ErrorState title="Could not load sale" error={detailQuery.error} />
      ) : null}

      {sale ? (
        <div className="space-y-5">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <StatusPill status={derivedStatus} />
            <div className="flex flex-wrap gap-2">
              <Button size="sm" variant="outline" onClick={() => onViewReceipt(sale.id)}>
                View receipt
              </Button>
              {canReprint ? (
                <Button size="sm" variant="outline" onClick={() => onReprint(sale.id)}>
                  Reprint (DUPLICATE)
                </Button>
              ) : null}
              {canRefund && hasReturnable && !showReturnForm ? (
                <Button size="sm" onClick={() => setShowReturnForm(true)}>
                  Return items
                </Button>
              ) : null}
            </div>
          </div>

          <div className="overflow-hidden rounded-lg border border-slate-200">
            <table className="min-w-full divide-y divide-slate-200 text-xs">
              <thead className="bg-slate-50 text-slate-500 uppercase">
                <tr>
                  <th className="px-3 py-2 text-left">Item</th>
                  <th className="px-3 py-2 text-right">Qty</th>
                  <th className="px-3 py-2 text-right">Returned</th>
                  <th className="px-3 py-2 text-right">Total</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {sale.lines.map((line) => {
                  const variantText = [line.color, line.size].filter(Boolean).join(' / ')
                  const returnedCount = returnedByLine[line.id] ?? 0
                  return (
                    <tr key={line.id}>
                      <td className="px-3 py-2">
                        <span className="font-medium text-slate-900">{line.productName}</span>
                        <span className="block font-mono text-slate-500">
                          {line.sku}
                          {variantText ? ` · ${variantText}` : ''}
                        </span>
                      </td>
                      <td className="px-3 py-2 text-right tabular-nums">{line.quantity}</td>
                      <td className="px-3 py-2 text-right tabular-nums">{returnedCount}</td>
                      <td className="px-3 py-2 text-right font-medium">
                        <MoneyText amount={line.lineTotal} />
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>

          <dl className="space-y-1 rounded-lg border border-slate-200 bg-slate-50 p-3.5 text-xs tabular-nums">
            <div className="flex justify-between">
              <dt className="text-slate-600">Subtotal</dt>
              <dd>
                <MoneyText amount={sale.subtotal} />
              </dd>
            </div>
            <div className="flex justify-between">
              <dt className="text-slate-600">Discounts</dt>
              <dd>
                <MoneyText amount={combinedDiscounts} />
              </dd>
            </div>
            <div className="flex justify-between">
              <dt className="text-slate-600">Tax</dt>
              <dd>
                <MoneyText amount={sale.taxTotal} />
              </dd>
            </div>
            <div className="flex justify-between border-t border-slate-200 pt-1.5 text-sm font-bold text-slate-900">
              <dt>Total ({sale.paymentMethod})</dt>
              <dd>
                <MoneyText amount={sale.total} />
              </dd>
            </div>
          </dl>

          {showReturnForm && canRefund ? (
            <form
              noValidate
              onSubmit={handleReturnSubmit}
              className="space-y-3 rounded-xl border border-slate-200 bg-white p-4"
            >
              <h3 className="text-sm font-semibold text-slate-900">Return items</h3>
              <ul className="space-y-2">
                {sale.lines.map((line) => {
                  const remaining = Math.max(0, line.quantity - (returnedByLine[line.id] ?? 0))
                  if (remaining === 0) return null
                  const disp =
                    dispositions[line.id] ?? (reason === 'DEFECTIVE' ? 'INSPECTION' : 'RESTOCK')
                  return (
                    <li
                      key={line.id}
                      className="flex flex-wrap items-center justify-between gap-2 text-xs"
                    >
                      <span>
                        {line.productName} ({line.sku}) · max {remaining}
                      </span>
                      <div className="flex items-center gap-2">
                        <Select
                          aria-label={`Disposition for ${line.sku}`}
                          value={disp}
                          onChange={(e) =>
                            setDispositions((prev) => ({
                              ...prev,
                              [line.id]: e.target.value as ReturnDisposition,
                            }))
                          }
                          className="h-8 w-28 text-xs"
                        >
                          <option value="RESTOCK">Restock</option>
                          <option value="INSPECTION">Inspection</option>
                        </Select>
                        <Input
                          type="number"
                          min="0"
                          max={remaining}
                          step="1"
                          aria-label={`Return quantity for ${line.sku}`}
                          value={returnQty[line.id] ?? '0'}
                          onChange={(e) =>
                            setReturnQty((prev) => ({ ...prev, [line.id]: e.target.value }))
                          }
                          className="h-8 w-20 text-right"
                        />
                      </div>
                    </li>
                  )
                })}
              </ul>
              <FormField label="Reason" required>
                <Select
                  value={reason}
                  onChange={(e) => setReason(e.target.value as ReturnReason)}
                >
                  {RETURN_REASONS.map((opt) => (
                    <option key={opt.value} value={opt.value}>
                      {opt.label}
                    </option>
                  ))}
                </Select>
              </FormField>
              <FormField label={reason === 'OTHER' ? 'Note' : 'Note (optional)'} required={reason === 'OTHER'}>
                <Input
                  value={note}
                  placeholder={reason === 'OTHER' ? 'Explain return reason...' : 'Optional return note'}
                  onChange={(e) => setNote(e.target.value)}
                />
              </FormField>
              {validationError ? (
                <p role="alert" className="text-xs font-medium text-rose-700">
                  {validationError}
                </p>
              ) : null}
              {returnMutation.isError ? (
                <ErrorState title="Return failed" error={returnMutation.error} />
              ) : null}
              <div className="flex justify-end gap-2">
                <Button variant="outline" size="sm" onClick={() => setShowReturnForm(false)}>
                  Cancel
                </Button>
                <Button type="submit" size="sm" isLoading={returnMutation.isPending}>
                  Submit return
                </Button>
              </div>
            </form>
          ) : null}

          {lastRefund ? (
            <div
              role="status"
              className="rounded-lg border border-emerald-200 bg-emerald-50 p-3 text-xs text-emerald-900"
            >
              Refund created: <strong>{lastRefund.returnNumber}</strong> (
              <MoneyText amount={lastRefund.refundAmount} />)
            </div>
          ) : null}

          {returnsList.length > 0 ? (
            <section className="space-y-2">
              <h3 className="text-xs font-semibold tracking-wide text-slate-500 uppercase">
                Return history
              </h3>
              <ul className="space-y-2">
                {returnsList.map((ret) => (
                  <li
                    key={ret.id}
                    className="rounded-lg border border-slate-200 bg-slate-50 p-3 text-xs"
                  >
                    <div className="flex justify-between font-medium text-slate-900">
                      <span>{ret.returnNumber}</span>
                      <MoneyText amount={ret.refundAmount} />
                    </div>
                    <p className="mt-0.5 text-slate-600">
                      {ret.reason}
                      {ret.note ? ` · ${ret.note}` : ''}
                    </p>
                  </li>
                ))}
              </ul>
            </section>
          ) : null}
        </div>
      ) : null}
    </Drawer>
  )
}
