import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useParams } from 'react-router-dom'
import { fetchLocations } from '../api/business'
import {
  approveTransfer,
  cancelTransfer,
  closeTransferWithShortage,
  dispatchTransfer,
  fetchTransfer,
  receiveTransfer,
  rejectTransfer,
} from '../api/transfers'
import type { TransferLineRow, TransferStatus } from '../api/models'
import { ApiError } from '../api/types'
import { formatQuantity } from '../lib/format'
import { hasLocationPermission } from '../lib/scopes'
import { useSession } from '../session/sessionContext'
import {
  Badge,
  Button,
  ConfirmDialog,
  DataTable,
  DateText,
  ErrorState,
  FormField,
  Input,
  Modal,
  PageHeader,
  Skeleton,
  StatusPill,
  Textarea,
  useToast,
  type DataTableColumn,
} from '../components/ui'

const TIMELINE_STEPS: Array<{ key: TransferStatus; label: string }> = [
  { key: 'REQUESTED', label: 'Requested' },
  { key: 'APPROVED', label: 'Approved' },
  { key: 'DISPATCHED', label: 'Dispatched' },
  { key: 'RECEIVED', label: 'Received' },
]

/**
 * Transfer detail page with status timeline, permission- and status-gated transitions,
 * per-line approval/dispatch/receive dialogs, over-receive prevention, and INVALID_TRANSITION handling.
 */
