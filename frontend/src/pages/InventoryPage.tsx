import { useMemo, useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { fetchLocations } from '../api/business'
import {
  createAdjustment,
  fetchInventoryPolicy,
  fetchLocationTotals,
  fetchMovements,
  fetchStock,
  saveInventoryPolicy,
} from '../api/inventory'
import type { AdjustmentReason, StockMovementRow, StockRow } from '../api/models'
import { formatQuantity } from '../lib/format'
import { canSeeCosts, filterLocationsByPermission, hasLocationPermission } from '../lib/scopes'
import { adjustmentFormSchema, policyFormSchema } from '../lib/validation'
import { useSession } from '../session/sessionContext'
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
  StatusPill,
  Textarea,
  useToast,
  type DataTableColumn,
} from '../components/ui'

/**
 * Inventory overview scoped to permitted locations with stock totals,
 * low-stock filtering, stock adjustments, reorder policies, and movement history.
 */
export default function InventoryPage() {
  const { user, can } = useSession()
  const queryClient = useQueryClient()

  const [locationId, setLocationId] = useState('')
  const [search, setSearch] = useState('')
  const [appliedSearch, setAppliedSearch] = useState('')
  const [lowStockOnly, setLowStockOnly] = useState(false)
  const [includeInactive, setIncludeInactive] = useState(false)

  const [adjustOpen, setAdjustOpen] = useState(false)
  const [policyRow, setPolicyRow] = useState<StockRow | null>(null)
  const [movementsTarget, setMovementsTarget] = useState<{
    locationId: string
    variantId?: string
    label: string
  } | null>(null)

  const locationsQuery = useQuery({ queryKey: ['locations'], queryFn: fetchLocations })
  const scopedLocations = useMemo(
    () => filterLocationsByPermission(user, 'INVENTORY_VIEW', locationsQuery.data ?? []),
    [user, locationsQuery.data],
  )
  const adjustableLocations = useMemo(
    () => filterLocationsByPermission(user, 'INVENTORY_ADJUST', locationsQuery.data ?? []),
    [user, locationsQuery.data],
  )

  const stockQuery = useQuery({
    queryKey: ['inventory', 'stock', locationId, appliedSearch, lowStockOnly, includeInactive],
    queryFn: () =>
      fetchStock({
        locationId: locationId || undefined,
        q: appliedSearch || undefined,
        lowStockOnly,
        includeInactive,
      }),
  })

  const totalsQuery = useQuery({
    queryKey: ['inventory', 'totals'],
    queryFn: fetchLocationTotals,
  })

  const stockRows = stockQuery.data ?? []
  const totals = totalsQuery.data ?? []
  const showCosts = canSeeCosts(user) || stockRows.some((r) => r.valueAtCost !== undefined)

  const locationName = (id: string) =>
    scopedLocations.find((l) => l.id === id)?.name ??
    locationsQuery.data?.find((l) => l.id === id)?.name ??
    id

  const columns: DataTableColumn<StockRow>[] = [
    {
      key: 'sku',
      header: 'SKU',
      sortable: true,
      sortValue: (r) => r.sku,
      render: (r) => (
        <div>
          <span className="font-mono text-xs font-semibold text-slate-900">{r.sku}</span>
          {r.productName ? (
            <span className="block text-xs text-slate-500">
              {r.productName}
              {[r.color, r.size].filter(Boolean).length > 0
                ? ` (${[r.color, r.size].filter(Boolean).join(' / ')})`
                : ''}
            </span>
          ) : null}
        </div>
      ),
    },
    ...(!locationId
      ? [
          {
            key: 'location',
            header: 'Location',
            render: (r: StockRow) => (
              <span className="text-xs text-slate-600">{locationName(r.locationId)}</span>
            ),
          },
        ]
      : []),
    {
      key: 'onHand',
      header: 'On hand',
      align: 'right',
      sortable: true,
      sortValue: (r) => r.onHand,
      render: (r) => <span className="tabular-nums font-medium">{formatQuantity(r.onHand)}</span>,
    },
    {
      key: 'reserved',
      header: 'Reserved',
      align: 'right',
      render: (r) => (
        <span className="tabular-nums text-slate-600">{formatQuantity(r.reserved)}</span>
      ),
    },
    {
      key: 'available',
      header: 'Available',
      align: 'right',
      sortable: true,
      sortValue: (r) => r.available,
      render: (r) => (
        <span className="tabular-nums font-semibold text-slate-900">
          {formatQuantity(r.available)}
        </span>
      ),
    },
    {
      key: 'inInspection',
      header: 'In inspection',
      align: 'right',
      render: (r) => (
        <span className="tabular-nums text-slate-600">{formatQuantity(r.inInspection)}</span>
      ),
    },
    {
      key: 'reorderPoint',
      header: 'Reorder pt',
      align: 'right',
      render: (r) => (
        <div className="inline-flex items-center gap-1.5">
          <span className="tabular-nums text-slate-600">{formatQuantity(r.reorderPoint)}</span>
          {r.isLowStock ? <StatusPill status="LOW_STOCK" /> : null}
        </div>
      ),
    },
    ...(showCosts
      ? [
          {
            key: 'valueAtCost',
            header: 'Value at cost',
            align: 'right' as const,
            render: (r: StockRow) => <MoneyText amount={r.valueAtCost} />,
          },
        ]
      : []),
    {
      key: 'actions',
      header: 'Actions',
      render: (r) => (
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            onClick={() =>
              setMovementsTarget({
                locationId: r.locationId,
                variantId: r.variantId,
                label: `${r.sku} at ${locationName(r.locationId)}`,
              })
            }
            className="text-xs font-medium text-merqo-700 hover:underline"
          >
            Movements
          </button>
          {hasLocationPermission(user, 'INVENTORY_ADJUST', r.locationId) ? (
            <button
              type="button"
              onClick={() => setPolicyRow(r)}
              className="text-xs font-medium text-slate-600 hover:underline"
            >
              Reorder policy
            </button>
          ) : null}
        </div>
      ),
    },
  ]

  return (
    <div className="space-y-6">
      <PageHeader
        title="Inventory"
        description="On-hand balances, reorder thresholds, adjustments, and movement ledger."
        actions={
          <div className="flex flex-wrap items-center gap-2">
            {can('STOCK_COUNT') ? (
              <Link
                to="/inventory/counts"
                className="inline-flex h-9 items-center rounded-md border border-slate-300 bg-white px-4 text-sm font-medium text-slate-700 shadow-xs hover:bg-slate-50"
              >
                Stock counts
              </Link>
            ) : null}
            <Button
              variant="outline"
              onClick={() =>
                setMovementsTarget({
                  locationId,
                  label: locationId ? locationName(locationId) : 'All permitted locations',
                })
              }
            >
              Movement history
            </Button>
            {adjustableLocations.length > 0 ? (
              <Button onClick={() => setAdjustOpen(true)}>Adjust stock</Button>
            ) : null}
          </div>
        }
      />

      {totals.length > 0 ? (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {totals.map((total) => (
            <div
              key={total.locationId}
              className="rounded-xl border border-slate-200 bg-white p-4 shadow-xs"
            >
              <p className="text-xs font-medium text-slate-500">{locationName(total.locationId)}</p>
              <p className="mt-1 text-lg font-semibold text-slate-900 tabular-nums">
                {formatQuantity(total.units)} units
              </p>
              {total.valueAtCost !== undefined ? (
                <p className="mt-0.5 text-xs text-slate-600">
                  Value:{' '}
                  <MoneyText amount={total.valueAtCost} className="font-medium text-slate-800" />
                </p>
              ) : null}
            </div>
          ))}
        </div>
      ) : null}

      <div className="flex flex-wrap items-end gap-3 rounded-xl border border-slate-200 bg-white p-4 shadow-xs">
        <FormField label="Location" className="min-w-[200px]">
          <Select value={locationId} onChange={(e) => setLocationId(e.target.value)}>
            <option value="">All permitted locations</option>
            {scopedLocations.map((loc) => (
              <option key={loc.id} value={loc.id}>
                {loc.name} ({loc.code})
              </option>
            ))}
          </Select>
        </FormField>

        <form
          className="flex flex-1 flex-wrap items-end gap-2"
          onSubmit={(e) => {
            e.preventDefault()
            setAppliedSearch(search.trim())
          }}
        >
          <FormField label="Search SKU or product" className="min-w-[200px] flex-1">
            <Input
              type="search"
              value={search}
              placeholder="SKU or product name"
              onChange={(e) => setSearch(e.target.value)}
            />
          </FormField>
          <Button type="submit" variant="outline">
            Filter
          </Button>
        </form>

        <label className="inline-flex h-9 items-center gap-2 text-sm text-slate-700">
          <input
            type="checkbox"
            checked={lowStockOnly}
            onChange={(e) => setLowStockOnly(e.target.checked)}
            className="h-4 w-4 rounded border-slate-300 accent-merqo-700"
          />
          Low stock only
        </label>

        <label className="inline-flex h-9 items-center gap-2 text-sm text-slate-700">
          <input
            type="checkbox"
            checked={includeInactive}
            onChange={(e) => setIncludeInactive(e.target.checked)}
            className="h-4 w-4 rounded border-slate-300 accent-merqo-700"
          />
          Include inactive
        </label>
      </div>

      {stockQuery.error ? (
        <ErrorState
          title="Could not load inventory"
          error={stockQuery.error}
          onRetry={() => stockQuery.refetch()}
        />
      ) : (
        <DataTable
          columns={columns}
          rows={stockRows}
          getRowKey={(r) => `${r.locationId}-${r.variantId}`}
          isLoading={stockQuery.isPending}
          emptyTitle="No stock rows match"
          emptyDescription="Adjust filters or choose a different location."
        />
      )}

      {adjustOpen ? (
        <AdjustStockModal
          locations={adjustableLocations}
          defaultLocationId={locationId || adjustableLocations[0]?.id || ''}
          stockRows={stockRows}
          onClose={() => setAdjustOpen(false)}
          onSaved={() => {
            queryClient.invalidateQueries({ queryKey: ['inventory'] })
          }}
        />
      ) : null}

      {policyRow ? (
        <PolicyModal
          row={policyRow}
          onClose={() => setPolicyRow(null)}
          onSaved={() => queryClient.invalidateQueries({ queryKey: ['inventory', 'stock'] })}
        />
      ) : null}

      <MovementHistoryDrawer target={movementsTarget} onClose={() => setMovementsTarget(null)} />
    </div>
  )
}

