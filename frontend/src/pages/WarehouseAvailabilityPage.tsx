import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { fetchWarehouseAvailability, type WarehouseAvailabilityRow } from '../api/orders'
import {
  Badge,
  DataTable,
  ErrorState,
  Input,
  PageHeader,
  type DataTableColumn,
} from '../components/ui'

export default function WarehouseAvailabilityPage() {
  const [q, setQ] = useState('')

  const availabilityQuery = useQuery({
    queryKey: ['orders', 'availability', q],
    queryFn: () => fetchWarehouseAvailability({ q }),
  })

  const columns: DataTableColumn<WarehouseAvailabilityRow>[] = [
    {
      key: 'sku',
      header: 'SKU',
      render: (r) => (
        <div>
          <p className="font-mono font-semibold text-slate-900">{r.sku}</p>
          <p className="text-xs text-slate-500">
            {r.productName} {[r.color, r.size].filter(Boolean).join(' / ')}
          </p>
        </div>
      ),
    },
    {
      key: 'onHand',
      header: 'On hand',
      align: 'right',
      render: (r) => <span className="tabular-nums">{r.onHand}</span>,
    },
    {
      key: 'reserved',
      header: 'Hard reserved',
      align: 'right',
      render: (r) => <span className="tabular-nums text-amber-700">{r.reserved}</span>,
    },
    {
      key: 'softHolds',
      header: 'Soft holds',
      align: 'right',
      render: (r) => <span className="tabular-nums text-sky-700">{r.softHolds}</span>,
    },
    {
      key: 'safetyStock',
      header: 'Safety buffer',
      align: 'right',
      render: (r) => <span className="tabular-nums text-slate-500">{r.safetyStock}</span>,
    },
    {
      key: 'available',
      header: 'Online sellable',
      align: 'right',
      render: (r) => (
        <Badge tone={r.available > 0 ? 'success' : 'danger'}>{r.available}</Badge>
      ),
    },
  ]

  return (
    <div className="space-y-6">
      <div className="text-xs text-slate-500">
        <Link to="/orders" className="font-medium text-merqo-700 hover:underline">
          &larr; Back to online orders
        </Link>
      </div>

      <PageHeader
        title="Warehouse online availability"
        description="Formula: max(0, onHand - reserved - softHolds - onlineSafetyStock)."
        actions={
          <Input
            aria-label="Filter SKUs"
            placeholder="Search SKU or product..."
            value={q}
            onChange={(e) => setQ(e.target.value)}
            className="w-64"
          />
        }
      />

      {availabilityQuery.error ? (
        <ErrorState
          title="Could not load warehouse availability"
          error={availabilityQuery.error}
          onRetry={() => availabilityQuery.refetch()}
        />
      ) : (
        <div className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-xs">
          <DataTable
            columns={columns}
            rows={availabilityQuery.data?.items ?? []}
            getRowKey={(r) => r.variantId}
            isLoading={availabilityQuery.isPending}
            emptyTitle="No matching variants"
            emptyDescription="Try a different SKU or product query."
          />
        </div>
      )}
    </div>
  )
}