export default function TransferDetailPage() {
  const { id } = useParams<{ id: string }>()
  const { user } = useSession()
  const queryClient = useQueryClient()
  const { toast } = useToast()

  const [modalMode, setModalMode] = useState<
    null | 'approve' | 'reject' | 'dispatch' | 'receive' | 'close' | 'cancel'
  >(null)
  const [transitionWarning, setTransitionWarning] = useState<string | null>(null)

  const transferQuery = useQuery({
    queryKey: ['transfer', id],
    queryFn: () => fetchTransfer(id ?? ''),
    enabled: Boolean(id),
  })
  const locationsQuery = useQuery({ queryKey: ['locations'], queryFn: fetchLocations })

  function handleTransitionError(caught: unknown) {
    if (caught instanceof ApiError && caught.errorCode === 'INVALID_TRANSITION') {
      setTransitionWarning(
        'This transfer already moved to another step. The latest status has been refreshed.',
      )
      setModalMode(null)
      queryClient.invalidateQueries({ queryKey: ['transfer', id] })
    }
  }

  const cancelMutation = useMutation({
    mutationFn: () => cancelTransfer(id!),
    onSuccess: () => {
      setModalMode(null)
      queryClient.invalidateQueries({ queryKey: ['transfer', id] })
      toast({ title: 'Transfer cancelled', tone: 'info' })
    },
    onError: handleTransitionError,
  })

  if (transferQuery.isPending) {
    return <Skeleton className="h-72 w-full rounded-xl" />
  }

  if (transferQuery.error) {
    return (
      <ErrorState
        title="Could not load transfer"
        error={transferQuery.error}
        onRetry={() => transferQuery.refetch()}
      />
    )
  }

  const transfer = transferQuery.data
  if (!transfer) return null

  const locations = locationsQuery.data ?? []
  const locationName = (locId: string) => locations.find((l) => l.id === locId)?.name ?? locId

  const canApprove = hasLocationPermission(user, 'TRANSFER_APPROVE', transfer.sourceLocationId)
  const canDispatch = hasLocationPermission(user, 'TRANSFER_DISPATCH', transfer.sourceLocationId)
  const canReceive = hasLocationPermission(user, 'TRANSFER_RECEIVE', transfer.destinationLocationId)
  const canRequestAtDest = hasLocationPermission(
    user,
    'TRANSFER_REQUEST',
    transfer.destinationLocationId,
  )

  const showApprove = transfer.status === 'REQUESTED' && canApprove
  const showReject = transfer.status === 'REQUESTED' && canApprove
  const showDispatch = transfer.status === 'APPROVED' && canDispatch
  const showReceive =
    (transfer.status === 'DISPATCHED' || transfer.status === 'PARTIALLY_RECEIVED') && canReceive
  const showCloseWithShortage =
    (transfer.status === 'DISPATCHED' || transfer.status === 'PARTIALLY_RECEIVED') && canApprove
  const showCancel =
    (transfer.status === 'REQUESTED' || transfer.status === 'APPROVED') &&
    (canApprove || canRequestAtDest)

  const lineColumns: DataTableColumn<TransferLineRow>[] = [
    {
      key: 'sku',
      header: 'Item',
      render: (line) => (
        <div>
          <span className="font-mono text-xs font-semibold text-slate-900">
            {line.sku ?? line.variantId}
          </span>
          {line.productName ? (
            <span className="block text-xs text-slate-500">{line.productName}</span>
          ) : null}
        </div>
      ),
    },
    {
      key: 'requested',
      header: 'Requested',
      align: 'right',
      render: (line) => (
        <span className="tabular-nums">{formatQuantity(line.requestedQuantity)}</span>
      ),
    },
    {
      key: 'approved',
      header: 'Approved',
      align: 'right',
      render: (line) => (
        <span className="tabular-nums">
          {line.approvedQuantity === null ? '-' : formatQuantity(line.approvedQuantity)}
        </span>
      ),
    },
    {
      key: 'dispatched',
      header: 'Dispatched',
      align: 'right',
      render: (line) => (
        <span className="tabular-nums">{formatQuantity(line.dispatchedQuantity)}</span>
      ),
    },
    {
      key: 'received',
      header: 'Received',
      align: 'right',
      render: (line) => (
        <span className="tabular-nums">{formatQuantity(line.receivedQuantity)}</span>
      ),
    },
    {
      key: 'inTransit',
      header: 'In transit',
      align: 'right',
      render: (line) => (
        <span className="tabular-nums font-medium">{formatQuantity(line.inTransit)}</span>
      ),
    },
    {
      key: 'shortage',
      header: 'Shortage',
      align: 'right',
      render: (line) => (
        <span className="tabular-nums text-rose-700">{formatQuantity(line.shortageQuantity)}</span>
      ),
    },
  ]

  const reachedIndex = (() => {
    if (transfer.status === 'REQUESTED') return 0
    if (transfer.status === 'APPROVED') return 1
    if (transfer.status === 'DISPATCHED' || transfer.status === 'PARTIALLY_RECEIVED') return 2
    if (transfer.status === 'RECEIVED' || transfer.status === 'CLOSED_WITH_SHORTAGE') return 3
    return -1
  })()

  return (
    <div className="space-y-6">
      <PageHeader
        breadcrumbs={[{ label: 'Transfers', to: '/transfers' }, { label: transfer.number }]}
        title={`Transfer ${transfer.number}`}
        description={`${locationName(transfer.sourceLocationId)} → ${locationName(
          transfer.destinationLocationId,
        )}`}
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <StatusPill status={transfer.status} />
            {showApprove ? <Button onClick={() => setModalMode('approve')}>Approve</Button> : null}
            {showReject ? (
              <Button variant="danger" onClick={() => setModalMode('reject')}>
                Reject
              </Button>
            ) : null}
            {showDispatch ? (
              <Button onClick={() => setModalMode('dispatch')}>Dispatch</Button>
            ) : null}
            {showReceive ? <Button onClick={() => setModalMode('receive')}>Receive</Button> : null}
            {showCloseWithShortage ? (
              <Button variant="outline" onClick={() => setModalMode('close')}>
                Close with shortage
              </Button>
            ) : null}
            {showCancel ? (
              <Button
                variant="ghost"
                className="text-rose-700"
                onClick={() => setModalMode('cancel')}
              >
                Cancel
              </Button>
            ) : null}
          </div>
        }
      />

      {transitionWarning ? (
        <div
          role="alert"
          className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900"
        >
          {transitionWarning}
        </div>
      ) : null}

      <section
        aria-label="Transfer timeline"
        className="rounded-xl border border-slate-200 bg-white p-5 shadow-xs"
      >
        <ol className="grid gap-3 sm:grid-cols-4">
          {TIMELINE_STEPS.map((step, index) => {
            const reached = reachedIndex >= index
            return (
              <li
                key={step.key}
                className={`rounded-lg border px-3.5 py-2.5 text-xs ${
                  reached
                    ? 'border-merqo-300 bg-merqo-50/70 text-merqo-950'
                    : 'border-slate-200 bg-slate-50 text-slate-400'
                }`}
              >
                <span className="font-semibold">
                  {index + 1}. {step.label}
                </span>
              </li>
            )
          })}
        </ol>
        {transfer.status === 'REJECTED' ||
        transfer.status === 'CANCELLED' ||
        transfer.status === 'CLOSED_WITH_SHORTAGE' ||
        transfer.status === 'PARTIALLY_RECEIVED' ? (
          <div className="mt-3">
            <StatusPill status={transfer.status} />
          </div>
        ) : null}
      </section>

      <DataTable
        columns={lineColumns}
        rows={transfer.lines}
        getRowKey={(l) => l.id}
        emptyTitle="No lines"
      />

      <section className="rounded-xl border border-slate-200 bg-white p-5 shadow-xs">
        <h2 className="text-sm font-semibold text-slate-900">Status history</h2>
        <ul className="mt-3 divide-y divide-slate-100 text-xs">
          {transfer.history.map((entry, idx) => (
            <li key={idx} className="flex flex-wrap items-center justify-between gap-2 py-2.5">
              <div className="flex flex-wrap items-center gap-2">
                <Badge tone="neutral">{entry.toStatus}</Badge>
                <span className="font-medium text-slate-800">
                  {entry.actorUsername ?? 'System'}
                </span>
                {entry.note ? <span className="text-slate-600">— {entry.note}</span> : null}
              </div>
              <span className="text-slate-400">
                <DateText value={entry.createdAt} withTime />
              </span>
            </li>
          ))}
        </ul>
      </section>

      {modalMode === 'approve' ? (
        <LineQuantityModal
          title="Approve transfer"
          submitLabel="Approve"
          lines={transfer.lines.map((l) => ({
            variantId: l.variantId,
            max: l.requestedQuantity,
            initial: l.requestedQuantity,
          }))}
          onClose={() => setModalMode(null)}
          onSubmit={(lines) => approveTransfer(transfer.id, lines)}
          onTransitionError={handleTransitionError}
          onSuccess={() => {
            setModalMode(null)
            queryClient.invalidateQueries({ queryKey: ['transfer', id] })
            toast({ title: 'Transfer approved', tone: 'success' })
          }}
        />
      ) : null}

      {modalMode === 'dispatch' ? (
        <LineQuantityModal
          title="Dispatch transfer"
          submitLabel="Dispatch"
          lines={transfer.lines.map((l) => ({
            variantId: l.variantId,
            max: l.approvedQuantity ?? l.requestedQuantity,
            initial: l.approvedQuantity ?? l.requestedQuantity,
          }))}
          onClose={() => setModalMode(null)}
          onSubmit={(lines) => dispatchTransfer(transfer.id, lines)}
          onTransitionError={handleTransitionError}
          onSuccess={() => {
            setModalMode(null)
            queryClient.invalidateQueries({ queryKey: ['transfer', id] })
            toast({ title: 'Transfer dispatched', tone: 'success' })
          }}
        />
      ) : null}

      {modalMode === 'receive' ? (
        <LineQuantityModal
          title="Receive transfer"
          description="Enter quantities arriving now. Over-receiving beyond outstanding quantity is blocked."
          submitLabel="Confirm receipt"
          requireAtLeastOnePositive
          lines={transfer.lines.map((l) => {
            const outstanding = Math.max(
              0,
              l.dispatchedQuantity - l.receivedQuantity - l.shortageQuantity,
            )
            return {
              variantId: l.variantId,
              max: outstanding,
              initial: outstanding,
            }
          })}
          onClose={() => setModalMode(null)}
          onSubmit={(lines) => receiveTransfer(transfer.id, lines)}
          onTransitionError={handleTransitionError}
          onSuccess={() => {
            setModalMode(null)
            queryClient.invalidateQueries({ queryKey: ['transfer', id] })
            toast({ title: 'Transfer received', tone: 'success' })
          }}
        />
      ) : null}

      {modalMode === 'reject' || modalMode === 'close' ? (
        <NoteActionModal
          title={modalMode === 'reject' ? 'Reject transfer' : 'Close transfer with shortage'}
          submitLabel={modalMode === 'reject' ? 'Reject' : 'Close with shortage'}
          requiredNote={modalMode === 'close'}
          onClose={() => setModalMode(null)}
          onSubmit={(note) =>
            modalMode === 'reject'
              ? rejectTransfer(transfer.id, note || undefined)
              : closeTransferWithShortage(transfer.id, note)
          }
          onTransitionError={handleTransitionError}
          onSuccess={() => {
            setModalMode(null)
            queryClient.invalidateQueries({ queryKey: ['transfer', id] })
            toast({
              title: modalMode === 'reject' ? 'Transfer rejected' : 'Transfer closed with shortage',
              tone: 'info',
            })
          }}
        />
      ) : null}

      <ConfirmDialog
        open={modalMode === 'cancel'}
        onClose={() => setModalMode(null)}
        onConfirm={() => cancelMutation.mutate()}
        title="Cancel transfer?"
        confirmLabel="Cancel transfer"
        confirmVariant="danger"
        isConfirming={cancelMutation.isPending}
      >
        <p>Cancel transfer {transfer.number} before dispatch?</p>
      </ConfirmDialog>
    </div>
  )
}

