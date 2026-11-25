import HealthPanel from '../components/HealthPanel'
import ServerWakingUp from '../components/ServerWakingUp'
import { EmptyState, ErrorState, PageHeader, Skeleton } from '../components/ui'
import { useBackendHealth } from '../hooks/useBackendHealth'

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

/**
 * Overview dashboard placeholder with four separated profit/cash metric cards,
 * branch comparison table placeholder, Action Center placeholder, and live backend health.
 */
export default function HomePage() {
  const { isLoading, isWakingUp, report, error } = useBackendHealth()

  return (
    <div className="space-y-6">
      <PageHeader
        title="Overview"
        description="Operational metrics, branch comparison, and action items."
      />

      {/* Four separated metric cards (never collapsed into one ambiguous profit number) */}
      <section aria-labelledby="metrics-heading" className="space-y-3">
        <h2 id="metrics-heading" className="text-sm font-semibold text-slate-700">
          Financial summary
        </h2>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
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

      <div className="grid gap-6 lg:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
        <section aria-labelledby="branch-comparison-heading" className="space-y-3">
          <h2 id="branch-comparison-heading" className="text-sm font-semibold text-slate-700">
            Branch comparison
          </h2>
          <EmptyState
            title="Branch reporting pending"
            description="Sales, gross profit, operating profit, and cash received by branch will appear here once analytics endpoints are connected."
          />
        </section>

        <section aria-labelledby="action-center-heading" className="space-y-3">
          <h2 id="action-center-heading" className="text-sm font-semibold text-slate-700">
            Action center
          </h2>
          <EmptyState
            title="No pending alerts"
            description="Low-stock alerts, flagged adjustments, and overdue transfers will surface here."
          />
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
