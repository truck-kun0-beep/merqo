import { useMemo, useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { fetchLocations } from '../api/business'
import { fetchCategories } from '../api/catalog'
import {
  applyStockCount,
  cancelStockCount,
  fetchStockCount,
  fetchStockCounts,
  saveStockCountLine,
  startStockCount,
  submitStockCount,
} from '../api/inventory'
import type { StockCountDetail, StockCountLineRow, StockCountRow } from '../api/models'
import { formatQuantity } from '../lib/format'
import { filterLocationsByPermission, hasLocationPermission } from '../lib/scopes'
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
  Select,
  Skeleton,
  StatusPill,
  useToast,
  type DataTableColumn,
} from '../components/ui'

/**
 * Stock counts screen: start counts (optionally by category), perform blind count
 * entry while IN_PROGRESS (no system quantities in DOM), submit, review variances, apply, or cancel.
 */
export default function StockCountsPage() {
  const { user } = useSession()
  const queryClient = useQueryClient()
  const [locationFilter, setLocationFilter] = useState('')
  const [activeCountId, setActiveCountId] = useState<string | null>(null)
  const [startOpen, setStartOpen] = useState(false)

  const locationsQuery = useQuery({ queryKey: ['locations'], queryFn: fetchLocations })
  const countsQuery = useQuery({
    queryKey: ['inventory', 'counts', locationFilter],
    queryFn: () => fetchStockCounts(locationFilter || undefined),
  })

  const locations = useMemo(() => locationsQuery.data ?? [], [locationsQuery.data])
  const countableLocations = useMemo(
    () => filterLocationsByPermission(user, 'STOCK_COUNT', locations),
    [user, locations],
  )

  const locationName = (id: string) => locations.find((l) => l.id === id)?.name ?? id

  if (activeCountId) {
    return (
      <StockCountDetailView
        countId={activeCountId}
        locationName={locationName}
        onBack={() => {
          setActiveCountId(null)
          queryClient.invalidateQueries({ queryKey: ['inventory', 'counts'] })
        }}
      />
    )
  }

  const columns: DataTableColumn<StockCountRow>[] = [
    {
      key: 'number',
      header: 'Count',
      render: (c) => (
        <button
          type="button"
          onClick={() => setActiveCountId(c.id)}
          className="font-mono text-xs font-semibold text-merqo-700 hover:underline"
        >
          {c.number}
        </button>
      ),
    },
    {
      key: 'location',
      header: 'Location',
      render: (c) => <span className="text-slate-700">{locationName(c.locationId)}</span>,
    },
    {
      key: 'status',
      header: 'Status',
      render: (c) => (
        <div className="flex items-center gap-1.5">
          <StatusPill status={c.status} />
          {c.isFlagged ? <Badge tone="danger">Flagged</Badge> : null}
        </div>
      ),
    },
    {
      key: 'createdAt',
      header: 'Started',
      render: (c) => (
        <span className="text-xs text-slate-500">
          <DateText value={c.createdAt} withTime />
        </span>
      ),
    },
    {
      key: 'actions',
      header: 'Actions',
      render: (c) => (
        <Button variant="outline" size="sm" onClick={() => setActiveCountId(c.id)}>
          {c.status === 'IN_PROGRESS' ? 'Enter counts' : 'Review'}
        </Button>
      ),
    },
  ]

  return (
    <div className="space-y-6">
      <PageHeader
        breadcrumbs={[{ label: 'Inventory', to: '/inventory' }, { label: 'Stock counts' }]}
        title="Stock counts"
        description="Blind shelf counts and variance review."
        actions={
          countableLocations.length > 0 ? (
            <Button onClick={() => setStartOpen(true)}>Start stock count</Button>
          ) : undefined
        }
      />

      <div className="flex flex-wrap items-center gap-3">
        <Select
          aria-label="Filter by location"
          value={locationFilter}
          onChange={(e) => setLocationFilter(e.target.value)}
          className="w-60"
        >
          <option value="">All locations</option>
          {locations.map((l) => (
            <option key={l.id} value={l.id}>
              {l.name} ({l.code})
            </option>
          ))}
        </Select>
      </div>

      {countsQuery.error ? (
        <ErrorState
          title="Could not load stock counts"
          error={countsQuery.error}
          onRetry={() => countsQuery.refetch()}
        />
      ) : (
        <DataTable
          columns={columns}
          rows={countsQuery.data ?? []}
          getRowKey={(c) => c.id}
          isLoading={countsQuery.isPending}
          emptyTitle="No stock counts"
          emptyDescription="Start a count for a location to verify physical stock on shelves."
        />
      )}

      {startOpen ? (
        <StartCountModal
          locations={countableLocations}
          onClose={() => setStartOpen(false)}
          onStarted={(created) => {
            queryClient.invalidateQueries({ queryKey: ['inventory', 'counts'] })
            setActiveCountId(created.id)
          }}
        />
      ) : null}
    </div>
  )
}