function LineQuantityModal({
  title,
  description,
  submitLabel,
  lines,
  requireAtLeastOnePositive = false,
  onClose,
  onSubmit,
  onTransitionError,
  onSuccess,
}: {
  title: string
  description?: string
  submitLabel: string
  lines: Array<{ variantId: string; max: number; initial: number }>
  requireAtLeastOnePositive?: boolean
  onClose: () => void
  onSubmit: (lines: Array<{ variantId: string; quantity: number }>) => Promise<unknown>
  onTransitionError: (error: unknown) => void
  onSuccess: () => void
}) {
  const [quantities, setQuantities] = useState<Record<string, string>>(() => {
    const map: Record<string, string> = {}
    for (const l of lines) {
      map[l.variantId] = String(l.initial)
    }
    return map
  })
  const [validationError, setValidationError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: () =>
      onSubmit(
        lines.map((l) => ({
          variantId: l.variantId,
          quantity: Number(quantities[l.variantId] ?? 0),
        })),
      ),
    onSuccess,
    onError: onTransitionError,
  })

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    let hasPositive = false
    for (const line of lines) {
      const val = Number(quantities[line.variantId])
      if (Number.isNaN(val) || !Number.isInteger(val) || val < 0) {
        setValidationError('Quantities must be whole numbers of 0 or more.')
        return
      }
      if (val > line.max) {
        setValidationError(`Quantity for ${line.variantId} cannot exceed ${line.max}.`)
        return
      }
      if (val > 0) hasPositive = true
    }
    if (requireAtLeastOnePositive && !hasPositive) {
      setValidationError('Enter a quantity greater than 0 on at least one line.')
      return
    }
    setValidationError(null)
    mutation.mutate()
  }

  return (
    <Modal open onClose={onClose} title={title} description={description}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <ul className="divide-y divide-slate-100 rounded-lg border border-slate-200">
          {lines.map((line) => (
            <li
              key={line.variantId}
              className="flex items-center justify-between gap-3 px-3.5 py-2.5"
            >
              <div>
                <span className="font-mono text-xs font-medium text-slate-900">
                  {line.variantId}
                </span>
                <span className="block text-xs text-slate-500">Max allowed: {line.max}</span>
              </div>
              <Input
                type="number"
                min="0"
                max={line.max}
                step="1"
                aria-label={`Quantity for ${line.variantId}`}
                value={quantities[line.variantId] ?? '0'}
                onChange={(e) =>
                  setQuantities((prev) => ({ ...prev, [line.variantId]: e.target.value }))
                }
                className="w-28"
              />
            </li>
          ))}
        </ul>

        {validationError ? (
          <p role="alert" className="text-sm font-medium text-rose-700">
            {validationError}
          </p>
        ) : null}
        {mutation.isError ? <ErrorState title="Action failed" error={mutation.error} /> : null}

        <div className="flex justify-end gap-2 pt-2">
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" isLoading={mutation.isPending} loadingText="Saving...">
            {submitLabel}
          </Button>
        </div>
      </form>
    </Modal>
  )
}

