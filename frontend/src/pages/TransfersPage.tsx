import { useMemo, useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router-dom'
import { fetchLocations } from '../api/business'
import { fetchVariants } from '../api/catalog'
import { fetchTransfers, requestTransfer } from '../api/transfers'
import type { TransferRow, TransferStatus } from '../api/models'
import { filterLocationsByPermission } from '../lib/scopes'
import { transferRequestSchema } from '../lib/validation'
import { useSession } from '../session/sessionContext'
import {
  Button,
  DataTable,
  DateText,
  ErrorState,
  FormField,
  Input,
  Modal,
  PageHeader,
  Select,
  StatusPill,
  Textarea,
  useToast,
  type DataTableColumn,
} from '../components/ui'

const STATUS_FILTERS: Array<{ value: string; label: string }> = [
  { value: '', label: 'All' },
  { value: 'REQUESTED', label: 'Requested' },
  { value: 'APPROVED', label: 'Approved' },
  { value: 'DISPATCHED', label: 'Dispatched' },
  { value: 'PARTIALLY_RECEIVED', label: 'Partially received' },
  { value: 'RECEIVED', label: 'Received' },
  { value: 'CLOSED_WITH_SHORTAGE', label: 'Closed with shortage' },
  { value: 'REJECTED', label: 'Rejected' },
  { value: 'CANCELLED', label: 'Cancelled' },
]

/**
 * Stock transfers list with status filter and request modal (never showing source stock quantities).
 */
export default function TransfersPage() {
  const { user } = useSession()
  const navigate = useNavigate()
  const [statusFilter, setStatusFilter] = useState('')
  const [requestOpen, setRequestOpen] = useState(false)

  const transfersQuery = useQuery({
    queryKey: ['transfers', statusFilter],
    queryFn: () => fetchTransfers(statusFilter || undefined),
  })

  const locationsQuery = useQuery({ queryKey: ['locations'], queryFn: fetchLocations })
  const locations = useMemo(() => locationsQuery.data ?? [], [locationsQuery.data])
  const requestableDestinations = useMemo(
    () => filterLocationsByPermission(user, 'TRANSFER_REQUEST', locations),
    [user, locations],
  )

  const locationName = (id: string) => locations.find((l) => l.id === id)?.name ?? id

  const columns: DataTableColumn<TransferRow>[] = [
    {
      key: 'number',
      header: 'Transfer',
      render: (t) => (
        <Link
          to={`/transfers/${t.id}`}
          className="font-mono text-xs font-semibold text-merqo-700 hover:underline"
        >
          {t.number}
        </Link>
      ),
    },
    {
      key: 'route',
      header: 'Route',
      render: (t) => (
        <span className="text-sm text-slate-800">
          {locationName(t.sourceLocationId)} → {locationName(t.destinationLocationId)}
        </span>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      render: (t) => <StatusPill status={t.status as TransferStatus} />,
    },
    {
      key: 'requestedAt',
      header: 'Requested',
      render: (t) => (
        <span className="text-xs text-slate-500">
          <DateText value={t.requestedAt} withTime />
        </span>
      ),
    },
    {
      key: 'actions',
      header: 'Actions',
      render: (t) => (
        <Link
          to={`/transfers/${t.id}`}
          className="text-xs font-semibold text-merqo-700 hover:underline"
        >
          Open
        </Link>
      ),
    },
  ]

  return (
    <div className="space-y-6">
      <PageHeader
        title="Stock transfers"
        description="Request, approve, dispatch, and receive stock across locations."
        actions={
          requestableDestinations.length > 0 ? (
            <Button onClick={() => setRequestOpen(true)}>Request transfer</Button>
          ) : undefined
        }
      />

      <div className="flex flex-wrap items-center gap-2">
        <Select
          aria-label="Filter by transfer status"
          value={statusFilter}
          onChange={(e) => setStatusFilter(e.target.value)}
          className="w-56"
        >
          {STATUS_FILTERS.map((f) => (
            <option key={f.value} value={f.value}>
              {f.label}
            </option>
          ))}
        </Select>
      </div>

      {transfersQuery.error ? (
        <ErrorState
          title="Could not load transfers"
          error={transfersQuery.error}
          onRetry={() => transfersQuery.refetch()}
        />
      ) : (
        <DataTable
          columns={columns}
          rows={transfersQuery.data ?? []}
          getRowKey={(t) => t.id}
          isLoading={transfersQuery.isPending}
          emptyTitle="No transfers found"
          emptyDescription="Request a transfer to move stock between locations."
        />
      )}

      {requestOpen ? (
        <RequestTransferModal
          allLocations={locations}
          destinationLocations={requestableDestinations}
          onClose={() => setRequestOpen(false)}
          onCreated={(created) => navigate(`/transfers/${created.id}`)}
        />
      ) : null}
    </div>
  )
}

function RequestTransferModal({
  allLocations,
  destinationLocations,
  onClose,
  onCreated,
}: {
  allLocations: Array<{ id: string; name: string; code: string; isActive: boolean }>
  destinationLocations: Array<{ id: string; name: string; code: string; isActive: boolean }>
  onClose: () => void
  onCreated: (transfer: TransferRow) => void
}) {
  const queryClient = useQueryClient()
  const { toast } = useToast()

  const activeLocations = allLocations.filter((l) => l.isActive)
  const activeDestinations = destinationLocations.filter((l) => l.isActive)

  const [destinationLocationId, setDestinationLocationId] = useState(
    activeDestinations[0]?.id ?? '',
  )
  const [sourceLocationId, setSourceLocationId] = useState(
    activeLocations.find((l) => l.id !== activeDestinations[0]?.id)?.id ?? '',
  )
  const [note, setNote] = useState('')
  const [lines, setLines] = useState<Array<{ variantId: string; quantity: string }>>([
    { variantId: '', quantity: '1' },
  ])
  const [formError, setFormError] = useState<string | null>(null)

  const variantsQuery = useQuery({
    queryKey: ['catalog', 'variants', 'transfer-request'],
    queryFn: () => fetchVariants({ activeOnly: true, size: 200 }),
  })
  const variants = variantsQuery.data?.items ?? []

  const mutation = useMutation({
    mutationFn: () =>
      requestTransfer({
        sourceLocationId,
        destinationLocationId,
        note: note.trim() || null,
        lines: lines.map((l) => ({ variantId: l.variantId, quantity: Number(l.quantity) })),
      }),
    onSuccess: (created) => {
      queryClient.invalidateQueries({ queryKey: ['transfers'] })
      toast({ title: `Transfer ${created.number} requested`, tone: 'success' })
      onClose()
      onCreated(created)
    },
  })

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const parsed = transferRequestSchema.safeParse({
      sourceLocationId,
      destinationLocationId,
      note,
      lines: lines.map((l) => ({ variantId: l.variantId, quantity: Number(l.quantity) })),
    })
    if (!parsed.success) {
      setFormError(parsed.error.issues[0]?.message ?? 'Invalid transfer request.')
      return
    }
    setFormError(null)
    mutation.mutate()
  }

  return (
    <Modal
      open
      onClose={onClose}
      size="lg"
      title="Request stock transfer"
      description="Request stock from a source location without viewing source stock balances."
    >
      <form onSubmit={handleSubmit} className="space-y-4">
        <div className="grid gap-3 sm:grid-cols-2">
          <FormField label="Source location" required>
            <Select
              value={sourceLocationId}
              onChange={(e) => setSourceLocationId(e.target.value)}
            >
              <option value="">Choose source</option>
              {activeLocations.map((loc) => (
                <option key={loc.id} value={loc.id}>
                  {loc.name} ({loc.code})
                </option>
              ))}
            </Select>
          </FormField>
          <FormField label="Destination location" required>
            <Select
              value={destinationLocationId}
              onChange={(e) => setDestinationLocationId(e.target.value)}
            >
              <option value="">Choose destination</option>
              {activeDestinations.map((loc) => (
                <option key={loc.id} value={loc.id}>
                  {loc.name} ({loc.code})
                </option>
              ))}
            </Select>
          </FormField>
        </div>

        <div className="space-y-2">
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold tracking-wide text-slate-600 uppercase">
              Requested items
            </span>
            <Button
              type="button"
              size="sm"
              variant="outline"
              onClick={() => setLines((prev) => [...prev, { variantId: '', quantity: '1' }])}
            >
              Add line
            </Button>
          </div>
          {lines.map((line, index) => (
            <div key={index} className="grid gap-2 sm:grid-cols-[1fr_120px_auto]">
              <Select
                aria-label={`Variant for line ${index + 1}`}
                value={line.variantId}
                onChange={(e) =>
                  setLines((prev) =>
                    prev.map((item, idx) =>
                      idx === index ? { ...item, variantId: e.target.value } : item,
                    ),
                  )
                }
              >
                <option value="">Choose variant</option>
                {variants.map((v) => (
                  <option key={v.id} value={v.id}>
                    {v.sku} {[v.color, v.size].filter(Boolean).join(' ')}
                  </option>
                ))}
              </Select>
              <Input
                type="number"
                min="1"
                step="1"
                aria-label={`Quantity for line ${index + 1}`}
                value={line.quantity}
                onChange={(e) =>
                  setLines((prev) =>
                    prev.map((item, idx) =>
                      idx === index ? { ...item, quantity: e.target.value } : item,
                    ),
                  )
                }
              />
              {lines.length > 1 ? (
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  className="text-rose-700"
                  onClick={() => setLines((prev) => prev.filter((_, idx) => idx !== index))}
                >
                  Remove
                </Button>
              ) : (
                <span />
              )}
            </div>
          ))}
        </div>

        <FormField label="Note (optional)">
          <Textarea rows={2} value={note} onChange={(e) => setNote(e.target.value)} />
        </FormField>

        {formError ? (
          <p role="alert" className="text-sm font-medium text-rose-700">
            {formError}
          </p>
        ) : null}
        {mutation.isError ? (
          <ErrorState title="Could not request transfer" error={mutation.error} />
        ) : null}

        <div className="flex justify-end gap-2 pt-2">
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" isLoading={mutation.isPending} loadingText="Submitting...">
            Submit request
          </Button>
        </div>
      </form>
    </Modal>
  )
}
