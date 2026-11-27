import { useMemo, useState, type FormEvent } from 'react'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { fetchLocations } from '../api/business'
import { fetchInspections, resolveInspection } from '../api/pos'
import type { InspectionItem, InspectionOutcome } from '../api/models'
import { filterLocationsByPermission } from '../lib/scopes'
import { useSession } from '../session/sessionContext'
import {
  Badge,
  Button,
  ConfirmDialog,
  DataTable,
  ErrorState,
  FormField,
  Input,
  Modal,
  PageHeader,
  Select,
  useToast,
  type DataTableColumn,
} from '../components/ui'

/**
 * Returned-stock inspection queue (`/inventory/inspections`), gated by `INVENTORY_ADJUST`.
 * Lists items held in inspection and resolves quantities as Restock or Write off with confirmation.
 */
export default function InspectionsPage() {
  const { user } = useSession()
  const queryClient = useQueryClient()
  const { toast } = useToast()

  const locationsQuery = useQuery({ queryKey: ['locations'], queryFn: fetchLocations })
  const allowedLocations = useMemo(() => {
    const all = locationsQuery.data ?? []
    return filterLocationsByPermission(user, 'INVENTORY_ADJUST', all)
  }, [user, locationsQuery.data])

  const locationNameById = useMemo(() => {
    const map = new Map<string, string>()
    for (const loc of locationsQuery.data ?? []) {
      map.set(loc.id, `${loc.name} (${loc.code})`)
    }
    return map
  }, [locationsQuery.data])

  const [selectedLocationId, setSelectedLocationId] = useState('')
  const effectiveLocationId =
    selectedLocationId || (allowedLocations.length === 1 ? allowedLocations[0].id : '')

  const inspectionsQuery = useQuery({
    queryKey: ['inventory', 'inspections', effectiveLocationId || 'all'],
    queryFn: () => fetchInspections(effectiveLocationId || undefined),
    placeholderData: keepPreviousData,
  })

  const [resolvingItem, setResolvingItem] = useState<InspectionItem | null>(null)
  const [outcome, setOutcome] = useState<InspectionOutcome>('RESTOCK')
  const [quantity, setQuantity] = useState('1')
  const [note, setNote] = useState('')
  const [validationError, setValidationError] = useState<string | null>(null)
  const [confirmOpen, setConfirmOpen] = useState(false)

  const resolveMutation = useMutation({
    mutationFn: () =>
      resolveInspection({
        locationId: resolvingItem!.locationId,
        variantId: resolvingItem!.variantId,
        quantity: Number(quantity),
        outcome,
        note: note.trim() || undefined,
      }),
    onSuccess: (res) => {
      setConfirmOpen(false)
      setResolvingItem(null)
      setNote('')
      setValidationError(null)
      queryClient.invalidateQueries({ queryKey: ['inventory', 'inspections'] })
      queryClient.invalidateQueries({ queryKey: ['inventory', 'stock'] })
      toast({
        title:
          res.outcome === 'RESTOCK'
            ? `Restocked ${res.quantity} unit(s)`
            : `Wrote off ${res.quantity} unit(s)`,
        tone: 'success',
      })
    },
  })

  function openResolveModal(item: InspectionItem, defaultOutcome: InspectionOutcome) {
    setResolvingItem(item)
    setOutcome(defaultOutcome)
    setQuantity(String(item.inInspection))
    setNote('')
    setValidationError(null)
    setConfirmOpen(false)
    resolveMutation.reset()
  }

  function handleFormSubmit(event: FormEvent) {
    event.preventDefault()
    if (!resolvingItem) return
    const qty = Number(quantity)
    if (!Number.isInteger(qty) || qty < 1 || qty > resolvingItem.inInspection) {
      setValidationError(`Quantity must be between 1 and ${resolvingItem.inInspection}.`)
      return
    }
    setValidationError(null)
    setConfirmOpen(true)
  }

  const columns: DataTableColumn<InspectionItem>[] = [
    {
      key: 'item',
      header: 'Item',
      render: (item) => {
        const variantLabel = [item.color, item.size].filter(Boolean).join(' / ')
        return (
          <div>
            <p className="font-medium text-slate-900">{item.productName}</p>
            <p className="font-mono text-xs text-slate-500">
              {item.sku}
              {variantLabel ? ` · ${variantLabel}` : ''}
            </p>
          </div>
        )
      },
    },
    {
      key: 'location',
      header: 'Branch',
      render: (item) => (
        <span className="text-slate-700">
          {locationNameById.get(item.locationId) ?? item.locationId}
        </span>
      ),
    },
    {
      key: 'inInspection',
      header: 'In inspection',
      align: 'right',
      render: (item) => <Badge tone="warning">{item.inInspection}</Badge>,
    },
    {
      key: 'available',
      header: 'Available',
      align: 'right',
      render: (item) => <span className="tabular-nums text-slate-700">{item.available}</span>,
    },
    {
      key: 'onHand',
      header: 'On hand',
      align: 'right',
      render: (item) => <span className="tabular-nums text-slate-700">{item.onHand}</span>,
    },
    {
      key: 'actions',
      header: 'Actions',
      render: (item) => (
        <div className="flex flex-wrap gap-2">
          <Button
            size="sm"
            variant="outline"
            onClick={() => openResolveModal(item, 'RESTOCK')}
          >
            Restock
          </Button>
          <Button
            size="sm"
            variant="outline"
            className="text-rose-700 hover:bg-rose-50"
            onClick={() => openResolveModal(item, 'WRITE_OFF')}
          >
            Write off
          </Button>
        </div>
      ),
    },
  ]

  return (
    <div className="space-y-6">
      <PageHeader
        title="Inspections"
        description="Resolve returned items held for inspection by restocking sellable units or writing off damaged stock."
        actions={
          allowedLocations.length > 1 ? (
            <Select
              aria-label="Filter branch"
              value={selectedLocationId}
              onChange={(e) => setSelectedLocationId(e.target.value)}
              className="w-56"
            >
              <option value="">All accessible branches</option>
              {allowedLocations.map((loc) => (
                <option key={loc.id} value={loc.id}>
                  {loc.name} ({loc.code})
                </option>
              ))}
            </Select>
          ) : null
        }
      />

      {inspectionsQuery.error ? (
        <ErrorState
          title="Could not load inspections"
          error={inspectionsQuery.error}
          onRetry={() => inspectionsQuery.refetch()}
        />
      ) : (
        <DataTable
          columns={columns}
          rows={inspectionsQuery.data ?? []}
          getRowKey={(item) => `${item.locationId}:${item.variantId}`}
          isLoading={inspectionsQuery.isPending}
          emptyTitle="No items in inspection"
          emptyDescription="Returned items routed to inspection will appear here for resolution."
        />
      )}

      {resolvingItem ? (
        <Modal
          open
          onClose={() => {
            setResolvingItem(null)
            setConfirmOpen(false)
          }}
          size="md"
          title={`Resolve inspection · ${resolvingItem.sku}`}
          description={`${resolvingItem.productName} — ${resolvingItem.inInspection} unit(s) currently in inspection.`}
        >
          <form onSubmit={handleFormSubmit} className="space-y-4">
            <FormField label="Resolution outcome" required>
              <Select
                aria-label="Resolution outcome"
                value={outcome}
                onChange={(e) => setOutcome(e.target.value as InspectionOutcome)}
              >
                <option value="RESTOCK">Restock (move back to sellable stock)</option>
                <option value="WRITE_OFF">Write off (remove from inventory)</option>
              </Select>
            </FormField>

            <FormField
              label={`Quantity (max ${resolvingItem.inInspection})`}
              required
            >
              <Input
                type="number"
                min="1"
                max={resolvingItem.inInspection}
                step="1"
                value={quantity}
                onChange={(e) => setQuantity(e.target.value)}
              />
            </FormField>

            <FormField label="Note (optional)">
              <Input
                value={note}
                placeholder="Condition findings or disposal reason..."
                onChange={(e) => setNote(e.target.value)}
              />
            </FormField>

            {validationError ? (
              <p role="alert" className="text-sm font-medium text-rose-700">
                {validationError}
              </p>
            ) : null}

            {resolveMutation.isError ? (
              <ErrorState title="Resolution failed" error={resolveMutation.error} />
            ) : null}

            <div className="flex justify-end gap-2 pt-2">
              <Button variant="outline" onClick={() => setResolvingItem(null)}>
                Cancel
              </Button>
              <Button type="submit">Continue to confirm</Button>
            </div>
          </form>
        </Modal>
      ) : null}

      {resolvingItem ? (
        <ConfirmDialog
          open={confirmOpen}
          onClose={() => setConfirmOpen(false)}
          onConfirm={() => resolveMutation.mutate()}
          isConfirming={resolveMutation.isPending}
          confirmVariant={outcome === 'WRITE_OFF' ? 'danger' : 'primary'}
          confirmLabel={outcome === 'RESTOCK' ? 'Confirm restock' : 'Confirm write off'}
          title={
            outcome === 'RESTOCK'
              ? 'Confirm inspection restock?'
              : 'Confirm inspection write off?'
          }
        >
          <p>
            {outcome === 'RESTOCK'
              ? `Restock ${quantity} unit(s) of ${resolvingItem.sku} back into sellable stock?`
              : `Write off ${quantity} unit(s) of ${resolvingItem.sku}? This permanently removes those units from on-hand stock.`}
          </p>
        </ConfirmDialog>
      ) : null}
    </div>
  )
}