function StartCountModal({
  locations,
  onClose,
  onStarted,
}: {
  locations: ReturnType<typeof filterLocationsByPermission>
  onClose: () => void
  onStarted: (count: StockCountRow) => void
}) {
  const [locationId, setLocationId] = useState(locations[0]?.id ?? '')
  const [categoryId, setCategoryId] = useState('')
  const categoriesQuery = useQuery({
    queryKey: ['catalog', 'categories', 'active'],
    queryFn: () => fetchCategories(true),
  })

  const mutation = useMutation({
    mutationFn: () =>
      startStockCount({
        locationId,
        categoryId: categoryId || null,
      }),
    onSuccess: (created) => {
      onStarted(created)
      onClose()
    },
  })

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    mutation.mutate()
  }

  return (
    <Modal open onClose={onClose} title="Start stock count">
      <form onSubmit={handleSubmit} className="space-y-4">
        <FormField label="Location" required>
          <Select value={locationId} onChange={(e) => setLocationId(e.target.value)}>
            {locations.map((loc) => (
              <option key={loc.id} value={loc.id}>
                {loc.name} ({loc.code})
              </option>
            ))}
          </Select>
        </FormField>

        <FormField
          label="Category (optional)"
          hint="Leave blank to count every active stocked variant at this location."
        >
          <Select value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
            <option value="">All categories</option>
            {(categoriesQuery.data ?? []).map((cat) => (
              <option key={cat.id} value={cat.id}>
                {cat.name}
              </option>
            ))}
          </Select>
        </FormField>

        {mutation.isError ? (
          <ErrorState title="Could not start count" error={mutation.error} />
        ) : null}

        <div className="flex justify-end gap-2 pt-2">
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" disabled={!locationId} isLoading={mutation.isPending}>
            Start blind count
          </Button>
        </div>
      </form>
    </Modal>
  )
}

