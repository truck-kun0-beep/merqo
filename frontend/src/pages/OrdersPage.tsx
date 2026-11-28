import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import {
  fetchOrderCounts,
  fetchOrders,
  type OrderChannel,
  type OrderStatus,
  type OrderSummaryView,
} from '../api/orders'
import { OrderFlagsBadges, RiskBadge } from '../components/OrderBadges'
import {
  Badge,
  Button,
  DataTable,
  DateText,
  ErrorState,
  Input,
  MoneyText,
  PageHeader,
  Pagination,
  Select,
  StatusPill,
  type DataTableColumn,
} from '../components/ui'
import { useSession } from '../session/sessionContext'

const STATUS_OPTIONS: OrderStatus[] = [
  'PENDING_CONFIRMATION',
  'FOLLOW_UP',
  'CONFIRMED',
  'DECLINED',
  'CANCELLED',
  'READY_TO_SHIP',
  'SHIPPED',
  'DELIVERED',
  'REFUSED',
  'RETURNED',
]

export default function OrdersPage() {
  const { can } = useSession()
  const [searchParams, setSearchParams] = useSearchParams()

  const [q, setQ] = useState('')
  const [status, setStatus] = useState<OrderStatus | ''>('')
  const [channel, setChannel] = useState<OrderChannel | ''>('')
  const [needsAction, setNeedsAction] = useState(searchParams.get('needsAction') === 'true')
  const [priceMismatch, setPriceMismatch] = useState(false)
  const [invalidPhone, setInvalidPhone] = useState(false)
  const [cancellationRequested, setCancellationRequested] = useState(false)
  const [fromDate, setFromDate] = useState('')
  const [toDate, setToDate] = useState('')
  const [page, setPage] = useState(0)

  const countsQuery = useQuery({
    queryKey: ['orders', 'counts'],
    queryFn: fetchOrderCounts,
  })

  const ordersQuery = useQuery({
    queryKey: [
      'orders',
      'list',
      {
        q,
        status,
        channel,
        needsAction,
        priceMismatch,
        invalidPhone,
        cancellationRequested,
        fromDate,
        toDate,
        page,
      },
    ],
    queryFn: () =>
      fetchOrders({
        q,
        status,
        channel,
        needsAction,
        priceMismatch,
        invalidPhone,
        cancellationRequested,
        fromDate,
        toDate,
        page,
        size: 20,
      }),
    placeholderData: keepPreviousData,
  })

  function toggleNeedsAction(next: boolean) {
    setNeedsAction(next)
    setPage(0)
    if (next) {
      setSearchParams({ needsAction: 'true' })
    } else {
      setSearchParams({})
    }
  }

  const columns: DataTableColumn<OrderSummaryView>[] = [
    {
      key: 'orderNumber',
      header: 'Order',
      render: (order) => (
        <div>
          <Link
            to={`/orders/${order.id}`}
            className="font-mono font-semibold text-merqo-700 hover:underline"
          >
            {order.orderNumber}
          </Link>
          <p className="text-xs text-slate-500">
            {order.channel} {order.externalOrderId ? `· ${order.externalOrderId}` : ''}
          </p>
        </div>
      ),
    },
    {
      key: 'customer',
      header: 'Customer',
      render: (order) => (
        <div className="space-y-1">
          <p className="font-medium text-slate-900">{order.customerName}</p>
          <p className="font-mono text-xs text-slate-600">
            {order.phoneNormalized ?? order.phoneRaw}
          </p>
          <RiskBadge tier={order.riskTier} />
        </div>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      render: (order) => (
        <div className="space-y-1">
          <StatusPill status={order.status} />
          <p className="text-xs text-slate-500">Calls: {order.callAttempts}</p>
          {order.followUpAt ? (
            <p className="text-xs text-slate-500">
              Follow up: <DateText value={order.followUpAt} withTime />
            </p>
          ) : null}
        </div>
      ),
    },
    {
      key: 'flags',
      header: 'Flags',
      render: (order) => <OrderFlagsBadges flags={order.flags} />,
    },
    {
      key: 'payment',
      header: 'Payment',
      render: (order) => (
        <div className="text-xs">
          <Badge tone="neutral">{order.paymentMode}</Badge>
          {Number(order.advanceAmount) > 0 ? (
            <p className="mt-1 text-slate-600">
              Adv: <MoneyText amount={order.advanceAmount} />
            </p>
          ) : null}
        </div>
      ),
    },
    {
      key: 'total',
      header: 'Total',
      align: 'right',
      render: (order) => (
        <MoneyText amount={order.total} className="font-semibold text-slate-900" />
      ),
    },
    {
      key: 'createdAt',
      header: 'Received',
      render: (order) => <DateText value={order.createdAt} withTime className="text-xs" />,
    },
  ]

  const counts = countsQuery.data
  const pageData = ordersQuery.data

  return (
    <div className="space-y-6">
      <PageHeader
        title="Online orders"
        description="Call verification, customer history screening, and warehouse stock reservation."
        actions={
          <div className="flex flex-wrap gap-2">
            {can('ORDER_VIEW') && can('INVENTORY_VIEW') ? (
              <Link to="/orders/availability">
                <Button variant="outline">Warehouse availability</Button>
              </Link>
            ) : null}
            {can('ORDER_CREATE_MANUAL') ? (
              <Link to="/orders/manual">
                <Button>New manual order</Button>
              </Link>
            ) : null}
          </div>
        }
      />

      {counts ? (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <button
            type="button"
            onClick={() => toggleNeedsAction(true)}
            className="rounded-xl border border-slate-200 bg-white p-3 text-left transition hover:border-merqo-400"
          >
            <p className="text-xs font-medium text-slate-500 uppercase">Awaiting confirmation</p>
            <p className="mt-1 text-2xl font-semibold text-amber-700 tabular-nums">
              {counts.needsAction}
            </p>
          </button>
          <div className="rounded-xl border border-slate-200 bg-white p-3">
            <p className="text-xs font-medium text-slate-500 uppercase">Pending confirmation</p>
            <p className="mt-1 text-2xl font-semibold text-slate-900 tabular-nums">
              {counts.pendingConfirmation}
            </p>
          </div>
          <div className="rounded-xl border border-slate-200 bg-white p-3">
            <p className="text-xs font-medium text-slate-500 uppercase">Follow-up queue</p>
            <p className="mt-1 text-2xl font-semibold text-sky-700 tabular-nums">
              {counts.followUp}
            </p>
          </div>
          <div className="rounded-xl border border-slate-200 bg-white p-3">
            <p className="text-xs font-medium text-slate-500 uppercase">Confirmed</p>
            <p className="mt-1 text-2xl font-semibold text-emerald-700 tabular-nums">
              {counts.confirmed}
            </p>
          </div>
        </div>
      ) : null}

      <div className="space-y-3 rounded-xl border border-slate-200 bg-white p-4 shadow-xs">
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
          <Input
            aria-label="Search orders"
            placeholder="Order #, external ID, phone, name..."
            value={q}
            onChange={(e) => {
              setQ(e.target.value)
              setPage(0)
            }}
          />
          <Select
            aria-label="Filter by status"
            value={status}
            onChange={(e) => {
              setStatus(e.target.value as OrderStatus | '')
              setPage(0)
            }}
          >
            <option value="">All statuses</option>
            {STATUS_OPTIONS.map((st) => (
              <option key={st} value={st}>
                {st.replaceAll('_', ' ')}
              </option>
            ))}
          </Select>
          <Select
            aria-label="Filter by channel"
            value={channel}
            onChange={(e) => {
              setChannel(e.target.value as OrderChannel | '')
              setPage(0)
            }}
          >
            <option value="">All channels</option>
            <option value="ONLINE">Website (ONLINE)</option>
            <option value="MANUAL">Phone / WhatsApp (MANUAL)</option>
          </Select>
          <Input
            aria-label="From date (Dhaka)"
            type="date"
            value={fromDate}
            onChange={(e) => {
              setFromDate(e.target.value)
              setPage(0)
            }}
          />
          <Input
            aria-label="To date (Dhaka)"
            type="date"
            value={toDate}
            onChange={(e) => {
              setToDate(e.target.value)
              setPage(0)
            }}
          />
        </div>

        <div className="flex flex-wrap gap-2">
          <Button
            size="sm"
            variant={needsAction ? 'primary' : 'outline'}
            onClick={() => toggleNeedsAction(!needsAction)}
          >
            Needs confirmation
          </Button>
          <Button
            size="sm"
            variant={priceMismatch ? 'primary' : 'outline'}
            onClick={() => {
              setPriceMismatch(!priceMismatch)
              setPage(0)
            }}
          >
            Price mismatch
          </Button>
          <Button
            size="sm"
            variant={invalidPhone ? 'primary' : 'outline'}
            onClick={() => {
              setInvalidPhone(!invalidPhone)
              setPage(0)
            }}
          >
            Bad phone
          </Button>
          <Button
            size="sm"
            variant={cancellationRequested ? 'primary' : 'outline'}
            onClick={() => {
              setCancellationRequested(!cancellationRequested)
              setPage(0)
            }}
          >
            Cancel requested
          </Button>
        </div>
      </div>

      {ordersQuery.error ? (
        <ErrorState
          title="Could not load orders"
          error={ordersQuery.error}
          onRetry={() => ordersQuery.refetch()}
        />
      ) : (
        <div className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-xs">
          <DataTable
            columns={columns}
            rows={pageData?.items ?? []}
            getRowKey={(o) => o.id}
            isLoading={ordersQuery.isPending}
            emptyTitle="No matching orders"
            emptyDescription="Try clearing search or status filters."
          />
          {pageData ? (
            <Pagination
              page={pageData.page}
              totalPages={pageData.totalPages}
              totalItems={pageData.totalItems}
              pageSize={pageData.size}
              onPageChange={setPage}
            />
          ) : null}
        </div>
      )}
    </div>
  )
}