function NoteActionModal({
  title,
  submitLabel,
  requiredNote,
  onClose,
  onSubmit,
  onTransitionError,
  onSuccess,
}: {
  title: string
  submitLabel: string
  requiredNote: boolean
  onClose: () => void
  onSubmit: (note: string) => Promise<unknown>
  onTransitionError: (error: unknown) => void
  onSuccess: () => void
}) {
  const [note, setNote] = useState('')
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: () => onSubmit(note.trim()),
    onSuccess,
    onError: onTransitionError,
  })

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (requiredNote && note.trim().length === 0) {
      setError('A note is required to close a transfer with shortage.')
      return
    }
    setError(null)
    mutation.mutate()
  }

  return (
    <Modal open onClose={onClose} title={title}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <FormField
          label={requiredNote ? 'Shortage note' : 'Note (optional)'}
          required={requiredNote}
          error={error ?? undefined}
        >
          <Textarea
            rows={3}
            value={note}
            invalid={Boolean(error)}
            onChange={(e) => setNote(e.target.value)}
          />
        </FormField>

        {mutation.isError ? <ErrorState title="Action failed" error={mutation.error} /> : null}

        <div className="flex justify-end gap-2 pt-2">
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button
            type="submit"
            variant="danger"
            isLoading={mutation.isPending}
            loadingText="Saving..."
          >
            {submitLabel}
          </Button>
        </div>
      </form>
    </Modal>
  )
}
