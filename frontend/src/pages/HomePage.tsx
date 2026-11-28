import { useContext, useMemo } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { fetchLocations } from '../api/business'
import { fetchOrderCounts } from '../api/orders'
import { fetchCurrentCashSession, fetchSales } from '../api/pos'
import HealthPanel from '../components/HealthPanel'
import ServerWakingUp from '../components/ServerWakingUp'
import {
  Badge,
  DateText,
  EmptyState,
  ErrorState,
  MoneyText,
  PageHeader,
  Skeleton,
  StatusPill,
} from '../components/ui'
import { useBackendHealth } from '../hooks/useBackendHealth'
import { filterLocationsByPermission } from '../lib/scopes'
import { SessionContext } from '../session/sessionContext'

const OVERVIEW_METRIC_CARDS = [
  { key: 'sales', label: 'Sales', hint: 'Net completed sales' },
  { key: 'gross-profit', label: 'Gross profit', hint: 'Sales minus cost of goods sold' },
  {
    key: 'operating-profit',
    label: 'Operating profit',
    hint: 'Gross profit minus operating expenses',
  },
  { key: 'cash-received', label: 'Cash received', hint: 'Collected across payment channels' },
] as const

const ACTION_CENTER_SLOTS = [
  {
    key: 'orders-pending',
    label: 'Orders awaiting confirmation',
    hint: 'Online & manual orders needing call confirmation',
    to: '/orders?needsAction=true',
  },
  {
    key: 'low-stock',
    label: 'Low-stock alerts',
    hint: 'Variants below branch reorder point',
    to: '/inventory',
  },
  {
    key: 'inspections',
    label: 'Pending inspections',
    hint: 'Returned units awaiting Restock or Write off',
    to: '/inventory/inspections',
  },
  {
    key: 'transfers',
    label: 'Overdue transfers',
    hint: 'Dispatched transfers not yet received',
    to: '/transfers',
  },
] as const

/**
 * Overview dashboard layout:
 * - Cashier "Today at my branch" live strip (session status + latest receipt) when signed in with POS access
 * - Four separated financial KPI cards (empty states, zero fake numbers)
 * - Branch comparison table skeleton & Action center list skeleton
 * - Live backend health probe
 */
