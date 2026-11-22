import HealthPanel from '../components/HealthPanel'
import ServerWakingUp from '../components/ServerWakingUp'
import { useBackendHealth } from '../hooks/useBackendHealth'

/**
 * The home page.
 *
 * It shows whether the backend is running and whether it can reach the
 * database. That is the first thing the instructor sees, so it has to work
 * even when the backend is asleep.
 *
 * @returns the home page element
 */
export default function HomePage() {
  const { isLoading, isWakingUp, report, error } = useBackendHealth()

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight text-slate-900">Overview</h1>
        <p className="mt-1 text-sm text-slate-600">
          The system is being prepared. Business screens arrive with the next steps; this page
          proves the backend, the database and the connection between them are working.
        </p>
      </div>

      {isLoading ? (
        <div
          role="status"
          className="rounded-xl border border-slate-200 bg-white px-6 py-10 text-center text-sm text-slate-600"
        >
          Checking the backend...
        </div>
      ) : null}

      {isWakingUp ? <ServerWakingUp /> : null}

      {!isLoading && !isWakingUp && error && !report ? (
        <div
          role="alert"
          className="rounded-xl border border-rose-200 bg-rose-50 px-6 py-6 text-sm text-rose-800"
        >
          <p className="font-medium">The backend could not be reached.</p>
          <p className="mt-1">{error.message}</p>
        </div>
      ) : null}

      {report ? <HealthPanel report={report} /> : null}
    </div>
  )
}