function AdjustStockModal({
  locations,
  defaultLocationId,
  stockRows,
  onClose,
  onSaved,
}: {
  locations: ReturnType<typeof filterLocationsByPermission>
  defaultLocationId: string
  stockRows: StockRow[]
  onClose: () => void
  onSaved: () => void
}) {
  const { toast } = useToast()
  const [locationId, setLocationId] = useState(defaultLocationId)
  const [reason, setReason] = useState<AdjustmentReason>('DAMAGED')
  const [note, setNote] = useState('')
  const [lines, setLines] = useState<Array<{ variantId: string; quantityDelta: string }>>([
    { variantId: '', quantityDelta: '' },
  ])
  const [formError, setFormError] = useState<string | null>(null)

  const variantOptions = useMemo(() => {
    const map = new Map<string, string>()
    for (const row of stockRows) {
      if (!locationId || row.locationId === locationId) {
        map.set(row.variantId, `${row.sku}${row.productName ? ` - ${row.productName}` : ''}`)
      }
    }
    return Array.from(map.entries())
  }, [stockRows, locationId])

  const mutation = useMutation({
    mutationFn: () =>
      createAdjustment({
        locationId,
        reason,
        note: note.trim() || null,
        lines: lines.map((l) => ({
          variantId: l.variantId,
          quantityDelta: Number(l.quantityDelta),
        })),
      }),
    onSuccess: (created) => {
      toast({ title: `Adjustment ${created.number} recorded`, tone: 'success' })
      onSaved()
      onClose()
    },
  })

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const parsed = adjustmentFormSchema.safeParse({
      locationId,
      reason,
      note,
      lines: lines.map((l) => ({
        variantId: l.variantId,
        quantityDelta: Number(l.quantityDelta),
      })),
    })
    if (!parsed.success) {
      setFormError(parsed.error.issues[0]?.message ?? 'Invalid adjustment.')
      return
    }
    setFormError(null)
    mutation.mutate()
  }

  return (
    <Modal open onClose={onClose} size="lg" title="Adjust stock">
      <form onSubmit={handleSubmit} className="space-y-4">
        <div className="grid gap-3 sm:grid-cols-2">
          <FormField label="Location" required>
            <Select value={locationId} onChange={(e) => setLocationId(e.target.value)}>
              {locations.map((loc) => (
                <option key={loc.id} value={loc.id}>
                  {loc.name} ({loc.code})
                </option>
              ))}
            </Select>
          </FormField>
          <FormField label="Reason" required>
            <Select value={reason} onChange={(e) => setReason(e.target.value as AdjustmentReason)}>
              <option value="DAMAGED">Damaged</option>
              <option value="LOST">Lost</option>
              <option value="FOUND">Found</option>
              <option value="DATA_CORRECTION">Data correction</option>
              <option value="OTHER">Other (note required)</option>
            </Select>
          </FormField>
        </div>

        <FormField
          label={reason === 'OTHER' ? 'Note (required for OTHER)' : 'Note (optional)'}
          required={reason === 'OTHER'}
        >
          <Textarea rows={2} value={note} onChange={(e) => setNote(e.target.value)} />
        </FormField>

        <div className="space-y-2">
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold tracking-wide text-slate-600 uppercase">
              Adjustment lines
            </span>
            <Button
              type="button"
              size="sm"
              variant="outline"
              onClick={() => setLines((prev) => [...prev, { variantId: '', quantityDelta: '' }])}
            >
              Add line
            </Button>
          </div>
          {lines.map((line, index) => (
            <div key={index} className="grid gap-2 sm:grid-cols-[1fr_140px_auto]">
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
                {variantOptions.map(([id, label]) => (
                  <option key={id} value={id}>
                    {label}
                  </option>
                ))}
              </Select>
              <Input
                type="number"
                step="1"
                placeholder="Delta (+/-)"
                aria-label={`Quantity delta for line ${index + 1}`}
                value={line.quantityDelta}
                onChange={(e) =>
                  setLines((prev) =>
                    prev.map((item, idx) =>
                      idx === index ? { ...item, quantityDelta: e.target.value } : item,
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

        {formError ? (
          <p role="alert" className="text-sm font-medium text-rose-700">
            {formError}
          </p>
        ) : null}
        {mutation.isError ? (
          <ErrorState title="Could not save adjustment" error={mutation.error} />
        ) : null}

        <div className="flex justify-end gap-2 pt-2">
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" isLoading={mutation.isPending} loadingText="Applying...">
            Apply adjustment
          </Button>
        </div>
      </form>
    </Modal>
  )
}

function PolicyModal({
  row,
  onClose,
  onSaved,
}: {
  row: StockRow
  onClose: () => void
  onSaved: () => void
}) {
  const { toast } = useToast()
  const policyQuery = useQuery({
    queryKey: ['inventory', 'policy', row.locationId, row.variantId],
    queryFn: () => fetchInventoryPolicy(row.locationId, row.variantId),
  })

  const [reorderPoint, setReorderPoint] = useState(String(row.reorderPoint))
  const [targetStock, setTargetStock] = useState('0')
  const [onlineSafetyStock, setOnlineSafetyStock] = useState('0')
  const [initializedFor, setInitializedFor] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  if (policyQuery.data && initializedFor !== `${row.locationId}-${row.variantId}`) {
    setInitializedFor(`${row.locationId}-${row.variantId}`)
    if (policyQuery.data.reorderPoint !== undefined) {
      setReorderPoint(String(policyQuery.data.reorderPoint))
    }
    if (policyQuery.data.targetStock !== undefined) {
      setTargetStock(String(policyQuery.data.targetStock))
    }
    if (policyQuery.data.onlineSafetyStock !== undefined) {
      setOnlineSafetyStock(String(policyQuery.data.onlineSafetyStock))
    }
  }

  const mutation = useMutation({
    mutationFn: () =>
      saveInventoryPolicy({
        locationId: row.locationId,
        variantId: row.variantId,
        reorderPoint: Number(reorderPoint),
        targetStock: Number(targetStock),
        onlineSafetyStock: Number(onlineSafetyStock),
      }),
    onSuccess: () => {
      toast({ title: `Reorder policy saved for ${row.sku}`, tone: 'success' })
      onSaved()
      onClose()
    },
  })

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const parsed = policyFormSchema.safeParse({
      reorderPoint: Number(reorderPoint),
      targetStock: Number(targetStock),
      onlineSafetyStock: Number(onlineSafetyStock),
    })
    if (!parsed.success) {
      setError(parsed.error.issues[0]?.message ?? 'Invalid policy values.')
      return
    }
    setError(null)
    mutation.mutate()
  }

  return (
    <Modal open onClose={onClose} title={`Reorder policy · ${row.sku}`}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <div className="grid gap-3 sm:grid-cols-3">
          <FormField label="Reorder point" required>
            <Input
              type="number"
              min="0"
              step="1"
              value={reorderPoint}
              onChange={(e) => setReorderPoint(e.target.value)}
            />
          </FormField>
          <FormField label="Target stock" required>
            <Input
              type="number"
              min="0"
              step="1"
              value={targetStock}
              onChange={(e) => setTargetStock(e.target.value)}
            />
          </FormField>
          <FormField label="Online safety stock" required>
            <Input
              type="number"
              min="0"
              step="1"
              value={onlineSafetyStock}
              onChange={(e) => setOnlineSafetyStock(e.target.value)}
            />
          </FormField>
        </div>

        {error ? (
          <p role="alert" className="text-sm font-medium text-rose-700">
            {error}
          </p>
        ) : null}
        {mutation.isError ? (
          <ErrorState title="Could not save policy" error={mutation.error} />
        ) : null}

        <div className="flex justify-end gap-2 pt-2">
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" isLoading={mutation.isPending} loadingText="Saving...">
            Save policy
          </Button>
        </div>
      </form>
    </Modal>
  )
}

function MovementHistoryDrawer({
  target,
  onClose,
}: {
  target: { locationId: string; variantId?: string; label: string } | null
  onClose: () => void
}) {
  const [page, setPage] = useState(0)

  const query = useQuery({
    queryKey: ['inventory', 'movements', target?.locationId, target?.variantId, page],
    queryFn: () =>
      fetchMovements({
        locationId: target?.locationId || undefined,
        variantId: target?.variantId,
        page,
        size: 20,
      }),
    enabled: Boolean(target),
  })

  const rows: StockMovementRow[] = query.data?.items ?? []

  return (
    <Drawer
      open={Boolean(target)}
      onClose={onClose}
      title="Stock movements"
      description={target?.label}
    >
      {query.error ? (
        <ErrorState title="Could not load movements" error={query.error} />
      ) : (
        <DataTable
          columns={[
            {
              key: 'when',
              header: 'When',
              render: (m) => (
                <span className="text-xs text-slate-500">
                  <DateText value={m.createdAt} withTime />
                </span>
              ),
            },
            {
              key: 'type',
              header: 'Type',
              render: (m) => <Badge tone="neutral">{m.movementType}</Badge>,
            },
            {
              key: 'delta',
              header: 'Change',
              align: 'right',
              render: (m) => (
                <span
                  className={`font-mono text-xs font-semibold tabular-nums ${
                    m.quantityDelta >= 0 ? 'text-emerald-700' : 'text-rose-700'
                  }`}
                >
                  {m.quantityDelta > 0 ? `+${m.quantityDelta}` : m.quantityDelta}
                </span>
              ),
            },
            {
              key: 'after',
              header: 'After',
              align: 'right',
              render: (m) => (
                <span className="tabular-nums text-xs text-slate-700">
                  {formatQuantity(m.onHandAfter)}
                </span>
              ),
            },
          ]}
          rows={rows}
          getRowKey={(m) => m.id}
          isLoading={query.isPending}
          emptyTitle="No movements recorded"
          pagination={
            query.data
              ? {
                  page: query.data.page,
                  totalPages: query.data.totalPages,
                  totalItems: query.data.totalItems,
                  pageSize: query.data.size,
                  onPageChange: setPage,
                }
              : undefined
          }
        />
      )}
    </Drawer>
  )
}
