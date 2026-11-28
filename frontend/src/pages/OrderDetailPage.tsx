import { useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  cancelOrder,
  confirmOrder,
  declineOrder,
  editOrderCustomer,
  fetchOrderDetail,
  logOrderCall,
  requestOrderCancellation,
  type CallResult,
  type CancelReason,
  type DeclineReason,
  type OrderCancelResponse,
  type OrderDetailView,
} from '../api/orders'
import { OrderFlagsBadges, RiskBadge } from '../components/OrderBadges'
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
  Skeleton,
  StatusPill,
  Textarea,
  useToast,
} from '../components/ui'
import {
  callLogFormSchema,
  cancelOrderFormSchema,
  declineOrderFormSchema,
  editCustomerFormSchema,
} from '../lib/validation'
import { useSession } from '../session/sessionContext'

const CALL_RESULTS: Array<{ value: CallResult; label: string }> = [
  { value: 'ANSWERED', label: 'Answered' },
  { value: 'NO_ANSWER', label: 'No answer' },
  { value: 'BUSY', label: 'Busy' },
  { value: 'WRONG_NUMBER', label: 'Wrong number' },
  { value: 'CALL_BACK_REQUESTED', label: 'Call back requested' },
]

const DECLINE_REASONS: Array<{ value: DeclineReason; label: string }> = [
  { value: 'CUSTOMER_DECLINED', label: 'Customer declined' },
  { value: 'CUSTOMER_UNREACHABLE', label: 'Customer unreachable' },
  { value: 'OUT_OF_STOCK', label: 'Out of stock' },
  { value: 'INVALID_ADDRESS', label: 'Invalid address' },
  { value: 'SUSPECTED_FAKE', label: 'Suspected fake' },
  { value: 'OTHER', label: 'Other (note required)' },
]

const CANCEL_REASONS: Array<{ value: CancelReason; label: string }> = [
  { value: 'CUSTOMER_REQUESTED', label: 'Customer requested' },
  { value: 'STOCK_PROBLEM', label: 'Stock problem' },
  { value: 'ADDRESS_PROBLEM', label: 'Address problem' },
  { value: 'OTHER', label: 'Other (note required)' },
]

