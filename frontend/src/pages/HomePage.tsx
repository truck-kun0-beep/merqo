import HealthPanel from '../components/HealthPanel'
import ServerWakingUp from '../components/ServerWakingUp'
import { ErrorState, PageHeader, Skeleton } from '../components/ui'
import { useBackendHealth } from '../hooks/useBackendHealth'

/**
 * Dashboard overview placeholder showing live backend and database health.
 */
export default function HomePage() {
  const { isLoading, isWakingUp, report, error } = useBackendHealth()

  return (
    <div className="space-y-6">
      <PageHeader
        title="Overview"
        description="System status and operational readiness across your connected services."
      />

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