export function StockCountDetailView({
  countId,
  locationName,
  onBack,
}: {
  countId: string
  locationName: (id: string) => string
  onBack: () => void
}) {
  const { user } = useSession()
  const queryClient = useQueryClient()
  const { toast } = useToast()

  const [draftCounts, setDraftCounts] = useState<Record<string, string>>({})
  const [confirmAction, setConfirmAction] = useState<'submit' | 'apply' | 'cancel' | null>(null)

  const query = useQuery({
    queryKey: ['inventory', 'count', countId],
    queryFn: () => fetchStockCount(countId),
  })

  const saveLineMutation = useMutation({
    mutationFn: ({ variantId, countedQuantity }: { variantId: string; countedQuantity: number }) =>
      saveStockCountLine(countId, { variantId, countedQuantity }),
    onSuccess: (updated: StockCountDetail) => {
      queryClient.setQueryData(['inventory', 'count', countId], updated)
      toast({ title: 'Count line saved', tone: 'success' })
    },
  })

  const submitMutation = useMutation({
    mutationFn: () => submitStockCount(countId),
    onSuccess: () => {
      setConfirmAction(null)
      queryClient.invalidateQueries({ queryKey: ['inventory', 'count', countId] })
      toast({ title: 'Count submitted for variance review', tone: 'success' })
    },
  })

  const applyMutation = useMutation({
    mutationFn: () => applyStockCount(countId),
    onSuccess: () => {
      setConfirmAction(null)
      queryClient.invalidateQueries({ queryKey: ['inventory', 'count', countId] })
      queryClient.invalidateQueries({ queryKey: ['inventory', 'stock'] })
      toast({ title: 'Stock count variances applied', tone: 'success' })
    },
  })

  const cancelMutation = useMutation({
    mutationFn: () => cancelStockCount(countId),
    onSuccess: () => {
      setConfirmAction(null)
      queryClient.invalidateQueries({ queryKey: ['inventory', 'count', countId] })
      toast({ title: 'Stock count cancelled', tone: 'info' })
    },
  })

  if (query.isPending) {
    return <Skeleton className="h-64 w-full rounded-xl" />
  }

  if (query.error) {
    return (
      <div className="space-y-4">
        <Button variant="outline" onClick={onBack}>
          Back to counts
        </Button>
        <ErrorState title="Could not load stock count" error={query.error} />
      </div>
    )
  }

  const count = query.data
  if (!count) return null

  const isInProgress = count.status === 'IN_PROGRESS'
  const isSubmitted = count.status === 'SUBMITTED'
  const canCount = hasLocationPermission(user, 'STOCK_COUNT', count.locationId)
  const canApply = hasLocationPermission(user, 'INVENTORY_ADJUST', count.locationId)

  // Strictly blind while IN_PROGRESS: never render systemQuantityAtStart or variance!
  const blindColumns: DataTableColumn<StockCountLineRow>[] = [
    {
      key: 'sku',
      header: 'SKU',
      render: (line) => (
        <span className="font-mono text-xs font-semibold text-slate-900">
          {line.sku ?? line.variantId}
        </span>
      ),
    },
    {
      key: 'counted',
      header: 'Counted quantity',
      render: (line) => {
        const value =
          draftCounts[line.variantId] ??
          (line.countedQuantity !== null ? String(line.countedQuantity) : '')
        const parsed = Number(value)
        const isValid = value !== '' && Number.isInteger(parsed) && parsed >= 0
        return (
          <div className="flex items-center gap-2">
            <Input
              type="number"
              min="0"
              step="1"
              aria-label={`Counted quantity for ${line.sku ?? line.variantId}`}
              value={value}
              disabled={!canCount}
              onChange={(e) =>
                setDraftCounts((prev) => ({ ...prev, [line.variantId]: e.target.value }))
              }
              className="w-28"
            />
            {canCount ? (
              <Button
                size="sm"
                variant="outline"
                disabled={!isValid}
                isLoading={
                  saveLineMutation.isPending &&
                  saveLineMutation.variables?.variantId === line.variantId
                }
                onClick={() =>
                  saveLineMutation.mutate({
                    variantId: line.variantId,
                    countedQuantity: parsed,
                  })
                }
              >
                Save
              </Button>
            ) : null}
          </div>
        )
      },
    },
  ]

  const reviewColumns: DataTableColumn<StockCountLineRow>[] = [
    {
      key: 'sku',
      header: 'SKU',
      render: (line) => (
        <span className="font-mono text-xs font-semibold text-slate-900">
          {line.sku ?? line.variantId}
        </span>
      ),
    },
    {
      key: 'system',
      header: 'System at start',
      align: 'right',
      render: (line) => (
        <span className="tabular-nums text-slate-700">
          {formatQuantity(line.systemQuantityAtStart)}
        </span>
      ),
    },
    {
      key: 'counted',
      header: 'Counted',
      align: 'right',
      render: (line) => (
        <span className="tabular-nums font-medium text-slate-900">
          {line.countedQuantity === null ? '-' : formatQuantity(line.countedQuantity)}
        </span>
      ),
    },
    {
      key: 'variance',
      header: 'Variance',
      align: 'right',
      render: (line) => {
        const v = line.variance ?? 0
        return (
          <span
            className={`font-mono text-xs font-semibold tabular-nums ${
              v > 0 ? 'text-emerald-700' : v < 0 ? 'text-rose-700' : 'text-slate-500'
            }`}
          >
            {v > 0 ? `+${v}` : v}
          </span>
        )
      },
    },
  ]

  const actionError =
    saveLineMutation.error ?? submitMutation.error ?? applyMutation.error ?? cancelMutation.error

  return (
    <div className="space-y-6">
      <PageHeader
        title={`Stock count ${count.number}`}
        description={`${locationName(count.locationId)} · Started ${
          count.createdAt ? new Date(count.createdAt).toLocaleDateString('en-GB') : ''
        }`}
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <StatusPill status={count.status} />
            <Button variant="outline" onClick={onBack}>
              All counts
            </Button>
            {isInProgress && canCount ? (
              <>
                <Button variant="danger" onClick={() => setConfirmAction('cancel')}>
                  Cancel count
                </Button>
                <Button onClick={() => setConfirmAction('submit')}>Submit count</Button>
              </>
            ) : null}
            {isSubmitted && canApply ? (
              <Button onClick={() => setConfirmAction('apply')}>Apply variances</Button>
            ) : null}
          </div>
        }
      />

      {isInProgress ? (
        <div
          role="note"
          className="rounded-xl border border-blue-200 bg-blue-50 px-4 py-3 text-xs text-blue-900"
        >
          Blind count active: system quantities remain hidden until the count is submitted.
        </div>
      ) : null}

      {actionError ? <ErrorState title="Action failed" error={actionError} /> : null}

      <DataTable
        columns={isInProgress ? blindColumns : reviewColumns}
        rows={count.lines}
        getRowKey={(l) => l.variantId}
        emptyTitle="No lines in this count"
      />

      <ConfirmDialog
        open={confirmAction === 'submit'}
        onClose={() => setConfirmAction(null)}
        onConfirm={() => submitMutation.mutate()}
        title="Submit stock count?"
        confirmLabel="Submit count"
        isConfirming={submitMutation.isPending}
      >
        <p>
          Submitting reveals system quantities and variances. You will not be able to edit lines
          afterward.
        </p>
      </ConfirmDialog>

      <ConfirmDialog
        open={confirmAction === 'apply'}
        onClose={() => setConfirmAction(null)}
        onConfirm={() => applyMutation.mutate()}
        title="Apply variances to stock?"
        confirmLabel="Apply variances"
        isConfirming={applyMutation.isPending}
      >
        <p>This will adjust stock balances to reconcile the counted variances.</p>
      </ConfirmDialog>

      <ConfirmDialog
        open={confirmAction === 'cancel'}
        onClose={() => setConfirmAction(null)}
        onConfirm={() => cancelMutation.mutate()}
        title="Cancel stock count?"
        confirmLabel="Cancel count"
        confirmVariant="danger"
        isConfirming={cancelMutation.isPending}
      >
        <p>Cancel {count.number}? No stock changes will be applied.</p>
      </ConfirmDialog>
    </div>
  )
}
