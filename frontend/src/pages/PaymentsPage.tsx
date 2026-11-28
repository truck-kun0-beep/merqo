import { useMemo, useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { fetchAuthStatus } from '../api/auth'
import { fetchLocations } from '../api/business'
import {
  approveRefund,
  checkOrderPaymentWithProvider,
  createCourierSettlement,
  fetchCodOutstanding,
  fetchCourierSettlementDetail,
  fetchCourierSettlements,
  fetchPaymentHistory,
  fetchPaymentSummary,
  fetchPendingPayments,
  fetchRefunds,
  fetchRefundsDue,
  payRefund,
  rejectRefund,
  requestOrderRefund,
  verifyOrderPayment,
  type CodOutstandingOrderView,
  type OnlinePaymentMethod,
  type PaymentDecision,
  type PaymentHistoryRowView,
  type PaymentHistoryType,
  type PendingPaymentView,
  type RefundDueRowView,
  type RefundPayoutMethod,
  type RefundReason,
  type RefundStatus,
  type RefundView,
  type SettlementDetailView,
} from '../api/payments'
import {
  Badge,
  Button,
  DataTable,
  DateText,
  EmptyState,
  ErrorState,
  FormField,
  Input,
  Modal,
  MoneyText,
  PageHeader,
  Select,
  Skeleton,
  StatusPill,
  Tabs,
  Textarea,
  useToast,
  type DataTableColumn,
  type TabItem,
} from '../components/ui'
import { filterLocationsByPermission } from '../lib/scopes'
import {
  refundPayFormSchema,
  refundRequestFormSchema,
  verifyPaymentFormSchema,
} from '../lib/validation'
import { useSession } from '../session/sessionContext'

const HISTORY_TYPES: Array<{ value: PaymentHistoryType | ''; label: string }> = [
  { value: '', label: 'All types' },
  { value: 'POS_SALE', label: 'POS sale' },
  { value: 'POS_RETURN', label: 'POS return' },
  { value: 'ORDER_PAYMENT', label: 'Order payment' },
  { value: 'ORDER_REFUND', label: 'Order refund' },
  { value: 'COD_SETTLEMENT', label: 'COD settlement' },
]

const HISTORY_METHODS: OnlinePaymentMethod[] | string[] = [
  'CASH',
  'BKASH',
  'NAGAD',
  'CARD',
  'BANK',
]

function toPaisa(raw: string | number | undefined): number {
  const num = Number(raw || 0)
  if (Number.isNaN(num)) return 0
  return Math.round(num * 100)
}

function fromPaisa(paisa: number): string {
  return (paisa / 100).toFixed(2)
}

export default function PaymentsPage() {
  const { can } = useSession()
  const [searchParams, setSearchParams] = useSearchParams()

  const canHistory = can('PAYMENT_VIEW')
  const canVerify = can('PAYMENT_VERIFY')
  const canRefunds =
    can('REFUND_REQUEST') || can('REFUND_APPROVE') || can('REFUND_PAY')
  const canCod = can('COD_SETTLEMENT_MANAGE')

  const defaultTab = canHistory
    ? 'history'
    : canVerify
      ? 'pending'
      : canRefunds
        ? 'refunds'
        : 'cod'
  const requestedTab = searchParams.get('tab') || defaultTab

  const pendingQuery = useQuery({
    queryKey: ['payments', 'pending'],
    queryFn: fetchPendingPayments,
    enabled: canVerify,
  })

  const refundsDueQuery = useQuery({
    queryKey: ['refunds', 'due'],
    queryFn: fetchRefundsDue,
    enabled: canRefunds,
  })

  const tabs: TabItem[] = []
  if (canHistory) {
    tabs.push({
      id: 'history',
      label: 'History',
      content: <HistoryTab />,
    })
  }
  if (canVerify) {
    tabs.push({
      id: 'pending',
      label: 'To verify',
      badge:
        pendingQuery.data && pendingQuery.data.length > 0 ? (
          <Badge tone="warning">{pendingQuery.data.length}</Badge>
        ) : undefined,
      content: <PendingVerifyTab />,
    })
  }
  if (canRefunds) {
    tabs.push({
      id: 'refunds',
      label: 'Refunds',
      badge:
        refundsDueQuery.data && refundsDueQuery.data.length > 0 ? (
          <Badge tone="danger">{refundsDueQuery.data.length}</Badge>
        ) : undefined,
      content: <RefundsTab />,
    })
  }
  if (canCod) {
    tabs.push({
      id: 'cod',
      label: 'COD & settlements',
      content: <CodTab />,
    })
  }

  const activeTab = tabs.some((t) => t.id === requestedTab)
    ? requestedTab
    : tabs[0]?.id ?? 'history'

  return (
    <div className="space-y-6">
      <PageHeader
        title="Payments & Finance"
        description="Unified payment history, payment verification queue, refunds, and courier COD settlements."
      />

      {tabs.length === 0 ? (
        <EmptyState
          title="No payment sections permitted"
          description="Ask the business owner to assign payment permissions to your account."
        />
      ) : (
        <Tabs
          tabs={tabs}
          activeTab={activeTab}
          onTabChange={(next) => setSearchParams({ tab: next })}
        />
      )}
    </div>
  )
}

function HistoryTab() {
  const { user } = useSession()
  const [q, setQ] = useState('')
  const [type, setType] = useState<PaymentHistoryType | ''>('')
  const [method, setMethod] = useState('')
  const [status, setStatus] = useState('')
  const [locationId, setLocationId] = useState('')
  const [fromDate, setFromDate] = useState('')
  const [toDate, setToDate] = useState('')
  const [page, setPage] = useState(0)

  const locationsQuery = useQuery({
    queryKey: ['locations'],
    queryFn: fetchLocations,
  })

  const permittedLocations = useMemo(
    () => filterLocationsByPermission(user, 'PAYMENT_VIEW', locationsQuery.data ?? []),
    [user, locationsQuery.data],
  )

  const filters = {
    type,
    method: method || undefined,
    status: status || undefined,
    locationId: locationId || undefined,
    fromDate: fromDate || undefined,
    toDate: toDate || undefined,
    q: q.trim() || undefined,
  }

  const historyQuery = useQuery({
    queryKey: ['payments', 'history', { ...filters, page }],
    queryFn: () => fetchPaymentHistory({ ...filters, page, size: 20 }),
    placeholderData: keepPreviousData,
  })

  const summaryQuery = useQuery({
    queryKey: ['payments', 'summary', filters],
    queryFn: () => fetchPaymentSummary(filters),
  })

  const columns: DataTableColumn<PaymentHistoryRowView>[] = [
    {
      key: 'doc',
      header: 'Document',
      render: (row) => (
        <div>
          {row.orderId ? (
            <Link
              to={`/orders/${row.orderId}`}
              className="font-mono font-semibold text-merqo-700 hover:underline"
            >
              {row.documentNumber}
            </Link>
          ) : (
            <span className="font-mono font-semibold text-slate-900">{row.documentNumber}</span>
          )}
          <p className="text-xs text-slate-500">{row.type.replaceAll('_', ' ')}</p>
        </div>
      ),
    },
    {
      key: 'method',
      header: 'Method',
      render: (row) => <Badge tone="neutral">{row.method ?? '—'}</Badge>,
    },
    {
      key: 'amount',
      header: 'Amount',
      align: 'right',
      render: (row) => <MoneyText amount={row.amount} className="font-semibold" />,
    },
    {
      key: 'reference',
      header: 'Reference',
      render: (row) => <span className="font-mono text-xs">{row.reference ?? '—'}</span>,
    },
    {
      key: 'status',
      header: 'Status',
      render: (row) => (row.status ? <StatusPill status={row.status} /> : '—'),
    },
    {
      key: 'location',
      header: 'Location',
      render: (row) => <span className="text-xs text-slate-700">{row.locationName ?? '—'}</span>,
    },
    {
      key: 'occurredAt',
      header: 'Occurred',
      render: (row) => (
        <div className="text-xs">
          <DateText value={row.occurredAt} withTime />
          {row.recordedBy ? <p className="text-slate-500">{row.recordedBy}</p> : null}
        </div>
      ),
    },
  ]

  return (
    <div className="space-y-4">
      <section className="grid gap-3 sm:grid-cols-3">
        <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-xs">
          <p className="text-xs font-medium text-slate-500 uppercase">Matching transactions</p>
          <p className="mt-1 text-2xl font-bold text-slate-900 tabular-nums">
            {summaryQuery.data?.totalCount ?? '—'}
          </p>
        </div>
        <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-xs">
          <p className="text-xs font-medium text-slate-500 uppercase">Total amount</p>
          <p className="mt-1 text-2xl font-bold text-slate-900">
            {summaryQuery.data ? <MoneyText amount={summaryQuery.data.totalAmount} /> : '—'}
          </p>
        </div>
        <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-xs">
          <p className="text-xs font-medium text-slate-500 uppercase">By method</p>
          <div className="mt-2 flex flex-wrap gap-1.5 text-xs">
            {summaryQuery.data?.byMethod.length ? (
              summaryQuery.data.byMethod.map((b) => (
                <Badge key={b.key} tone="neutral">
                  {b.key}: {b.count} (<MoneyText amount={b.totalAmount} />)
                </Badge>
              ))
            ) : (
              <span className="text-slate-400">No entries</span>
            )}
          </div>
        </div>
      </section>

      <section className="rounded-xl border border-slate-200 bg-white p-4 shadow-xs">
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-6">
          <Input
            aria-label="Search payments"
            placeholder="TrxID, order #, receipt, phone..."
            value={q}
            onChange={(e) => {
              setQ(e.target.value)
              setPage(0)
            }}
          />
          <Select
            aria-label="Filter by type"
            value={type}
            onChange={(e) => {
              setType(e.target.value as PaymentHistoryType | '')
              setPage(0)
            }}
          >
            {HISTORY_TYPES.map((t) => (
              <option key={t.value} value={t.value}>
                {t.label}
              </option>
            ))}
          </Select>
          <Select
            aria-label="Filter by method"
            value={method}
            onChange={(e) => {
              setMethod(e.target.value)
              setPage(0)
            }}
          >
            <option value="">All methods</option>
            {HISTORY_METHODS.map((m) => (
              <option key={m} value={m}>
                {m}
              </option>
            ))}
          </Select>
          <Select
            aria-label="Filter by location"
            value={locationId}
            onChange={(e) => {
              setLocationId(e.target.value)
              setPage(0)
            }}
          >
            <option value="">All permitted locations</option>
            {permittedLocations.map((loc) => (
              <option key={loc.id} value={loc.id}>
                {loc.name}
              </option>
            ))}
          </Select>
          <Input
            aria-label="From date"
            type="date"
            value={fromDate}
            onChange={(e) => {
              setFromDate(e.target.value)
              setPage(0)
            }}
          />
          <Input
            aria-label="To date"
            type="date"
            value={toDate}
            onChange={(e) => {
              setToDate(e.target.value)
              setPage(0)
            }}
          />
        </div>
        <div className="mt-3 flex items-center gap-2">
          <Input
            aria-label="Filter by status"
            placeholder="Status (e.g. VERIFIED, PAID)"
            value={status}
            onChange={(e) => {
              setStatus(e.target.value)
              setPage(0)
            }}
            className="max-w-xs"
          />
        </div>
      </section>

      {historyQuery.error ? (
        <ErrorState
          title="Could not load payment history"
          error={historyQuery.error}
          onRetry={() => historyQuery.refetch()}
        />
      ) : (
        <>
          <DataTable
            columns={columns}
            rows={historyQuery.data?.items ?? []}
            getRowKey={(row, idx) => `${row.type}-${row.documentNumber}-${idx}`}
            isLoading={historyQuery.isPending}
            emptyTitle="No matching payments"
            emptyDescription="Try clearing filters or broadening the date range."
            pagination={
              historyQuery.data
                ? {
                    page: historyQuery.data.page,
                    totalPages: historyQuery.data.totalPages,
                    totalItems: historyQuery.data.totalItems,
                    pageSize: 20,
                    onPageChange: setPage,
                  }
                : undefined
            }
          />
        </>
      )}
    </div>
  )
}

function PendingVerifyTab() {
  const queryClient = useQueryClient()
  const { toast } = useToast()

  const authStatusQuery = useQuery({
    queryKey: ['auth', 'status'],
    queryFn: fetchAuthStatus,
    staleTime: 60_000,
  })

  const pendingQuery = useQuery({
    queryKey: ['payments', 'pending'],
    queryFn: fetchPendingPayments,
  })

  const [target, setTarget] = useState<PendingPaymentView | null>(null)
  const [decision, setDecision] = useState<PaymentDecision>('VERIFIED')
  const [confirmedAmount, setConfirmedAmount] = useState('')
  const [evidence, setEvidence] = useState('')
  const [formError, setFormError] = useState<string | null>(null)

  function invalidate() {
    queryClient.invalidateQueries({ queryKey: ['payments'] })
    queryClient.invalidateQueries({ queryKey: ['orders'] })
  }

  const verifyMutation = useMutation({
    mutationFn: () =>
      verifyOrderPayment(target!.id, {
        decision,
        confirmedAmount: decision === 'VERIFIED' ? confirmedAmount.trim() : undefined,
        evidence: evidence.trim() || null,
      }),
    onSuccess: (updated) => {
      setTarget(null)
      setFormError(null)
      invalidate()
      toast({
        title: `Payment ${updated.paymentNumber} ${updated.status.toLowerCase()}`,
        tone: 'success',
      })
    },
  })

  const checkMutation = useMutation({
    mutationFn: (paymentId: string) => checkOrderPaymentWithProvider(paymentId),
    onSuccess: (res) => {
      invalidate()
      toast({
        title: `Provider check: ${res.outcome}`,
        description: res.message,
        tone: res.outcome === 'CONFIRMED' ? 'success' : 'warning',
      })
    },
  })

  const providerCheckEnabled = authStatusQuery.data?.providerCheckEnabled === true

  function submitVerify(e: FormEvent) {
    e.preventDefault()
    const parsed = verifyPaymentFormSchema.safeParse({
      decision,
      confirmedAmount,
      evidence,
    })
    if (!parsed.success) {
      setFormError(parsed.error.issues[0]?.message ?? 'Invalid verification input.')
      return
    }
    setFormError(null)
    verifyMutation.mutate()
  }

  if (pendingQuery.isPending) {
    return <Skeleton className="h-48 w-full rounded-xl" />
  }

  if (pendingQuery.error) {
    return (
      <ErrorState
        title="Could not load pending payments"
        error={pendingQuery.error}
        onRetry={() => pendingQuery.refetch()}
      />
    )
  }

  const rows = pendingQuery.data ?? []

  return (
    <div className="space-y-4">
      {rows.length === 0 ? (
        <EmptyState
          title="No payments waiting verification"
          description="All recorded advances and website claims have been decided."
        />
      ) : (
        <div className="overflow-x-auto rounded-xl border border-slate-200 bg-white shadow-xs">
          <table className="min-w-full divide-y divide-slate-200 text-sm">
            <thead className="bg-slate-50 text-xs text-slate-500 uppercase">
              <tr>
                <th className="px-4 py-3 text-left">Payment</th>
                <th className="px-4 py-3 text-left">Order</th>
                <th className="px-4 py-3 text-left">Method / Ref</th>
                <th className="px-4 py-3 text-right">Amount</th>
                <th className="px-4 py-3 text-left">Age</th>
                <th className="px-4 py-3 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {rows.map((p) => (
                <tr key={p.id}>
                  <td className="px-4 py-3">
                    <p className="font-mono font-semibold text-slate-900">{p.paymentNumber}</p>
                    <p className="text-xs text-slate-500">
                      {p.source === 'WEBSITE_CLAIM' ? 'Website claim' : 'Staff recorded'} ·{' '}
                      {p.recordedByUsername}
                    </p>
                  </td>
                  <td className="px-4 py-3">
                    <Link
                      to={`/orders/${p.orderId}`}
                      className="font-mono font-semibold text-merqo-700 hover:underline"
                    >
                      {p.orderNumber}
                    </Link>
                    <p className="text-xs text-slate-600">{p.customerName}</p>
                  </td>
                  <td className="px-4 py-3">
                    <Badge tone="neutral">{p.method}</Badge>{' '}
                    <span className="font-mono text-xs text-slate-800">{p.reference}</span>
                  </td>
                  <td className="px-4 py-3 text-right font-semibold">
                    <MoneyText amount={p.amount} />
                  </td>
                  <td className="px-4 py-3 text-xs">
                    <span>{p.hoursPending}h</span>{' '}
                    {p.overdue ? <Badge tone="danger">Overdue</Badge> : null}
                  </td>
                  <td className="px-4 py-3 text-right">
                    <div className="inline-flex flex-wrap justify-end gap-2">
                      {providerCheckEnabled ? (
                        <Button
                          size="sm"
                          variant="outline"
                          isLoading={checkMutation.isPending && checkMutation.variables === p.id}
                          onClick={() => checkMutation.mutate(p.id)}
                        >
                          Check
                        </Button>
                      ) : null}
                      <Button
                        size="sm"
                        onClick={() => {
                          setTarget(p)
                          setDecision('VERIFIED')
                          setConfirmedAmount(p.amount)
                          setEvidence('')
                          setFormError(null)
                          verifyMutation.reset()
                        }}
                      >
                        Review
                      </Button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <Modal
        open={Boolean(target)}
        onClose={() => setTarget(null)}
        title={`Verify payment ${target?.paymentNumber ?? ''}`}
        size="sm"
      >
        <form onSubmit={submitVerify} className="space-y-3">
          <FormField label="Decision" required>
            <Select
              aria-label="Decision"
              value={decision}
              onChange={(e) => setDecision(e.target.value as PaymentDecision)}
            >
              <option value="VERIFIED">Verify</option>
              <option value="REJECTED">Reject</option>
            </Select>
          </FormField>
          {decision === 'VERIFIED' ? (
            <FormField label="Confirmed amount (BDT)" required>
              <Input
                aria-label="Confirmed amount"
                type="number"
                step="0.01"
                value={confirmedAmount}
                onChange={(e) => setConfirmedAmount(e.target.value)}
              />
            </FormField>
          ) : null}
          <FormField label="Evidence / note" required>
            <Textarea
              aria-label="Evidence"
              value={evidence}
              onChange={(e) => setEvidence(e.target.value)}
            />
          </FormField>
          {formError ? (
            <p role="alert" className="text-xs font-medium text-rose-700">
              {formError}
            </p>
          ) : null}
          {verifyMutation.isError ? (
            <ErrorState title="Verification failed" error={verifyMutation.error} />
          ) : null}
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="outline" onClick={() => setTarget(null)}>
              Cancel
            </Button>
            <Button type="submit" isLoading={verifyMutation.isPending}>
              Confirm
            </Button>
          </div>
        </form>
      </Modal>
    </div>
  )
}

function RefundsTab() {
  const { can } = useSession()
  const queryClient = useQueryClient()
  const { toast } = useToast()

  const [statusFilter, setStatusFilter] = useState<RefundStatus | ''>('')
  const [reqTarget, setReqTarget] = useState<RefundDueRowView | null>(null)
  const [reqAmount, setReqAmount] = useState('')
  const [reqReason, setReqReason] = useState<RefundReason>('ORDER_CANCELLED')
  const [reqNote, setReqNote] = useState('')
  const [reqError, setReqError] = useState<string | null>(null)

  const [decideTarget, setDecideTarget] = useState<{
    refund: RefundView
    mode: 'APPROVE' | 'REJECT'
  } | null>(null)
  const [decideNote, setDecideNote] = useState('')

  const [payTarget, setPayTarget] = useState<RefundView | null>(null)
  const [payoutMethod, setPayoutMethod] = useState<RefundPayoutMethod>('BKASH')
  const [payoutRef, setPayoutRef] = useState('')
  const [payoutNote, setPayoutNote] = useState('')
  const [payError, setPayError] = useState<string | null>(null)

  const dueQuery = useQuery({
    queryKey: ['refunds', 'due'],
    queryFn: fetchRefundsDue,
  })

  const listQuery = useQuery({
    queryKey: ['refunds', 'list', statusFilter],
    queryFn: () => fetchRefunds({ status: statusFilter }),
  })

  function invalidate() {
    queryClient.invalidateQueries({ queryKey: ['refunds'] })
    queryClient.invalidateQueries({ queryKey: ['payments'] })
    queryClient.invalidateQueries({ queryKey: ['orders'] })
  }

  const reqMutation = useMutation({
    mutationFn: () =>
      requestOrderRefund(reqTarget!.orderId, {
        amount: reqAmount.trim(),
        reason: reqReason.trim(),
        note: reqNote.trim() || null,
      }),
    onSuccess: (created) => {
      setReqTarget(null)
      invalidate()
      toast({ title: `Refund ${created.refundNumber} requested`, tone: 'success' })
    },
  })

  const decideMutation = useMutation({
    mutationFn: () =>
      decideTarget!.mode === 'APPROVE'
        ? approveRefund(decideTarget!.refund.id, decideNote.trim() || null)
        : rejectRefund(decideTarget!.refund.id, decideNote.trim() || null),
    onSuccess: (updated) => {
      setDecideTarget(null)
      setDecideNote('')
      invalidate()
      toast({
        title: `Refund ${updated.refundNumber} ${updated.status.toLowerCase()}`,
        tone: 'success',
      })
    },
  })

  const payMutation = useMutation({
    mutationFn: () =>
      payRefund(payTarget!.id, {
        payoutMethod,
        payoutReference: payoutMethod === 'CASH' ? null : payoutRef.trim().toUpperCase(),
        note: payoutNote.trim() || null,
      }),
    onSuccess: (updated) => {
      setPayTarget(null)
      invalidate()
      toast({ title: `Refund ${updated.refundNumber} marked PAID`, tone: 'success' })
    },
  })

  function submitRequest(e: FormEvent) {
    e.preventDefault()
    const parsed = refundRequestFormSchema.safeParse({
      amount: reqAmount,
      reason: reqReason,
      note: reqNote,
    })
    if (!parsed.success) {
      setReqError(parsed.error.issues[0]?.message ?? 'Invalid refund details.')
      return
    }
    setReqError(null)
    reqMutation.mutate()
  }

  function submitPay(e: FormEvent) {
    e.preventDefault()
    const parsed = refundPayFormSchema.safeParse({
      payoutMethod,
      payoutReference: payoutRef,
      note: payoutNote,
    })
    if (!parsed.success) {
      setPayError(parsed.error.issues[0]?.message ?? 'Invalid payout details.')
      return
    }
    setPayError(null)
    payMutation.mutate()
  }

  return (
    <div className="space-y-6">
      <section className="rounded-xl border border-slate-200 bg-white p-5 shadow-xs space-y-3">
        <div className="flex items-center justify-between gap-2">
          <div>
            <h2 className="text-sm font-semibold text-slate-900">Refunds due</h2>
            <p className="text-xs text-slate-500">
              Cancelled or declined orders holding unrefunded verified prepayments.
            </p>
          </div>
          <Badge tone={dueQuery.data?.length ? 'danger' : 'success'}>
            {dueQuery.data?.length ?? 0} due
          </Badge>
        </div>
        {dueQuery.isPending ? (
          <Skeleton className="h-24 w-full" />
        ) : dueQuery.data && dueQuery.data.length > 0 ? (
          <ul className="divide-y divide-slate-100 text-xs">
            {dueQuery.data.map((d) => (
              <li key={d.orderId} className="flex flex-wrap items-center justify-between gap-3 py-2.5">
                <div>
                  <Link
                    to={`/orders/${d.orderId}`}
                    className="font-mono font-semibold text-merqo-700 hover:underline"
                  >
                    {d.orderNumber}
                  </Link>{' '}
                  <StatusPill status={d.orderStatus} />{' '}
                  <span className="text-slate-700">{d.customerName}</span>
                  <p className="text-slate-500">
                    Verified paid <MoneyText amount={d.verifiedPaid} /> · Refundable{' '}
                    <MoneyText amount={d.refundable} className="font-semibold text-rose-700" />
                  </p>
                </div>
                {can('REFUND_REQUEST') ? (
                  <Button
                    size="sm"
                    onClick={() => {
                      setReqTarget(d)
                      setReqAmount(d.refundable)
                      setReqReason(
                        d.orderStatus === 'DECLINED' ? 'ORDER_DECLINED' : 'ORDER_CANCELLED',
                      )
                      setReqNote('')
                      setReqError(null)
                      reqMutation.reset()
                    }}
                  >
                    Request refund
                  </Button>
                ) : null}
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-xs text-slate-500">No unrefunded cancelled/declined prepayments.</p>
        )}
      </section>

      <section className="rounded-xl border border-slate-200 bg-white p-5 shadow-xs space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-sm font-semibold text-slate-900">Refund requests</h2>
          <Select
            aria-label="Filter refund status"
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value as RefundStatus | '')}
            className="max-w-xs"
          >
            <option value="">All statuses</option>
            <option value="REQUESTED">Requested</option>
            <option value="APPROVED">Approved</option>
            <option value="REJECTED">Rejected</option>
            <option value="PAID">Paid</option>
          </Select>
        </div>

        {listQuery.isPending ? (
          <Skeleton className="h-32 w-full" />
        ) : listQuery.data && listQuery.data.length > 0 ? (
          <ul className="space-y-2 text-xs">
            {listQuery.data.map((r) => (
              <li
                key={r.id}
                className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-slate-200 p-3"
              >
                <div>
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-mono font-semibold text-slate-900">
                      {r.refundNumber}
                    </span>
                    <StatusPill status={r.status} />
                    <Link
                      to={`/orders/${r.orderId}`}
                      className="font-mono text-merqo-700 hover:underline"
                    >
                      {r.orderNumber}
                    </Link>
                    <MoneyText amount={r.amount} className="font-semibold text-slate-900" />
                  </div>
                  <p className="mt-0.5 text-slate-600">
                    {r.reason} · Requested by {r.requestedByUsername}
                  </p>
                </div>
                <div className="flex flex-wrap gap-2">
                  {r.status === 'REQUESTED' && can('REFUND_APPROVE') ? (
                    <>
                      <Button
                        size="sm"
                        onClick={() => {
                          setDecideTarget({ refund: r, mode: 'APPROVE' })
                          setDecideNote('')
                        }}
                      >
                        Approve
                      </Button>
                      <Button
                        size="sm"
                        variant="outline"
                        className="text-rose-700"
                        onClick={() => {
                          setDecideTarget({ refund: r, mode: 'REJECT' })
                          setDecideNote('')
                        }}
                      >
                        Reject
                      </Button>
                    </>
                  ) : null}
                  {r.status === 'APPROVED' && can('REFUND_PAY') ? (
                    <Button
                      size="sm"
                      onClick={() => {
                        setPayTarget(r)
                        setPayoutMethod('BKASH')
                        setPayoutRef('')
                        setPayoutNote('')
                        setPayError(null)
                        payMutation.reset()
                      }}
                    >
                      Pay
                    </Button>
                  ) : null}
                </div>
              </li>
            ))}
          </ul>
        ) : (
          <EmptyState
            title="No refund requests"
            description="Refund requests submitted from orders or Refunds due appear here."
          />
        )}
      </section>

      <Modal
        open={Boolean(reqTarget)}
        onClose={() => setReqTarget(null)}
        title={`Request refund for ${reqTarget?.orderNumber ?? ''}`}
        size="sm"
      >
        <form onSubmit={submitRequest} className="space-y-3">
          <FormField label="Amount (BDT)" required>
            <Input
              aria-label="Refund amount"
              type="number"
              step="0.01"
              value={reqAmount}
              onChange={(e) => setReqAmount(e.target.value)}
            />
          </FormField>
          <FormField label="Reason" required>
            <Select
              aria-label="Refund reason"
              value={reqReason}
              onChange={(e) => setReqReason(e.target.value as RefundReason)}
            >
              <option value="ORDER_CANCELLED">Order cancelled</option>
              <option value="ORDER_DECLINED">Order declined</option>
              <option value="OVERPAYMENT">Overpayment</option>
              <option value="CUSTOMER_REQUEST">Customer request</option>
              <option value="OTHER">Other (note required)</option>
            </Select>
          </FormField>
          <FormField label="Note" required={reqReason === 'OTHER'}>
            <Textarea value={reqNote} onChange={(e) => setReqNote(e.target.value)} />
          </FormField>
          {reqError ? (
            <p role="alert" className="text-xs font-medium text-rose-700">
              {reqError}
            </p>
          ) : null}
          {reqMutation.isError ? <ErrorState title="Request failed" error={reqMutation.error} /> : null}
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="outline" onClick={() => setReqTarget(null)}>
              Cancel
            </Button>
            <Button type="submit" isLoading={reqMutation.isPending}>
              Submit refund
            </Button>
          </div>
        </form>
      </Modal>

      <Modal
        open={Boolean(decideTarget)}
        onClose={() => setDecideTarget(null)}
        title={`${decideTarget?.mode === 'APPROVE' ? 'Approve' : 'Reject'} ${decideTarget?.refund.refundNumber ?? ''}`}
        size="sm"
      >
        <form
          onSubmit={(e) => {
            e.preventDefault()
            decideMutation.mutate()
          }}
          className="space-y-3"
        >
          <FormField label="Decision note">
            <Textarea value={decideNote} onChange={(e) => setDecideNote(e.target.value)} />
          </FormField>
          {decideMutation.isError ? (
            <ErrorState title="Decision failed" error={decideMutation.error} />
          ) : null}
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="outline" onClick={() => setDecideTarget(null)}>
              Back
            </Button>
            <Button type="submit" isLoading={decideMutation.isPending}>
              Confirm
            </Button>
          </div>
        </form>
      </Modal>

      <Modal
        open={Boolean(payTarget)}
        onClose={() => setPayTarget(null)}
        title={`Pay ${payTarget?.refundNumber ?? ''}`}
        size="sm"
      >
        <form onSubmit={submitPay} className="space-y-3">
          <FormField label="Payout method" required>
            <Select
              aria-label="Payout method"
              value={payoutMethod}
              onChange={(e) => setPayoutMethod(e.target.value as RefundPayoutMethod)}
            >
              <option value="BKASH">BKASH</option>
              <option value="NAGAD">NAGAD</option>
              <option value="CARD">CARD</option>
              <option value="BANK">BANK</option>
              <option value="CASH">CASH</option>
            </Select>
          </FormField>
          {payoutMethod !== 'CASH' ? (
            <FormField label="Payout reference" required>
              <Input
                aria-label="Payout reference"
                value={payoutRef}
                onChange={(e) => setPayoutRef(e.target.value)}
              />
            </FormField>
          ) : null}
          <FormField label="Note" required={payoutMethod === 'CASH'}>
            <Textarea
              aria-label="Payout note"
              value={payoutNote}
              onChange={(e) => setPayoutNote(e.target.value)}
            />
          </FormField>
          {payError ? (
            <p role="alert" className="text-xs font-medium text-rose-700">
              {payError}
            </p>
          ) : null}
          {payMutation.isError ? <ErrorState title="Payout failed" error={payMutation.error} /> : null}
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="outline" onClick={() => setPayTarget(null)}>
              Cancel
            </Button>
            <Button type="submit" isLoading={payMutation.isPending}>
              Record payout
            </Button>
          </div>
        </form>
      </Modal>
    </div>
  )
}

function CodTab() {
  const queryClient = useQueryClient()
  const { toast } = useToast()

  const outstandingQuery = useQuery({
    queryKey: ['cod', 'outstanding'],
    queryFn: fetchCodOutstanding,
  })
  const settlementsQuery = useQuery({
    queryKey: ['cod', 'settlements'],
    queryFn: fetchCourierSettlements,
  })

  const [settleOpen, setSettleOpen] = useState(false)
  const [courier, setCourier] = useState('STEADFAST')
  const [reference, setReference] = useState('')
  const [receivedOn, setReceivedOn] = useState(() => new Date().toISOString().slice(0, 10))
  const [receiveMethod, setReceiveMethod] = useState('BANK')
  const [amountReceived, setAmountReceived] = useState('')
  const [note, setNote] = useState('')
  const [selectedLines, setSelectedLines] = useState<
    Record<string, { selected: boolean; gross: string; fee: string }>
  >({})
  const [settleError, setSettleError] = useState<string | null>(null)
  const [detailModal, setDetailModal] = useState<SettlementDetailView | null>(null)

  const settleMutation = useMutation({
    mutationFn: () => {
      const lines = Object.entries(selectedLines)
        .filter(([, v]) => v.selected && toPaisa(v.gross) > 0)
        .map(([orderId, v]) => ({
          orderId,
          codCollectedAmount: fromPaisa(toPaisa(v.gross)),
          fee: fromPaisa(toPaisa(v.fee)),
        }))
      return createCourierSettlement({
        courier: courier.trim(),
        reference: reference.trim().toUpperCase(),
        receivedOn,
        receiveMethod,
        amountReceived: fromPaisa(toPaisa(amountReceived)),
        note: note.trim() || null,
        lines,
      })
    },
    onSuccess: (created) => {
      setSettleOpen(false)
      setSelectedLines({})
      setReference('')
      setAmountReceived('')
      setNote('')
      setSettleError(null)
      queryClient.invalidateQueries({ queryKey: ['cod'] })
      queryClient.invalidateQueries({ queryKey: ['payments'] })
      queryClient.invalidateQueries({ queryKey: ['orders'] })
      toast({
        title: `Settlement ${created.settlementNumber} recorded (${created.differenceKind})`,
        tone: 'success',
      })
    },
  })

  const liveMath = useMemo(() => {
    let grossTotalPaisa = 0
    let feesTotalPaisa = 0
    for (const v of Object.values(selectedLines)) {
      if (!v.selected) continue
      grossTotalPaisa += toPaisa(v.gross)
      feesTotalPaisa += toPaisa(v.fee)
    }
    const expectedNetPaisa = grossTotalPaisa - feesTotalPaisa
    const receivedPaisa = amountReceived.trim() === '' ? expectedNetPaisa : toPaisa(amountReceived)
    const diffPaisa = receivedPaisa - expectedNetPaisa
    const kind = diffPaisa === 0 ? 'NONE' : diffPaisa > 0 ? 'OVER' : 'SHORT'
    return { grossTotalPaisa, feesTotalPaisa, expectedNetPaisa, receivedPaisa, diffPaisa, kind }
  }, [selectedLines, amountReceived])

  function openSettleModal(preselectOrder?: CodOutstandingOrderView) {
    const initial: Record<string, { selected: boolean; gross: string; fee: string }> = {}
    if (preselectOrder) {
      setCourier(preselectOrder.courier ?? 'STEADFAST')
      initial[preselectOrder.orderId] = {
        selected: true,
        gross: preselectOrder.withCourier,
        fee: '0.00',
      }
      setAmountReceived(preselectOrder.withCourier)
    } else {
      setAmountReceived('')
    }
    setSelectedLines(initial)
    setReference('')
    setNote('')
    setSettleError(null)
    settleMutation.reset()
    setSettleOpen(true)
  }

  function submitSettlement(e: FormEvent) {
    e.preventDefault()
    const chosenCount = Object.values(selectedLines).filter(
      (v) => v.selected && toPaisa(v.gross) > 0,
    ).length
    if (chosenCount === 0) {
      setSettleError('Select at least one order with collected COD balance.')
      return
    }
    if (!reference.trim()) {
      setSettleError('Settlement reference is required.')
      return
    }
    if (liveMath.diffPaisa !== 0 && !note.trim()) {
      setSettleError('An explanatory note is required when settlement amount has an over/short difference.')
      return
    }
    setSettleError(null)
    settleMutation.mutate()
  }

  if (outstandingQuery.isPending || settlementsQuery.isPending) {
    return <Skeleton className="h-56 w-full rounded-xl" />
  }

  const outstanding = outstandingQuery.data
  const settlements = settlementsQuery.data ?? []

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-xs text-slate-600">
          Gap warning triggers after <strong>{outstanding?.gapThresholdDays ?? 7}</strong> days from
          earliest unsettled collection date.
        </p>
        <Button onClick={() => openSettleModal()}>New courier settlement</Button>
      </div>

      {outstanding?.courierTotals.length ? (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {outstanding.courierTotals.map((ct) => (
            <div key={ct.courier} className="rounded-xl border border-slate-200 bg-white p-4 shadow-xs text-xs space-y-1">
              <div className="flex items-center justify-between">
                <p className="text-sm font-semibold text-slate-900">{ct.courier}</p>
                {ct.gapCount > 0 ? <Badge tone="danger">{ct.gapCount} gap(s)</Badge> : null}
              </div>
              <p>
                With courier: <MoneyText amount={ct.withCourier} className="font-semibold text-amber-800" />
              </p>
              <p className="text-slate-500">
                Uncollected: <MoneyText amount={ct.uncollected} /> · Settled:{' '}
                <MoneyText amount={ct.settledGross} />
              </p>
            </div>
          ))}
        </div>
      ) : null}

      <section className="rounded-xl border border-slate-200 bg-white shadow-xs overflow-hidden">
        <div className="border-b border-slate-100 px-5 py-3">
          <h2 className="text-sm font-semibold text-slate-900">Outstanding COD orders</h2>
        </div>
        {!outstanding?.orders.length ? (
          <div className="p-4">
            <EmptyState
              title="No outstanding COD balances"
              description="All delivered/shipped COD orders are collected and settled."
            />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="min-w-full divide-y divide-slate-200 text-xs">
              <thead className="bg-slate-50 text-slate-500 uppercase">
                <tr>
                  <th className="px-4 py-2.5 text-left">Order</th>
                  <th className="px-4 py-2.5 text-left">Courier</th>
                  <th className="px-4 py-2.5 text-right">With courier</th>
                  <th className="px-4 py-2.5 text-right">Uncollected</th>
                  <th className="px-4 py-2.5 text-left">Gap</th>
                  <th className="px-4 py-2.5 text-right">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {outstanding.orders.map((o) => (
                  <tr key={o.orderId}>
                    <td className="px-4 py-2.5">
                      <Link
                        to={`/orders/${o.orderId}`}
                        className="font-mono font-semibold text-merqo-700 hover:underline"
                      >
                        {o.orderNumber}
                      </Link>
                      <p className="text-slate-600">{o.customerName}</p>
                    </td>
                    <td className="px-4 py-2.5">{o.courier ?? '—'}</td>
                    <td className="px-4 py-2.5 text-right font-semibold">
                      <MoneyText amount={o.withCourier} />
                    </td>
                    <td className="px-4 py-2.5 text-right">
                      <MoneyText amount={o.uncollected} />
                    </td>
                    <td className="px-4 py-2.5">
                      {o.gap ? <Badge tone="danger">Gap &gt; {outstanding.gapThresholdDays}d</Badge> : '—'}
                    </td>
                    <td className="px-4 py-2.5 text-right">
                      {Number(o.withCourier) > 0 ? (
                        <Button size="sm" variant="outline" onClick={() => openSettleModal(o)}>
                          Settle
                        </Button>
                      ) : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="rounded-xl border border-slate-200 bg-white shadow-xs overflow-hidden">
        <div className="border-b border-slate-100 px-5 py-3">
          <h2 className="text-sm font-semibold text-slate-900">Recorded courier settlements</h2>
        </div>
        {settlements.length === 0 ? (
          <p className="p-4 text-xs text-slate-500">No courier settlements recorded yet.</p>
        ) : (
          <ul className="divide-y divide-slate-100 text-xs">
            {settlements.map((s) => (
              <li
                key={s.id}
                className="flex flex-wrap items-center justify-between gap-3 px-5 py-3"
              >
                <div>
                  <span className="font-mono font-semibold text-slate-900">
                    {s.settlementNumber}
                  </span>{' '}
                  <Badge tone="neutral">{s.courier}</Badge>{' '}
                  <StatusPill status={s.differenceKind} />{' '}
                  <span className="font-mono text-slate-600">Ref: {s.reference}</span>
                  <p className="mt-0.5 text-slate-500">
                    Received <MoneyText amount={s.amountReceived} className="font-semibold text-slate-900" /> on{' '}
                    <DateText value={s.receivedOn} /> · Fees <MoneyText amount={s.feesTotal} /> ·{' '}
                    {s.lineCount} order(s)
                  </p>
                </div>
                <Button
                  size="sm"
                  variant="outline"
                  onClick={async () => {
                    const detail = await fetchCourierSettlementDetail(s.id)
                    setDetailModal(detail)
                  }}
                >
                  View breakdown
                </Button>
              </li>
            ))}
          </ul>
        )}
      </section>

      <Modal
        open={settleOpen}
        onClose={() => setSettleOpen(false)}
        title="Record courier settlement"
        size="lg"
      >
        <form onSubmit={submitSettlement} className="space-y-4">
          <div className="grid gap-3 sm:grid-cols-4">
            <FormField label="Courier" required>
              <Select
                aria-label="Settlement courier"
                value={courier}
                onChange={(e) => setCourier(e.target.value)}
              >
                <option value="STEADFAST">STEADFAST</option>
                <option value="PATHAO">PATHAO</option>
              </Select>
            </FormField>
            <FormField label="Reference" required>
              <Input
                aria-label="Settlement reference"
                value={reference}
                onChange={(e) => setReference(e.target.value)}
              />
            </FormField>
            <FormField label="Received method" required>
              <Select
                aria-label="Receive method"
                value={receiveMethod}
                onChange={(e) => setReceiveMethod(e.target.value)}
              >
                <option value="BANK">BANK</option>
                <option value="BKASH">BKASH</option>
                <option value="NAGAD">NAGAD</option>
                <option value="CASH">CASH</option>
              </Select>
            </FormField>
            <FormField label="Received on" required>
              <Input
                aria-label="Received date"
                type="date"
                value={receivedOn}
                onChange={(e) => setReceivedOn(e.target.value)}
              />
            </FormField>
          </div>

          <div className="space-y-2 rounded-lg border border-slate-200 p-3 text-xs">
            <p className="font-semibold text-slate-800">Select collected orders to settle</p>
            {(outstanding?.orders ?? [])
              .filter((o) => Number(o.withCourier) > 0)
              .map((o) => {
                const entry = selectedLines[o.orderId] ?? {
                  selected: false,
                  gross: o.withCourier,
                  fee: '0.00',
                }
                return (
                  <div
                    key={o.orderId}
                    className="grid items-center gap-2 border-t border-slate-100 pt-2 sm:grid-cols-4"
                  >
                    <label className="flex items-center gap-2 font-mono font-medium">
                      <input
                        type="checkbox"
                        checked={entry.selected}
                        onChange={(e) =>
                          setSelectedLines((prev) => ({
                            ...prev,
                            [o.orderId]: { ...entry, selected: e.target.checked },
                          }))
                        }
                      />
                      {o.orderNumber} (max ৳ {o.withCourier})
                    </label>
                    <Input
                      aria-label={`Gross ${o.orderNumber}`}
                      type="number"
                      step="0.01"
                      value={entry.gross}
                      disabled={!entry.selected}
                      onChange={(e) =>
                        setSelectedLines((prev) => ({
                          ...prev,
                          [o.orderId]: { ...entry, gross: e.target.value },
                        }))
                      }
                    />
                    <Input
                      aria-label={`Fee ${o.orderNumber}`}
                      type="number"
                      step="0.01"
                      value={entry.fee}
                      disabled={!entry.selected}
                      onChange={(e) =>
                        setSelectedLines((prev) => ({
                          ...prev,
                          [o.orderId]: { ...entry, fee: e.target.value },
                        }))
                      }
                    />
                    <span className="text-right font-semibold">
                      Net: ৳ {fromPaisa(toPaisa(entry.gross) - toPaisa(entry.fee))}
                    </span>
                  </div>
                )
              })}
          </div>

          <div className="grid gap-3 rounded-lg bg-slate-50 p-3 text-xs sm:grid-cols-4">
            <div>
              <p className="text-slate-500">Gross total</p>
              <p className="font-semibold">৳ {fromPaisa(liveMath.grossTotalPaisa)}</p>
            </div>
            <div>
              <p className="text-slate-500">Fees total</p>
              <p className="font-semibold">৳ {fromPaisa(liveMath.feesTotalPaisa)}</p>
            </div>
            <div>
              <p className="text-slate-500">Expected net</p>
              <p className="font-semibold">৳ {fromPaisa(liveMath.expectedNetPaisa)}</p>
            </div>
            <div>
              <p className="text-slate-500">Live difference</p>
              <p className="font-semibold">
                {liveMath.kind} (৳ {fromPaisa(liveMath.diffPaisa)})
              </p>
            </div>
          </div>

          <div className="grid gap-3 sm:grid-cols-2">
            <FormField label="Amount received (BDT)" required>
              <Input
                aria-label="Amount received"
                type="number"
                step="0.01"
                value={amountReceived}
                placeholder={fromPaisa(liveMath.expectedNetPaisa)}
                onChange={(e) => setAmountReceived(e.target.value)}
              />
            </FormField>
            <FormField label="Variance note" required={liveMath.diffPaisa !== 0}>
              <Input
                aria-label="Settlement note"
                value={note}
                onChange={(e) => setNote(e.target.value)}
              />
            </FormField>
          </div>

          {settleError ? (
            <p role="alert" className="text-xs font-medium text-rose-700">
              {settleError}
            </p>
          ) : null}
          {settleMutation.isError ? (
            <ErrorState title="Settlement failed" error={settleMutation.error} />
          ) : null}

          <div className="flex justify-end gap-2 pt-2">
            <Button variant="outline" onClick={() => setSettleOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" isLoading={settleMutation.isPending}>
              Save settlement
            </Button>
          </div>
        </form>
      </Modal>

      <Modal
        open={Boolean(detailModal)}
        onClose={() => setDetailModal(null)}
        title={`Settlement ${detailModal?.settlementNumber ?? ''}`}
        size="md"
      >
        {detailModal ? (
          <div className="space-y-3 text-xs">
            <p>
              <strong>{detailModal.courier}</strong> · Ref {detailModal.reference} ·{' '}
              <StatusPill status={detailModal.differenceKind} /> (diff{' '}
              <MoneyText amount={detailModal.difference} />)
            </p>
            {detailModal.note ? <p className="text-slate-600">Note: {detailModal.note}</p> : null}
            <ul className="divide-y divide-slate-100 rounded border border-slate-200">
              {detailModal.lines.map((l) => (
                <li key={l.id} className="flex justify-between px-3 py-2">
                  <span className="font-mono font-semibold">{l.orderNumber}</span>
                  <span>
                    Gross <MoneyText amount={l.codCollectedAmount} /> − Fee{' '}
                    <MoneyText amount={l.fee} /> = <strong><MoneyText amount={l.net} /></strong>
                  </span>
                </li>
              ))}
            </ul>
          </div>
        ) : null}
      </Modal>
    </div>
  )
}
