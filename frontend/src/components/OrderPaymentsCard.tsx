import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { fetchAuthStatus } from '../api/auth'
import {
  approveRefund,
  checkOrderPaymentWithProvider,
  fetchClaimPrefill,
  fetchOrderPayments,
  payRefund,
  recordCodCollection,
  recordOrderPayment,
  rejectRefund,
  requestOrderRefund,
  verifyOrderPayment,
  type CourierPartner,
  type OnlinePaymentMethod,
  type OrderPaymentView,
  type PaymentDecision,
  type RefundPayoutMethod,
  type RefundReason,
  type RefundView,
} from '../api/payments'
import {
  Badge,
  Button,
  DateText,
  ErrorState,
  FormField,
  Input,
  Modal,
  MoneyText,
  Select,
  Skeleton,
  StatusPill,
  Textarea,
  useToast,
} from './ui'
import {
  codCollectionFormSchema,
  recordOrderPaymentSchema,
  refundPayFormSchema,
  refundRequestFormSchema,
  verifyPaymentFormSchema,
} from '../lib/validation'
import { useSession } from '../session/sessionContext'

const METHODS: OnlinePaymentMethod[] = ['BKASH', 'NAGAD', 'CARD', 'BANK']
const PAYOUT_METHODS: RefundPayoutMethod[] = ['BKASH', 'NAGAD', 'CARD', 'BANK', 'CASH']
const REFUND_REASONS: Array<{ value: RefundReason; label: string }> = [
  { value: 'ORDER_CANCELLED', label: 'Order cancelled' },
  { value: 'ORDER_DECLINED', label: 'Order declined' },
  { value: 'OVERPAYMENT', label: 'Overpayment' },
  { value: 'CUSTOMER_REQUEST', label: 'Customer request' },
  { value: 'OTHER', label: 'Other (note required)' },
]
const COURIERS: CourierPartner[] = ['STEADFAST', 'PATHAO']

