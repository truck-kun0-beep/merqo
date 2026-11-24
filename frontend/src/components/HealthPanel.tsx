import type { HealthReport } from '../api/types'
import { DateText, StatusPill } from './ui'

function StatusCard({
  label,
  value,
  status,
}: {
  label: string
  value: string
  status?: 'UP' | 'DOWN'
}) {
  return (
    <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-xs">
      <p className="text-xs font-medium tracking-wide text-slate-500 uppercase">{label}</p>
      <div className="mt-2 flex items-center justify-between gap-2">
        <p className="text-base font-semibold text-slate-900">{value}</p>
        {status ? <StatusPill status={status} /> : null}
      </div>
    </div>
  )
}

/**
 * Renders the health report of the backend.
 */
export default function HealthPanel({ report }: { report: HealthReport }) {
  const applicationUp = report.status === 'UP'
  const databaseUp = report.database.status === 'UP'

  return (
    <section aria-labelledby="health-heading" className="space-y-4">
      <h2 id="health-heading" className="text-sm font-semibold text-slate-700">
        Backend health
      </h2>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatusCard
          label="Application"
          value={applicationUp ? 'UP' : 'DOWN'}
          status={applicationUp ? 'UP' : 'DOWN'}
        />
        <StatusCard
          label="Database"
          value={databaseUp ? 'UP' : 'DOWN'}
          status={databaseUp ? 'UP' : 'DOWN'}
        />
        <StatusCard label="Service" value={report.service} />
        <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-xs">
          <p className="text-xs font-medium tracking-wide text-slate-500 uppercase">Checked at</p>
          <p className="mt-2 text-base font-semibold text-slate-900">
            <DateText value={report.time} withTime />
          </p>
        </div>
      </div>
      <p className="text-xs text-slate-500">
        Business time (Asia/Dhaka): <DateText value={report.businessTime} withTime />
      </p>
    </section>
  )
}