export default function OrderDetailPage() {
  const { id = '' } = useParams()
  const { can } = useSession()
  const queryClient = useQueryClient()
  const { toast } = useToast()

  const orderQuery = useQuery({
    queryKey: ['orders', 'detail', id],
    queryFn: () => fetchOrderDetail(id),
    enabled: Boolean(id),
  })

  const [callResult, setCallResult] = useState<CallResult>('ANSWERED')
  const [callNote, setCallNote] = useState('')
  const [callFollowUp, setCallFollowUp] = useState('')
  const [callError, setCallError] = useState<string | null>(null)

  const [editOpen, setEditOpen] = useState(false)
  const [editName, setEditName] = useState('')
  const [editPhone, setEditPhone] = useState('')
  const [editAddress, setEditAddress] = useState('')
  const [editDistrict, setEditDistrict] = useState('')
  const [editError, setEditError] = useState<string | null>(null)

  const [confirmOpen, setConfirmOpen] = useState(false)
  const [declineOpen, setDeclineOpen] = useState(false)
  const [declineReason, setDeclineReason] = useState<DeclineReason>('CUSTOMER_DECLINED')
  const [declineNote, setDeclineNote] = useState('')
  const [declineError, setDeclineError] = useState<string | null>(null)

  const [cancelOpen, setCancelOpen] = useState(false)
  const [cancelReason, setCancelReason] = useState<CancelReason>('CUSTOMER_REQUESTED')
  const [cancelNote, setCancelNote] = useState('')
  const [cancelError, setCancelError] = useState<string | null>(null)
  const [cancelBanner, setCancelBanner] = useState<OrderCancelResponse | null>(null)

  const [cancelReqOpen, setCancelReqOpen] = useState(false)
  const [cancelReqNote, setCancelReqNote] = useState('')
  const [cancelReqError, setCancelReqError] = useState<string | null>(null)

  function refreshOrder(updated: OrderDetailView) {
    queryClient.setQueryData(['orders', 'detail', id], updated)
    queryClient.invalidateQueries({ queryKey: ['orders', 'list'] })
    queryClient.invalidateQueries({ queryKey: ['orders', 'counts'] })
  }

  const callMutation = useMutation({
    mutationFn: () =>
      logOrderCall(id, {
        result: callResult,
        note: callNote.trim() || undefined,
        followUpAt: callFollowUp ? new Date(callFollowUp).toISOString() : null,
      }),
    onSuccess: (updated) => {
      setCallNote('')
      setCallFollowUp('')
      setCallError(null)
      refreshOrder(updated)
      toast({ title: 'Call attempt logged', tone: 'success' })
    },
  })

  const editCustomerMutation = useMutation({
    mutationFn: () =>
      editOrderCustomer(id, {
        customerName: editName.trim(),
        phone: editPhone.trim(),
        address: editAddress.trim(),
        district: editDistrict.trim() || null,
      }),
    onSuccess: (updated) => {
      setEditOpen(false)
      setEditError(null)
      refreshOrder(updated)
      toast({ title: 'Customer details updated', tone: 'success' })
    },
  })

  const confirmMutation = useMutation({
    mutationFn: () => confirmOrder(id),
    onSuccess: (updated) => {
      setConfirmOpen(false)
      refreshOrder(updated)
      toast({ title: 'Order confirmed & warehouse stock reserved', tone: 'success' })
    },
  })

  const declineMutation = useMutation({
    mutationFn: () =>
      declineOrder(id, { reason: declineReason, note: declineNote.trim() || undefined }),
    onSuccess: (updated) => {
      setDeclineOpen(false)
      setDeclineNote('')
      setDeclineError(null)
      refreshOrder(updated)
      toast({ title: 'Order declined', tone: 'success' })
    },
  })

  const cancelMutation = useMutation({
    mutationFn: () =>
      cancelOrder(id, { reason: cancelReason, note: cancelNote.trim() || undefined }),
    onSuccess: (res) => {
      setCancelOpen(false)
      setCancelNote('')
      setCancelError(null)
      setCancelBanner(res)
      refreshOrder(res.order)
      toast({
        title: res.releasedReservation
          ? 'Order cancelled & stock reservation released'
          : 'Order cancelled',
        tone: 'success',
      })
    },
  })

  const cancelRequestMutation = useMutation({
    mutationFn: () => requestOrderCancellation(id, cancelReqNote.trim()),
    onSuccess: (updated) => {
      setCancelReqOpen(false)
      setCancelReqNote('')
      setCancelReqError(null)
      refreshOrder(updated)
      toast({ title: 'Cancellation request flagged', tone: 'success' })
    },
  })

  if (orderQuery.isPending) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-20 w-full rounded-xl" />
        <Skeleton className="h-64 w-full rounded-xl" />
      </div>
    )
  }

  if (orderQuery.error || !orderQuery.data) {
    return (
      <ErrorState
        title="Could not load order"
        error={orderQuery.error}
        onRetry={() => orderQuery.refetch()}
      />
    )
  }

  const order = orderQuery.data
  const canCallEdit =
    order.status === 'PENDING_CONFIRMATION' || order.status === 'FOLLOW_UP'
  const canConfirm = canCallEdit && can('ORDER_CONFIRM')
  const canDecline = canCallEdit && can('ORDER_DECLINE')
  const canCancel =
    (canCallEdit || order.status === 'CONFIRMED') && can('ORDER_CANCEL')
  const canFlagCancelReq =
    order.status !== 'CANCELLED' && order.status !== 'DECLINED' && can('ORDER_CALL')

  function openEditModal() {
    setEditName(order.customerName)
    setEditPhone(order.phoneNormalized ?? order.phoneRaw)
    setEditAddress(order.address)
    setEditDistrict(order.district ?? '')
    setEditError(null)
    editCustomerMutation.reset()
    setEditOpen(true)
  }

  function submitCall(e: FormEvent) {
    e.preventDefault()
    const parsed = callLogFormSchema.safeParse({
      result: callResult,
      note: callNote,
      followUpAt: callFollowUp,
    })
    if (!parsed.success) {
      setCallError(parsed.error.issues[0]?.message ?? 'Invalid call details')
      return
    }
    setCallError(null)
    callMutation.mutate()
  }

  function submitEditCustomer(e: FormEvent) {
    e.preventDefault()
    const parsed = editCustomerFormSchema.safeParse({
      customerName: editName,
      phone: editPhone,
      address: editAddress,
      district: editDistrict,
    })
    if (!parsed.success) {
      setEditError(parsed.error.issues[0]?.message ?? 'Invalid customer fields')
      return
    }
    setEditError(null)
    editCustomerMutation.mutate()
  }

  function submitDecline(e: FormEvent) {
    e.preventDefault()
    const parsed = declineOrderFormSchema.safeParse({
      reason: declineReason,
      note: declineNote,
    })
    if (!parsed.success) {
      setDeclineError(parsed.error.issues[0]?.message ?? 'Invalid decline details')
      return
    }
    setDeclineError(null)
    declineMutation.mutate()
  }

  function submitCancel(e: FormEvent) {
    e.preventDefault()
    const parsed = cancelOrderFormSchema.safeParse({
      reason: cancelReason,
      note: cancelNote,
    })
    if (!parsed.success) {
      setCancelError(parsed.error.issues[0]?.message ?? 'Invalid cancellation details')
      return
    }
    setCancelError(null)
    cancelMutation.mutate()
  }

  function submitCancelRequest(e: FormEvent) {
    e.preventDefault()
    if (!cancelReqNote.trim()) {
      setCancelReqError('Enter a note explaining the customer cancellation request.')
      return
    }
    setCancelReqError(null)
    cancelRequestMutation.mutate()
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-2 text-xs text-slate-500">
        <Link to="/orders" className="font-medium text-merqo-700 hover:underline">
          &larr; Back to online orders
        </Link>
      </div>

      <PageHeader
        title={`Order ${order.orderNumber}`}
        description={`${order.channel}${order.sourceLabel ? ` · ${order.sourceLabel}` : ''}${order.externalOrderId ? ` · External ID: ${order.externalOrderId}` : ''}`}
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <StatusPill status={order.status} />
            <RiskBadge tier={order.riskTier} />
            {canConfirm ? (
              <Button onClick={() => setConfirmOpen(true)} disabled={order.flags.invalidPhone}>
                Confirm order
              </Button>
            ) : null}
            {canDecline ? (
              <Button variant="outline" onClick={() => setDeclineOpen(true)}>
                Decline
              </Button>
            ) : null}
            {canFlagCancelReq ? (
              <Button variant="outline" onClick={() => setCancelReqOpen(true)}>
                Flag cancel request
              </Button>
            ) : null}
            {canCancel ? (
              <Button
                variant="outline"
                className="text-rose-700 hover:bg-rose-50"
                onClick={() => setCancelOpen(true)}
              >
                Cancel order
              </Button>
            ) : null}
          </div>
        }
      />

      {cancelBanner ? (
        <div
          role="status"
          className="rounded-xl border border-amber-200 bg-amber-50 px-5 py-3 text-sm text-amber-950"
        >
          <p className="font-semibold">Order cancelled</p>
          <p className="mt-0.5">
            {cancelBanner.releasedReservation
              ? 'Hard warehouse stock reservations were released back to available stock.'
              : 'No hard reservation needed releasing.'}
            {cancelBanner.advanceClaimed
              ? ` Claimed advance of ৳ ${cancelBanner.advanceAmount} noted for Phase 5 refund review.`
              : ''}
          </p>
        </div>
      ) : null}

      {order.flags.invalidPhone ||
      order.flags.priceMismatch ||
      order.flags.cancellationRequested ||
      order.flags.highRisk ? (
        <div className="space-y-2 rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-950">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <p className="font-semibold">Attention flags</p>
            <OrderFlagsBadges flags={order.flags} />
          </div>
          {order.flags.invalidPhone ? (
            <p>
              Customer phone <code>{order.phoneRaw}</code> could not be normalized to{' '}
              <code>01XXXXXXXXX</code>. Edit customer details before confirming.
            </p>
          ) : null}
          {order.flags.priceMismatch ? (
            <p>One or more lines differ from Merqo&apos;s live online catalog/promotion price.</p>
          ) : null}
          {order.flags.cancellationRequested ? (
            <p>
              Cancellation requested: <strong>{order.cancellationRequestNote}</strong>
            </p>
          ) : null}
          {order.flags.highRisk ? (
            <p>
              High refusal risk: this phone has {order.phoneHistory.refused} refused and{' '}
              {order.phoneHistory.returned} returned order(s).
            </p>
          ) : null}
        </div>
      ) : null}

      <div className="grid gap-6 lg:grid-cols-3">
        <div className="space-y-4 lg:col-span-2">
          <section className="rounded-xl border border-slate-200 bg-white p-5 shadow-xs">
            <div className="flex items-start justify-between gap-3">
              <div>
                <h2 className="text-sm font-semibold text-slate-900">Customer & delivery</h2>
                <p className="mt-1 font-medium text-slate-900">{order.customerName}</p>
                <p className="font-mono text-xs text-slate-700">
                  {order.phoneNormalized ?? order.phoneRaw}
                </p>
                <p className="mt-1 text-sm text-slate-700">{order.address}</p>
                {order.district ? (
                  <p className="text-xs text-slate-500">District: {order.district}</p>
                ) : null}
                {order.note ? (
                  <p className="mt-2 rounded bg-slate-50 p-2 text-xs text-slate-700">
                    Note: {order.note}
                  </p>
                ) : null}
              </div>
              {canCallEdit && can('ORDER_CALL') ? (
                <Button size="sm" variant="outline" onClick={openEditModal}>
                  Edit customer
                </Button>
              ) : null}
            </div>

            <div className="mt-4 grid grid-cols-3 gap-2 border-t border-slate-100 pt-3 text-xs sm:grid-cols-6">
              <div>
                <p className="text-slate-500">Prior orders</p>
                <p className="font-semibold">{order.phoneHistory.total}</p>
              </div>
              <div>
                <p className="text-slate-500">Delivered</p>
                <p className="font-semibold text-emerald-700">{order.phoneHistory.delivered}</p>
              </div>
              <div>
                <p className="text-slate-500">Cancelled</p>
                <p className="font-semibold">{order.phoneHistory.cancelled}</p>
              </div>
              <div>
                <p className="text-slate-500">Declined</p>
                <p className="font-semibold">{order.phoneHistory.declined}</p>
              </div>
              <div>
                <p className="text-slate-500">Refused</p>
                <p className="font-semibold text-rose-700">{order.phoneHistory.refused}</p>
              </div>
              <div>
                <p className="text-slate-500">Returned</p>
                <p className="font-semibold text-rose-700">{order.phoneHistory.returned}</p>
              </div>
            </div>
          </section>

          <section className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-xs">
            <div className="border-b border-slate-100 px-5 py-3">
              <h2 className="text-sm font-semibold text-slate-900">Order items</h2>
            </div>
            <div className="overflow-x-auto">
              <table className="min-w-full divide-y divide-slate-200 text-sm">
                <thead className="bg-slate-50 text-xs text-slate-500 uppercase">
                  <tr>
                    <th className="px-4 py-2 text-left">Item</th>
                    <th className="px-4 py-2 text-right">Qty</th>
                    <th className="px-4 py-2 text-right">Unit price</th>
                    <th className="px-4 py-2 text-right">Catalog online</th>
                    <th className="px-4 py-2 text-right">WH avail</th>
                    <th className="px-4 py-2 text-right">Line total</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {order.lines.map((line) => (
                    <tr key={line.id}>
                      <td className="px-4 py-2.5">
                        <p className="font-medium text-slate-900">{line.productName}</p>
                        <p className="font-mono text-xs text-slate-500">{line.sku}</p>
                      </td>
                      <td className="px-4 py-2.5 text-right tabular-nums">{line.quantity}</td>
                      <td className="px-4 py-2.5 text-right">
                        <MoneyText amount={line.unitPrice} />
                      </td>
                      <td className="px-4 py-2.5 text-right text-xs text-slate-600">
                        {line.catalogOnlinePrice ? (
                          <MoneyText amount={line.catalogOnlinePrice} />
                        ) : (
                          '—'
                        )}
                      </td>
                      <td className="px-4 py-2.5 text-right text-xs tabular-nums">
                        {line.availability ? (
                          <span
                            className={
                              line.availability.available >= line.quantity
                                ? 'font-semibold text-emerald-700'
                                : 'font-semibold text-rose-700'
                            }
                          >
                            {line.availability.available} (on hand {line.availability.onHand}, res{' '}
                            {line.availability.reserved})
                          </span>
                        ) : (
                          '—'
                        )}
                      </td>
                      <td className="px-4 py-2.5 text-right font-semibold">
                        <MoneyText amount={line.lineTotal} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="flex flex-wrap items-center justify-between gap-4 border-t border-slate-100 bg-slate-50 px-5 py-3 text-sm">
              <div className="space-y-0.5 text-xs text-slate-600">
                <p>
                  Payment mode: <Badge tone="neutral">{order.paymentMode}</Badge>
                </p>
                <p>
                  Advance claimed: <MoneyText amount={order.advanceAmount} /> · COD:{' '}
                  <MoneyText amount={order.codAmount} />
                </p>
                {order.claimedAdvanceMethod ? (
                  <p>
                    Channel: {order.claimedAdvanceMethod}{' '}
                    {order.claimedAdvanceReference ? `(${order.claimedAdvanceReference})` : ''}
                  </p>
                ) : null}
              </div>
              <div className="text-right">
                <p className="text-xs text-slate-500">
                  Subtotal <MoneyText amount={order.subtotal} /> · Shipping{' '}
                  <MoneyText amount={order.shippingFee} /> · Discount{' '}
                  <MoneyText amount={order.discountTotal} />
                </p>
                <p className="text-base font-bold text-slate-900">
                  Total: <MoneyText amount={order.total} />
                </p>
              </div>
            </div>
          </section>

          <section className="rounded-xl border border-slate-200 bg-white p-5 shadow-xs">
            <h2 className="text-sm font-semibold text-slate-900">Status history</h2>
            <ul className="mt-3 space-y-2 text-xs">
              {order.statusHistory.map((h) => (
                <li
                  key={h.id}
                  className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-100 pb-2 last:border-b-0"
                >
                  <div>
                    <StatusPill status={h.toStatus} />{' '}
                    <span className="ml-2 font-medium text-slate-700">{h.actorLabel}</span>
                    {h.reason ? <span className="ml-1 text-slate-500">({h.reason})</span> : null}
                    {h.note ? <p className="mt-0.5 text-slate-600">{h.note}</p> : null}
                  </div>
                  <DateText value={h.createdAt} withTime className="text-slate-500" />
                </li>
              ))}
            </ul>
          </section>
        </div>

        <div className="space-y-4">
          {canCallEdit && can('ORDER_CALL') ? (
            <section className="rounded-xl border border-slate-200 bg-white p-5 shadow-xs">
              <h2 className="text-sm font-semibold text-slate-900">Log confirmation call</h2>
              <form onSubmit={submitCall} className="mt-3 space-y-3">
                <FormField label="Call outcome" required>
                  <Select
                    aria-label="Call outcome"
                    value={callResult}
                    onChange={(e) => setCallResult(e.target.value as CallResult)}
                  >
                    {CALL_RESULTS.map((opt) => (
                      <option key={opt.value} value={opt.value}>
                        {opt.label}
                      </option>
                    ))}
                  </Select>
                </FormField>
                <FormField label="Note" required={callResult !== 'ANSWERED'}>
                  <Textarea
                    aria-label="Call note"
                    value={callNote}
                    onChange={(e) => setCallNote(e.target.value)}
                    placeholder={
                      callResult === 'ANSWERED'
                        ? 'Optional call notes...'
                        : 'Required when customer does not answer'
                    }
                  />
                </FormField>
                {callResult === 'NO_ANSWER' ||
                callResult === 'BUSY' ||
                callResult === 'CALL_BACK_REQUESTED' ? (
                  <FormField label="Follow-up time (optional)">
                    <Input
                      type="datetime-local"
                      value={callFollowUp}
                      onChange={(e) => setCallFollowUp(e.target.value)}
                    />
                  </FormField>
                ) : null}
                {callError ? (
                  <p role="alert" className="text-xs font-medium text-rose-700">
                    {callError}
                  </p>
                ) : null}
                {callMutation.isError ? (
                  <ErrorState title="Could not log call" error={callMutation.error} />
                ) : null}
                <Button type="submit" isLoading={callMutation.isPending} className="w-full">
                  Save call log
                </Button>
              </form>
            </section>
          ) : null}

          <section className="rounded-xl border border-slate-200 bg-white p-5 shadow-xs">
            <h2 className="text-sm font-semibold text-slate-900">Call log timeline</h2>
            {order.callLogs.length === 0 ? (
              <p className="mt-2 text-xs text-slate-500">No calls recorded yet.</p>
            ) : (
              <ul className="mt-3 space-y-3 text-xs">
                {order.callLogs.map((c) => (
                  <li key={c.id} className="rounded-lg border border-slate-100 bg-slate-50 p-3">
                    <div className="flex items-center justify-between gap-2">
                      <span className="font-semibold text-slate-900">
                        #{c.attemptNumber} · {c.result.replaceAll('_', ' ')}
                      </span>
                      <DateText value={c.calledAt} withTime className="text-slate-500" />
                    </div>
                    {c.note ? <p className="mt-1 text-slate-700">{c.note}</p> : null}
                    <p className="mt-1 text-slate-500">By {c.calledByUsername}</p>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      </div>

      <ConfirmDialog
        open={confirmOpen}
        onClose={() => setConfirmOpen(false)}
        onConfirm={() => confirmMutation.mutate()}
        isConfirming={confirmMutation.isPending}
        title="Confirm order and reserve stock?"
        confirmLabel="Confirm order"
      >
        <p>
          Confirm {order.orderNumber} and hard-reserve warehouse inventory for all {order.lines.length}{' '}
          line(s)?
        </p>
        {confirmMutation.isError ? (
          <div className="mt-2">
            <ErrorState title="Confirmation failed" error={confirmMutation.error} />
          </div>
        ) : null}
      </ConfirmDialog>

      <Modal
        open={editOpen}
        onClose={() => setEditOpen(false)}
        title="Edit customer details"
        size="md"
      >
        <form onSubmit={submitEditCustomer} className="space-y-3">
          <FormField label="Customer name" required>
            <Input value={editName} onChange={(e) => setEditName(e.target.value)} />
          </FormField>
          <FormField label="Mobile phone (BD)" required>
            <Input value={editPhone} onChange={(e) => setEditPhone(e.target.value)} />
          </FormField>
          <FormField label="Address" required>
            <Textarea value={editAddress} onChange={(e) => setEditAddress(e.target.value)} />
          </FormField>
          <FormField label="District">
            <Input value={editDistrict} onChange={(e) => setEditDistrict(e.target.value)} />
          </FormField>
          {editError ? (
            <p role="alert" className="text-xs font-medium text-rose-700">
              {editError}
            </p>
          ) : null}
          {editCustomerMutation.isError ? (
            <ErrorState title="Update failed" error={editCustomerMutation.error} />
          ) : null}
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="outline" onClick={() => setEditOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" isLoading={editCustomerMutation.isPending}>
              Save customer
            </Button>
          </div>
        </form>
      </Modal>

      <Modal open={declineOpen} onClose={() => setDeclineOpen(false)} title="Decline order" size="sm">
        <form onSubmit={submitDecline} className="space-y-3">
          <FormField label="Decline reason" required>
            <Select
              value={declineReason}
              onChange={(e) => setDeclineReason(e.target.value as DeclineReason)}
            >
              {DECLINE_REASONS.map((r) => (
                <option key={r.value} value={r.value}>
                  {r.label}
                </option>
              ))}
            </Select>
          </FormField>
          <FormField label="Note" required={declineReason === 'OTHER'}>
            <Textarea value={declineNote} onChange={(e) => setDeclineNote(e.target.value)} />
          </FormField>
          {declineError ? (
            <p role="alert" className="text-xs font-medium text-rose-700">
              {declineError}
            </p>
          ) : null}
          {declineMutation.isError ? (
            <ErrorState title="Decline failed" error={declineMutation.error} />
          ) : null}
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="outline" onClick={() => setDeclineOpen(false)}>
              Back
            </Button>
            <Button type="submit" isLoading={declineMutation.isPending}>
              Confirm decline
            </Button>
          </div>
        </form>
      </Modal>

      <Modal open={cancelOpen} onClose={() => setCancelOpen(false)} title="Cancel order" size="sm">
        <form onSubmit={submitCancel} className="space-y-3">
          <FormField label="Cancellation reason" required>
            <Select
              value={cancelReason}
              onChange={(e) => setCancelReason(e.target.value as CancelReason)}
            >
              {CANCEL_REASONS.map((r) => (
                <option key={r.value} value={r.value}>
                  {r.label}
                </option>
              ))}
            </Select>
          </FormField>
          <FormField label="Note" required={cancelReason === 'OTHER'}>
            <Textarea value={cancelNote} onChange={(e) => setCancelNote(e.target.value)} />
          </FormField>
          {cancelError ? (
            <p role="alert" className="text-xs font-medium text-rose-700">
              {cancelError}
            </p>
          ) : null}
          {cancelMutation.isError ? (
            <ErrorState title="Cancel failed" error={cancelMutation.error} />
          ) : null}
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="outline" onClick={() => setCancelOpen(false)}>
              Back
            </Button>
            <Button type="submit" variant="danger" isLoading={cancelMutation.isPending}>
              Confirm cancellation
            </Button>
          </div>
        </form>
      </Modal>

      <Modal
        open={cancelReqOpen}
        onClose={() => setCancelReqOpen(false)}
        title="Flag customer cancellation request"
        size="sm"
      >
        <form onSubmit={submitCancelRequest} className="space-y-3">
          <FormField label="Request note" required>
            <Textarea value={cancelReqNote} onChange={(e) => setCancelReqNote(e.target.value)} />
          </FormField>
          {cancelReqError ? (
            <p role="alert" className="text-xs font-medium text-rose-700">
              {cancelReqError}
            </p>
          ) : null}
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="outline" onClick={() => setCancelReqOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" isLoading={cancelRequestMutation.isPending}>
              Flag request
            </Button>
          </div>
        </form>
      </Modal>
    </div>
  )
}