export default function HomePage() {
  const { isLoading, isWakingUp, report, error } = useBackendHealth()
  const sessionCtx = useContext(SessionContext)
  const user = sessionCtx?.user
  const can = sessionCtx?.can ?? (() => false)

  const isCashierView = Boolean(user && (can('POS_SELL') || can('POS_CASH_SESSION')))

  const locationsQuery = useQuery({
    queryKey: ['locations'],
    queryFn: fetchLocations,
    enabled: Boolean(user),
  })

  const myBranch = useMemo(() => {
    const all = locationsQuery.data ?? []
    const posBranches = filterLocationsByPermission(user, 'POS_SELL', all).filter(
      (loc) => loc.locationType === 'BRANCH',
    )
    if (posBranches.length > 0) return posBranches[0]
    const sessionBranches = filterLocationsByPermission(user, 'POS_CASH_SESSION', all).filter(
      (loc) => loc.locationType === 'BRANCH',
    )
    return sessionBranches[0] ?? null
  }, [user, locationsQuery.data])

  const branchSessionQuery = useQuery({
    queryKey: ['pos', 'session', myBranch?.id, 'overview'],
    queryFn: () => fetchCurrentCashSession(myBranch!.id),
    enabled: Boolean(isCashierView && myBranch?.id),
  })

  const lastBranchSaleQuery = useQuery({
    queryKey: ['pos', 'sales', 'overview-last', myBranch?.id],
    queryFn: () => fetchSales({ locationId: myBranch!.id, page: 0, size: 1 }),
    enabled: Boolean(isCashierView && myBranch?.id),
  })

  const orderCountsQuery = useQuery({
    queryKey: ['orders', 'counts', 'overview'],
    queryFn: fetchOrderCounts,
    enabled: Boolean(user && can('ORDER_VIEW')),
  })

  const activeSession = branchSessionQuery.data?.session ?? null
  const lastSale = lastBranchSaleQuery.data?.items?.[0] ?? null

  return (
    <div className="space-y-6">
      <PageHeader
        title="Overview"
        description="Operational metrics, branch comparison, and action items."
      />

      {isCashierView && myBranch ? (
        <section
          aria-labelledby="today-branch-heading"
          className="rounded-xl border border-slate-200 bg-white p-4 shadow-xs"
        >
          <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-100 pb-3">
            <div>
              <h2 id="today-branch-heading" className="text-sm font-semibold text-slate-900">
                Today at my branch
              </h2>
              <p className="text-xs text-slate-500">
                {myBranch.name} ({myBranch.code})
              </p>
            </div>
            <Link
              to="/pos"
              className="text-xs font-semibold text-merqo-700 hover:underline"
            >
              Open register
            </Link>
          </div>

          <div className="mt-3 grid gap-3 sm:grid-cols-2">
            <div className="rounded-lg border border-slate-200 bg-slate-50 p-3">
              <p className="text-xs font-medium text-slate-500 uppercase">Cash session</p>
              {branchSessionQuery.isPending ? (
                <Skeleton className="mt-2 h-6 w-32" />
              ) : activeSession ? (
                <div className="mt-1.5 flex flex-wrap items-center gap-2">
                  <StatusPill status="ACTIVE" label="Open" />
                  <span className="font-mono text-xs text-slate-700">
                    {activeSession.sessionNumber}
                  </span>
                  <span className="text-xs text-slate-500">
                    · {activeSession.saleCount} sale(s)
                  </span>
                </div>
              ) : (
                <div className="mt-1.5 flex items-center gap-2">
                  <Badge tone="warning">No open session</Badge>
                </div>
              )}
            </div>

            <div className="rounded-lg border border-slate-200 bg-slate-50 p-3">
              <p className="text-xs font-medium text-slate-500 uppercase">Last receipt</p>
              {lastBranchSaleQuery.isPending ? (
                <Skeleton className="mt-2 h-6 w-40" />
              ) : lastSale ? (
                <div className="mt-1.5 flex flex-wrap items-center justify-between gap-2 text-xs">
                  <div>
                    <Link
                      to="/pos/sales"
                      className="font-mono font-semibold text-merqo-700 hover:underline"
                    >
                      {lastSale.receiptNumber}
                    </Link>
                    <p className="text-slate-500">
                      <DateText value={lastSale.createdAt} withTime />
                    </p>
                  </div>
                  <MoneyText
                    amount={lastSale.total}
                    className="text-sm font-semibold text-slate-900"
                  />
                </div>
              ) : (
                <p className="mt-1.5 text-xs text-slate-500">No sales recorded yet.</p>
              )}
            </div>
          </div>
        </section>
      ) : null}

      {/* Four separated metric cards (never collapsed into one ambiguous profit number) */}
      <section aria-labelledby="metrics-heading" className="space-y-3">
        <h2 id="metrics-heading" className="text-sm font-semibold text-slate-700">
          Financial summary
        </h2>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
          {OVERVIEW_METRIC_CARDS.map((card) => (
            <div
              key={card.key}
              className="rounded-xl border border-slate-200 bg-white p-4 shadow-xs"
            >
              <p className="text-xs font-medium tracking-wide text-slate-500 uppercase">
                {card.label}
              </p>
              <p className="mt-2 text-xl font-semibold text-slate-400 tabular-nums">—</p>
              <p className="mt-1 text-xs text-slate-500">{card.hint}</p>
            </div>
          ))}
        </div>
      </section>

      <div className="grid grid-cols-1 gap-6 xl:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
        <section aria-labelledby="branch-comparison-heading" className="min-w-0 space-y-3">
          <h2 id="branch-comparison-heading" className="text-sm font-semibold text-slate-700">
            Branch comparison
          </h2>
          <div className="overflow-x-auto rounded-xl border border-slate-200 bg-white shadow-xs">
            <table className="min-w-full divide-y divide-slate-200 text-sm">
              <thead className="bg-slate-50 text-xs font-medium text-slate-500 uppercase">
                <tr>
                  <th scope="col" className="px-4 py-3 text-left">
                    Branch
                  </th>
                  <th scope="col" className="px-4 py-3 text-right">
                    Sales
                  </th>
                  <th scope="col" className="px-4 py-3 text-right">
                    Gross profit
                  </th>
                  <th scope="col" className="px-4 py-3 text-right">
                    Operating profit
                  </th>
                  <th scope="col" className="px-4 py-3 text-right">
                    Cash received
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td colSpan={5} className="p-4">
                    <EmptyState
                      title="Branch reporting pending"
                      description="Per-branch sales, gross profit, operating profit, and cash received will populate here once analytics endpoints are connected."
                    />
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section aria-labelledby="action-center-heading" className="min-w-0 space-y-3">
          <h2 id="action-center-heading" className="text-sm font-semibold text-slate-700">
            Action center
          </h2>
          <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-xs">
            <ul className="divide-y divide-slate-100">
              {ACTION_CENTER_SLOTS.map((slot) => (
                <li key={slot.key} className="flex items-center justify-between gap-3 py-3 first:pt-0 last:pb-0">
                  <div>
                    <Link
                      to={slot.to}
                      className="text-sm font-medium text-slate-900 hover:text-merqo-700 hover:underline"
                    >
                      {slot.label}
                    </Link>
                    <p className="text-xs text-slate-500">{slot.hint}</p>
                  </div>
                  <span className="text-xs font-medium text-slate-600 tabular-nums">
                    {slot.key === 'orders-pending' && can('ORDER_VIEW')
                      ? orderCountsQuery.data?.needsAction ?? '—'
                      : '—'}
                  </span>
                </li>
              ))}
            </ul>
          </div>
        </section>
      </div>

      {isLoading ? (
        <div
          role="status"
          aria-label="Checking the backend"
          className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4"
        >
          <Skeleton className="h-24 w-full rounded-xl" />
          <Skeleton className="h-24 w-full rounded-xl" />
          <Skeleton className="h-24 w-full rounded-xl" />
          <Skeleton className="h-24 w-full rounded-xl" />
        </div>
      ) : null}

      {isWakingUp ? <ServerWakingUp /> : null}

      {!isLoading && !isWakingUp && error && !report ? (
        <ErrorState title="The backend could not be reached." error={error} />
      ) : null}

      {report ? <HealthPanel report={report} /> : null}
    </div>
  )
}
