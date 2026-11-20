import type { HealthReport } from '../api/types'
import { formatDateTime } from '../lib/format'

/**
 * Shows one health value as a labelled card.
 *
 * @param props the label and the value
 * @returns the card element
 */
function StatusCard({
  label,
  value,
  tone,
}: {
  label: string
  value: string
  tone: 'good' | 'bad' | 'neutral'
}) {
  const toneClass =
    tone === 'good'
      ? 'text-emerald-700'
      : tone === 'bad'
        ? 'text-rose-700'
        : 'text-slate-900'

  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4">
      <p className="text-xs font-medium tracking-wide text-slate-500 uppercase">{label}</p>
      <p className={`mt-1 text-lg font-semibold ${toneClass}`}>{value}</p>
    </div>
  )
}

/**
 * Renders the health report of the backend.
 *
 * @param props the health report
 * @returns the health panel element
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
          tone={applicationUp ? 'good' : 'bad'}
        />
        <StatusCard
          label="Database"
          value={databaseUp ? 'UP' : 'DOWN'}
          tone={databaseUp ? 'good' : 'bad'}
        />
        <StatusCard label="Service" value={report.service} tone="neutral" />
        <StatusCard label="Checked at" value={formatDateTime(report.time)} tone="neutral" />
      </div>
      <p className="text-xs text-slate-500">
        Business time (Asia/Dhaka): {formatDateTime(report.businessTime)}
      </p>
    </section>
  )
}