export function OrderPaymentsCard({ orderId }: { orderId: string }) {
  const { can } = useSession()
  const queryClient = useQueryClient()
  const { toast } = useToast()

  const canSeePayments =
    can('PAYMENT_VIEW') ||
    can('PAYMENT_RECORD') ||
    can('PAYMENT_VERIFY') ||
    can('REFUND_REQUEST') ||
    can('REFUND_APPROVE') ||
    can('REFUND_PAY') ||
    can('COD_SETTLEMENT_MANAGE')

  const authStatusQuery = useQuery({
    queryKey: ['auth', 'status'],
    queryFn: fetchAuthStatus,
    staleTime: 60_000,
  })

  const paymentsQuery = useQuery({
    queryKey: ['orders', 'payments', orderId],
    queryFn: () => fetchOrderPayments(orderId),
    enabled: Boolean(orderId) && canSeePayments,
  })

  const [recordOpen, setRecordOpen] = useState(false)
  const [recMethod, setRecMethod] = useState<OnlinePaymentMethod>('BKASH')
  const [recAmount, setRecAmount] = useState('')
  const [recReference, setRecReference] = useState('')
  const [recNote, setRecNote] = useState('')
  const [recFromClaim, setRecFromClaim] = useState(false)
  const [recError, setRecError] = useState<string | null>(null)

  const [verifyTarget, setVerifyTarget] = useState<OrderPaymentView | null>(null)
  const [verifyDecision, setVerifyDecision] = useState<PaymentDecision>('VERIFIED')
  const [verifyConfirmedAmount, setVerifyConfirmedAmount] = useState('')
  const [verifyEvidence, setVerifyEvidence] = useState('')
  const [verifyError, setVerifyError] = useState<string | null>(null)

  const [refundReqOpen, setRefundReqOpen] = useState(false)
  const [refundAmount, setRefundAmount] = useState('')
  const [refundReason, setRefundReason] = useState<RefundReason>('ORDER_CANCELLED')
  const [refundNote, setRefundNote] = useState('')
  const [refundReqError, setRefundReqError] = useState<string | null>(null)

  const [decisionRefund, setDecisionRefund] = useState<{
    refund: RefundView
    mode: 'APPROVE' | 'REJECT'
  } | null>(null)
  const [decisionNote, setDecisionNote] = useState('')

  const [payTarget, setPayTarget] = useState<RefundView | null>(null)
  const [payoutMethod, setPayoutMethod] = useState<RefundPayoutMethod>('BKASH')
  const [payoutRef, setPayoutRef] = useState('')
  const [payoutNote, setPayoutNote] = useState('')
  const [payError, setPayError] = useState<string | null>(null)

  const [codOpen, setCodOpen] = useState(false)
  const [codCourier, setCodCourier] = useState<CourierPartner>('STEADFAST')
  const [codAmount, setCodAmount] = useState('')
  const [codDate, setCodDate] = useState(() => new Date().toISOString().slice(0, 10))
  const [codNote, setCodNote] = useState('')
  const [codError, setCodError] = useState<string | null>(null)

  function invalidateAll() {
    queryClient.invalidateQueries({ queryKey: ['orders', 'payments', orderId] })
    queryClient.invalidateQueries({ queryKey: ['orders', 'detail', orderId] })
    queryClient.invalidateQueries({ queryKey: ['payments'] })
    queryClient.invalidateQueries({ queryKey: ['refunds'] })
    queryClient.invalidateQueries({ queryKey: ['cod'] })
  }

  const recordMutation = useMutation({
    mutationFn: () =>
      recordOrderPayment(orderId, {
        method: recMethod,
        amount: recAmount.trim(),
        reference: recReference.trim().toUpperCase(),
        payerNote: recNote.trim() || null,
        fromClaim: recFromClaim,
      }),
    onSuccess: (created) => {
      setRecordOpen(false)
      setRecError(null)
      invalidateAll()
      toast({ title: `Payment ${created.paymentNumber} recorded`, tone: 'success' })
    },
  })

  const verifyMutation = useMutation({
    mutationFn: () =>
      verifyOrderPayment(verifyTarget!.id, {
        decision: verifyDecision,
        confirmedAmount: verifyDecision === 'VERIFIED' ? verifyConfirmedAmount.trim() : undefined,
        evidence: verifyEvidence.trim() || null,
      }),
    onSuccess: (updated) => {
      setVerifyTarget(null)
      setVerifyError(null)
      invalidateAll()
      toast({
        title: `Payment ${updated.paymentNumber} ${updated.status.toLowerCase()}`,
        tone: 'success',
      })
    },
  })

  const providerCheckMutation = useMutation({
    mutationFn: (paymentId: string) => checkOrderPaymentWithProvider(paymentId),
    onSuccess: (res) => {
      invalidateAll()
      toast({
        title: `Provider check: ${res.outcome}`,
        description: res.message,
        tone: res.outcome === 'CONFIRMED' ? 'success' : 'warning',
      })
    },
  })

  const refundReqMutation = useMutation({
    mutationFn: () =>
      requestOrderRefund(orderId, {
        amount: refundAmount.trim(),
        reason: refundReason.trim(),
        note: refundNote.trim() || null,
      }),
    onSuccess: (created) => {
      setRefundReqOpen(false)
      setRefundReqError(null)
      invalidateAll()
      toast({ title: `Refund ${created.refundNumber} requested`, tone: 'success' })
    },
  })

  const refundDecideMutation = useMutation({
    mutationFn: () =>
      decisionRefund!.mode === 'APPROVE'
        ? approveRefund(decisionRefund!.refund.id, decisionNote.trim() || null)
        : rejectRefund(decisionRefund!.refund.id, decisionNote.trim() || null),
    onSuccess: (updated) => {
      setDecisionRefund(null)
      setDecisionNote('')
      invalidateAll()
      toast({
        title: `Refund ${updated.refundNumber} ${updated.status.toLowerCase()}`,
        tone: 'success',
      })
    },
  })

  const refundPayMutation = useMutation({
    mutationFn: () =>
      payRefund(payTarget!.id, {
        payoutMethod,
        payoutReference: payoutMethod === 'CASH' ? null : payoutRef.trim().toUpperCase(),
        note: payoutNote.trim() || null,
      }),
    onSuccess: (updated) => {
      setPayTarget(null)
      setPayError(null)
      invalidateAll()
      toast({ title: `Refund ${updated.refundNumber} paid`, tone: 'success' })
    },
  })

  const codMutation = useMutation({
    mutationFn: () =>
      recordCodCollection(orderId, {
        courier: codCourier.trim(),
        amount: codAmount.trim(),
        collectedOn: codDate,
        note: codNote.trim() || null,
      }),
    onSuccess: () => {
      setCodOpen(false)
      setCodError(null)
      invalidateAll()
      toast({ title: 'COD collection recorded', tone: 'success' })
    },
  })

  if (!canSeePayments) {
    return null
  }

  if (paymentsQuery.isPending) {
    return <Skeleton className="h-44 w-full rounded-xl" />
  }

  if (paymentsQuery.error || !paymentsQuery.data) {
    return (
      <ErrorState
        title="Could not load order payments"
        error={paymentsQuery.error}
        onRetry={() => paymentsQuery.refetch()}
      />
    )
  }

  const data = paymentsQuery.data
  const pos = data.position
  const providerCheckEnabled = authStatusQuery.data?.providerCheckEnabled === true

  async function openRecordModal() {
    setRecError(null)
    setRecNote('')
    setRecFromClaim(false)
    recordMutation.reset()

    if (can('PAYMENT_RECORD')) {
      try {
        const prefill = await fetchClaimPrefill(orderId)
        if (prefill.hasClaim && Number(prefill.remainingAllowed) > 0) {
          setRecMethod((prefill.method as OnlinePaymentMethod) || 'BKASH')
          setRecAmount(prefill.claimedAmount)
          setRecReference(prefill.reference ?? '')
          setRecFromClaim(true)
        } else {
          setRecAmount(Number(pos.shortfall) > 0 ? pos.shortfall : '')
          setRecReference('')
        }
      } catch {
        setRecAmount(Number(pos.shortfall) > 0 ? pos.shortfall : '')
        setRecReference('')
      }
    }
    setRecordOpen(true)
  }

  function openVerifyModal(payment: OrderPaymentView) {
    setVerifyTarget(payment)
    setVerifyDecision('VERIFIED')
    setVerifyConfirmedAmount(payment.amount)
    setVerifyEvidence('')
    setVerifyError(null)
    verifyMutation.reset()
  }

  function submitRecordPayment(e: FormEvent) {
    e.preventDefault()
    const parsed = recordOrderPaymentSchema.safeParse({
      method: recMethod,
      amount: recAmount,
      reference: recReference,
      payerNote: recNote,
    })
    if (!parsed.success) {
      setRecError(parsed.error.issues[0]?.message ?? 'Invalid payment details.')
      return
    }
    setRecError(null)
    recordMutation.mutate()
  }

  function submitVerifyPayment(e: FormEvent) {
    e.preventDefault()
    const parsed = verifyPaymentFormSchema.safeParse({
      decision: verifyDecision,
      confirmedAmount: verifyConfirmedAmount,
      evidence: verifyEvidence,
    })
    if (!parsed.success) {
      setVerifyError(parsed.error.issues[0]?.message ?? 'Invalid verification details.')
      return
    }
    setVerifyError(null)
    verifyMutation.mutate()
  }

  function submitRefundRequest(e: FormEvent) {
    e.preventDefault()
    const parsed = refundRequestFormSchema.safeParse({
      amount: refundAmount,
      reason: refundReason,
      note: refundNote,
    })
    if (!parsed.success) {
      setRefundReqError(parsed.error.issues[0]?.message ?? 'Invalid refund details.')
      return
    }
    setRefundReqError(null)
    refundReqMutation.mutate()
  }

  function submitRefundPay(e: FormEvent) {
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
    refundPayMutation.mutate()
  }

  function submitCodCollection(e: FormEvent) {
    e.preventDefault()
    const parsed = codCollectionFormSchema.safeParse({
      courier: codCourier,
      amount: codAmount,
      collectedOn: codDate,
      note: codNote,
    })
    if (!parsed.success) {
      setCodError(parsed.error.issues[0]?.message ?? 'Invalid COD collection details.')
      return
    }
    setCodError(null)
    codMutation.mutate()
  }

  return (
    <section
      aria-labelledby="order-payments-heading"
      className="rounded-xl border border-slate-200 bg-white p-5 shadow-xs space-y-4"
    >
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-100 pb-3">
        <div className="flex flex-wrap items-center gap-2">
          <h2 id="order-payments-heading" className="text-sm font-semibold text-slate-900">
            Payments &amp; COD position
          </h2>
          {pos.readyForShipment ? (
            <Badge tone="success">Ready for shipment</Badge>
          ) : (
            <Badge tone="warning">Awaiting prepayment</Badge>
          )}
          {pos.refundDue ? <Badge tone="danger">Refund due</Badge> : null}
        </div>
        <div className="flex flex-wrap gap-2">
          {can('PAYMENT_RECORD') &&
          data.paymentMode !== 'COD' &&
          Number(pos.shortfall) > 0 &&
          data.orderStatus !== 'CANCELLED' &&
          data.orderStatus !== 'DECLINED' ? (
            <Button size="sm" onClick={openRecordModal}>
              Record payment
            </Button>
          ) : null}
          {can('REFUND_REQUEST') && Number(pos.refundable) > 0 ? (
            <Button
              size="sm"
              variant="outline"
              onClick={() => {
                setRefundAmount(pos.refundable)
                setRefundReason(
                  data.orderStatus === 'DECLINED' ? 'ORDER_DECLINED' : 'ORDER_CANCELLED',
                )
                setRefundNote('')
                setRefundReqError(null)
                refundReqMutation.reset()
                setRefundReqOpen(true)
              }}
            >
              Request refund
            </Button>
          ) : null}
          {can('COD_SETTLEMENT_MANAGE') && Number(pos.codUncollected) > 0 ? (
            <Button
              size="sm"
              variant="outline"
              onClick={() => {
                setCodAmount(pos.codUncollected)
                setCodNote('')
                setCodError(null)
                codMutation.reset()
                setCodOpen(true)
              }}
            >
              Record COD collection
            </Button>
          ) : null}
        </div>
      </div>

      <div className="grid grid-cols-2 gap-3 rounded-lg bg-slate-50 p-3 text-xs sm:grid-cols-3 lg:grid-cols-6">
        <div>
          <p className="text-slate-500">Required advance</p>
          <p className="font-semibold text-slate-900">
            <MoneyText amount={pos.requiredPrepayment} />
          </p>
        </div>
        <div>
          <p className="text-slate-500">Verified paid</p>
          <p className="font-semibold text-emerald-700">
            <MoneyText amount={pos.verifiedPaid} />
          </p>
        </div>
        <div>
          <p className="text-slate-500">Pending verify</p>
          <p className="font-semibold text-amber-700">
            <MoneyText amount={pos.pendingPaid} />
          </p>
        </div>
        <div>
          <p className="text-slate-500">Shortfall</p>
          <p className="font-semibold text-slate-900">
            <MoneyText amount={pos.shortfall} />
          </p>
        </div>
        <div>
          <p className="text-slate-500">Refundable</p>
          <p className="font-semibold text-slate-900">
            <MoneyText amount={pos.refundable} />
          </p>
        </div>
        <div>
          <p className="text-slate-500">COD uncollected</p>
          <p className="font-semibold text-slate-900">
            <MoneyText amount={pos.codUncollected} />
          </p>
        </div>
      </div>

      {data.payments.length > 0 ? (
        <div className="space-y-2">
          <h3 className="text-xs font-semibold tracking-wide text-slate-500 uppercase">
            Recorded payments
          </h3>
          <ul className="space-y-2 text-xs">
            {data.payments.map((p) => (
              <li
                key={p.id}
                className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-slate-200 p-3"
              >
                <div className="space-y-0.5">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-mono font-semibold text-slate-900">
                      {p.paymentNumber}
                    </span>
                    <StatusPill status={p.status} />
                    <Badge tone="neutral">{p.method}</Badge>
                    <MoneyText amount={p.amount} className="font-semibold text-slate-900" />
                    <span className="font-mono text-slate-600">Ref: {p.reference}</span>
                    {p.source === 'WEBSITE_CLAIM' ? (
                      <Badge tone="info">Website claim</Badge>
                    ) : null}
                  </div>
                  <p className="text-slate-500">
                    Recorded by {p.recordedByUsername} on <DateText value={p.recordedAt} withTime />
                    {p.payerNote ? ` · ${p.payerNote}` : ''}
                  </p>
                  {p.decision ? (
                    <p className="text-slate-600">
                      Decision: {p.decision.decision} ({p.decision.deciderKind.replaceAll('_', ' ')})
                      {p.decision.decidedByUsername ? ` by ${p.decision.decidedByUsername}` : ''}
                      {p.decision.evidence ? ` · ${p.decision.evidence}` : ''}
                    </p>
                  ) : null}
                </div>
                {p.status === 'PENDING_VERIFICATION' && can('PAYMENT_VERIFY') ? (
                  <div className="flex flex-wrap gap-2">
                    {providerCheckEnabled ? (
                      <Button
                        size="sm"
                        variant="outline"
                        isLoading={
                          providerCheckMutation.isPending &&
                          providerCheckMutation.variables === p.id
                        }
                        onClick={() => providerCheckMutation.mutate(p.id)}
                      >
                        Check with provider
                      </Button>
                    ) : null}
                    <Button size="sm" onClick={() => openVerifyModal(p)}>
                      Verify / Reject
                    </Button>
                  </div>
                ) : null}
              </li>
            ))}
          </ul>
        </div>
      ) : (
        <p className="text-xs text-slate-500">No payments recorded for this order yet.</p>
      )}

      {data.refunds.length > 0 ? (
        <div className="space-y-2 border-t border-slate-100 pt-3">
          <h3 className="text-xs font-semibold tracking-wide text-slate-500 uppercase">Refunds</h3>
          <ul className="space-y-2 text-xs">
            {data.refunds.map((r) => (
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
                    <MoneyText amount={r.amount} className="font-semibold text-slate-900" />
                    <span className="text-slate-700">{r.reason}</span>
                  </div>
                  <p className="mt-0.5 text-slate-500">
                    Requested by {r.requestedByUsername} · <DateText value={r.requestedAt} withTime />
                  </p>
                </div>
                <div className="flex flex-wrap gap-2">
                  {r.status === 'REQUESTED' && can('REFUND_APPROVE') ? (
                    <>
                      <Button
                        size="sm"
                        onClick={() => {
                          setDecisionRefund({ refund: r, mode: 'APPROVE' })
                          setDecisionNote('')
                        }}
                      >
                        Approve
                      </Button>
                      <Button
                        size="sm"
                        variant="outline"
                        className="text-rose-700"
                        onClick={() => {
                          setDecisionRefund({ refund: r, mode: 'REJECT' })
                          setDecisionNote('')
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
                        refundPayMutation.reset()
                      }}
                    >
                      Record payout
                    </Button>
                  ) : null}
                </div>
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      {data.codCollections.length > 0 ? (
        <div className="space-y-2 border-t border-slate-100 pt-3">
          <h3 className="text-xs font-semibold tracking-wide text-slate-500 uppercase">
            COD collections
          </h3>
          <ul className="space-y-1.5 text-xs">
            {data.codCollections.map((c) => (
              <li
                key={c.id}
                className="flex flex-wrap items-center justify-between gap-2 rounded-lg bg-slate-50 px-3 py-2"
              >
                <span>
                  <strong>{c.courier}</strong> · <MoneyText amount={c.amount} /> · Collected{' '}
                  <DateText value={c.collectedOn} />
                  {c.note ? ` · ${c.note}` : ''}
                </span>
                <span className="text-slate-500">By {c.recordedByUsername}</span>
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      <Modal
        open={recordOpen}
        onClose={() => setRecordOpen(false)}
        title="Record order payment"
        size="sm"
      >
        <form onSubmit={submitRecordPayment} className="space-y-3">
          {recFromClaim ? (
            <p className="rounded-lg bg-merqo-50 px-3 py-2 text-xs text-merqo-900">
              Prefilled from customer&apos;s website claim. Confirm details before saving.
            </p>
          ) : null}
          <FormField label="Payment method" required>
            <Select
              aria-label="Payment method"
              value={recMethod}
              onChange={(e) => setRecMethod(e.target.value as OnlinePaymentMethod)}
            >
              {METHODS.map((m) => (
                <option key={m} value={m}>
                  {m}
                </option>
              ))}
            </Select>
          </FormField>
          <FormField label="Amount (BDT)" required>
            <Input
              aria-label="Payment amount"
              type="number"
              step="0.01"
              value={recAmount}
              onChange={(e) => setRecAmount(e.target.value)}
            />
          </FormField>
          <FormField label="Transaction reference / TrxID" required>
            <Input
              aria-label="Payment reference"
              value={recReference}
              onChange={(e) => setRecReference(e.target.value)}
              placeholder="e.g. BK8812AB or LAST4"
            />
          </FormField>
          <FormField label="Payer note">
            <Input
              aria-label="Payer note"
              value={recNote}
              onChange={(e) => setRecNote(e.target.value)}
            />
          </FormField>
          {recError ? (
            <p role="alert" className="text-xs font-medium text-rose-700">
              {recError}
            </p>
          ) : null}
          {recordMutation.isError ? (
            <ErrorState title="Could not record payment" error={recordMutation.error} />
          ) : null}
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="outline" onClick={() => setRecordOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" isLoading={recordMutation.isPending}>
              Save payment
            </Button>
          </div>
        </form>
      </Modal>

      <Modal
        open={Boolean(verifyTarget)}
        onClose={() => setVerifyTarget(null)}
        title={`Review payment ${verifyTarget?.paymentNumber ?? ''}`}
        size="sm"
      >
        <form onSubmit={submitVerifyPayment} className="space-y-3">
          <FormField label="Decision" required>
            <Select
              aria-label="Verification decision"
              value={verifyDecision}
              onChange={(e) => setVerifyDecision(e.target.value as PaymentDecision)}
            >
              <option value="VERIFIED">Verify (confirm funds received)</option>
              <option value="REJECTED">Reject (release TrxID)</option>
            </Select>
          </FormField>
          {verifyDecision === 'VERIFIED' ? (
            <FormField label="Confirmed amount (BDT)" required>
              <Input
                aria-label="Confirmed amount"
                type="number"
                step="0.01"
                value={verifyConfirmedAmount}
                onChange={(e) => setVerifyConfirmedAmount(e.target.value)}
              />
            </FormField>
          ) : null}
          <FormField label="Evidence / note" required>
            <Textarea
              aria-label="Verification evidence"
              value={verifyEvidence}
              onChange={(e) => setVerifyEvidence(e.target.value)}
            />
          </FormField>
          {verifyError ? (
            <p role="alert" className="text-xs font-medium text-rose-700">
              {verifyError}
            </p>
          ) : null}
          {verifyMutation.isError ? (
            <ErrorState title="Verification failed" error={verifyMutation.error} />
          ) : null}
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="outline" onClick={() => setVerifyTarget(null)}>
              Cancel
            </Button>
            <Button type="submit" isLoading={verifyMutation.isPending}>
              Confirm {verifyDecision.toLowerCase()}
            </Button>
          </div>
        </form>
      </Modal>

      <Modal
        open={refundReqOpen}
        onClose={() => setRefundReqOpen(false)}
        title="Request customer refund"
        size="sm"
      >
        <form onSubmit={submitRefundRequest} className="space-y-3">
          <FormField label="Refund amount (BDT)" required>
            <Input
              aria-label="Refund amount"
              type="number"
              step="0.01"
              value={refundAmount}
              onChange={(e) => setRefundAmount(e.target.value)}
            />
          </FormField>
          <FormField label="Reason" required>
            <Select
              aria-label="Refund reason"
              value={refundReason}
              onChange={(e) => setRefundReason(e.target.value as RefundReason)}
            >
              {REFUND_REASONS.map((r) => (
                <option key={r.value} value={r.value}>
                  {r.label}
                </option>
              ))}
            </Select>
          </FormField>
          <FormField label="Note" required={refundReason === 'OTHER'}>
            <Textarea
              aria-label="Refund note"
              value={refundNote}
              onChange={(e) => setRefundNote(e.target.value)}
            />
          </FormField>
          {refundReqError ? (
            <p role="alert" className="text-xs font-medium text-rose-700">
              {refundReqError}
            </p>
          ) : null}
          {refundReqMutation.isError ? (
            <ErrorState title="Refund request failed" error={refundReqMutation.error} />
          ) : null}
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="outline" onClick={() => setRefundReqOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" isLoading={refundReqMutation.isPending}>
              Submit request
            </Button>
          </div>
        </form>
      </Modal>

      <Modal
        open={Boolean(decisionRefund)}
        onClose={() => setDecisionRefund(null)}
        title={`${decisionRefund?.mode === 'APPROVE' ? 'Approve' : 'Reject'} refund ${decisionRefund?.refund.refundNumber ?? ''}`}
        size="sm"
      >
        <form
          onSubmit={(e) => {
            e.preventDefault()
            refundDecideMutation.mutate()
          }}
          className="space-y-3"
        >
          <FormField label="Note (optional)">
            <Textarea
              aria-label="Refund decision note"
              value={decisionNote}
              onChange={(e) => setDecisionNote(e.target.value)}
            />
          </FormField>
          {refundDecideMutation.isError ? (
            <ErrorState title="Action failed" error={refundDecideMutation.error} />
          ) : null}
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="outline" onClick={() => setDecisionRefund(null)}>
              Back
            </Button>
            <Button type="submit" isLoading={refundDecideMutation.isPending}>
              Confirm
            </Button>
          </div>
        </form>
      </Modal>

      <Modal
        open={Boolean(payTarget)}
        onClose={() => setPayTarget(null)}
        title={`Pay refund ${payTarget?.refundNumber ?? ''}`}
        size="sm"
      >
        <form onSubmit={submitRefundPay} className="space-y-3">
          <FormField label="Payout method" required>
            <Select
              aria-label="Payout method"
              value={payoutMethod}
              onChange={(e) => setPayoutMethod(e.target.value as RefundPayoutMethod)}
            >
              {PAYOUT_METHODS.map((m) => (
                <option key={m} value={m}>
                  {m}
                </option>
              ))}
            </Select>
          </FormField>
          {payoutMethod !== 'CASH' ? (
            <FormField label="Payout reference / TrxID" required>
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
          {refundPayMutation.isError ? (
            <ErrorState title="Payout failed" error={refundPayMutation.error} />
          ) : null}
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="outline" onClick={() => setPayTarget(null)}>
              Cancel
            </Button>
            <Button type="submit" isLoading={refundPayMutation.isPending}>
              Confirm payout
            </Button>
          </div>
        </form>
      </Modal>

      <Modal
        open={codOpen}
        onClose={() => setCodOpen(false)}
        title="Record courier COD collection"
        size="sm"
      >
        <form onSubmit={submitCodCollection} className="space-y-3">
          <FormField label="Courier" required>
            <Select
              aria-label="COD courier"
              value={codCourier}
              onChange={(e) => setCodCourier(e.target.value as CourierPartner)}
            >
              {COURIERS.map((c) => (
                <option key={c} value={c}>
                  {c}
                </option>
              ))}
            </Select>
          </FormField>
          <FormField label="Collected amount (BDT)" required>
            <Input
              aria-label="COD collected amount"
              type="number"
              step="0.01"
              value={codAmount}
              onChange={(e) => setCodAmount(e.target.value)}
            />
          </FormField>
          <FormField label="Collected on" required>
            <Input
              aria-label="Collected date"
              type="date"
              value={codDate}
              onChange={(e) => setCodDate(e.target.value)}
            />
          </FormField>
          <FormField label="Note">
            <Input aria-label="COD note" value={codNote} onChange={(e) => setCodNote(e.target.value)} />
          </FormField>
          {codError ? (
            <p role="alert" className="text-xs font-medium text-rose-700">
              {codError}
            </p>
          ) : null}
          {codMutation.isError ? (
            <ErrorState title="Failed to record COD collection" error={codMutation.error} />
          ) : null}
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="outline" onClick={() => setCodOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" isLoading={codMutation.isPending}>
              Save COD collection
            </Button>
          </div>
        </form>
      </Modal>
    </section>
  )
}
