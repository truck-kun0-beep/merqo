import { useQuery } from '@tanstack/react-query'
import { fetchAuditLogs } from '../api/business'
import { formatDateTime } from '../lib/format'

/**
 * The audit screen.
 *
 * The audit log is append only: the backend has no update and no delete, and a
 * database trigger raises an error if anything tries to change a row. This
 * screen therefore only ever reads.
 *
 * @returns the audit screen element
 */
export default function AuditPage() {
  const query = useQuery({
    queryKey: ['audit-logs'],
    queryFn: () => fetchAuditLogs({ size: 50 }),
  })

  const rows = query.data?.items ?? []

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight text-slate-900">Audit log</h1>
        <p className="mt-1 text-sm text-slate-600">
          Who did what and when. The log is append only and cannot be edited, not even by the owner.
        </p>
      </div>

      {query.isPending ? (
        <div
          role="status"
          className="rounded-xl border border-slate-200 bg-white px-6 py-8 text-center text-sm text-slate-600"
        >
          Loading the audit log...
        </div>
      ) : null}

      {query.error ? (
        <div
          role="alert"
          className="rounded-xl border border-rose-200 bg-rose-50 px-6 py-6 text-sm text-rose-800"
        >
          <p className="font-medium">The audit log could not be loaded.</p>
          <p className="mt-1">{query.error.message}</p>
        </div>
      ) : null}

      {query.isSuccess && rows.length === 0 ? (
        <div className="rounded-xl border border-slate-200 bg-white px-6 py-8 text-center text-sm text-slate-600">
          Nothing has been recorded yet.
        </div>
      ) : null}

      {rows.length > 0 ? (
        <div className="overflow-x-auto rounded-xl border border-slate-200 bg-white shadow-sm">
          <table className="min-w-full divide-y divide-slate-200 text-sm">
            <thead className="bg-slate-50 text-left text-xs tracking-wide text-slate-500 uppercase">
              <tr>
                <th scope="col" className="px-4 py-3 font-medium">
                  When
                </th>
                <th scope="col" className="px-4 py-3 font-medium">
                  Who
                </th>
                <th scope="col" className="px-4 py-3 font-medium">
                  What
                </th>
                <th scope="col" className="px-4 py-3 font-medium">
                  Record
                </th>
                <th scope="col" className="px-4 py-3 font-medium">
                  Change
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {rows.map((row) => (
                <tr key={row.id}>
                  <td className="px-4 py-3 text-xs whitespace-nowrap text-slate-500">
                    {formatDateTime(row.occurredAt)}
                  </td>
                  <td className="px-4 py-3 text-slate-700">{row.actorUsername ?? 'System'}</td>
                  <td className="px-4 py-3">
                    <span className="rounded bg-slate-100 px-2 py-0.5 font-mono text-xs text-slate-700">
                      {row.action}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-xs text-slate-600">
                    {row.entityType}
                    {row.entityId ? (
                      <span className="ml-1 font-mono text-slate-400">
                        {row.entityId.slice(0, 8)}
                      </span>
                    ) : null}
                  </td>
                  <td className="px-4 py-3 text-xs text-slate-600">
                    {describeChange(row.oldValues, row.newValues)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}

      {query.data ? (
        <p className="text-xs text-slate-500">
          Showing {query.data.items.length} of {query.data.totalItems} rows.
        </p>
      ) : null}
    </div>
  )
}

/**
 * Describes one change in one short line.
 *
 * @param oldValues the values before the change
 * @param newValues the values after the change
 * @returns the description
 */
function describeChange(
  oldValues: Record<string, unknown> | null,
  newValues: Record<string, unknown> | null,
): string {
  if (!newValues || Object.keys(newValues).length === 0) {
    return '-'
  }
  const parts = Object.entries(newValues).map(([key, value]) => {
    const before = oldValues?.[key]
    if (before === undefined) {
      return `${key}: ${String(value)}`
    }
    return `${key}: ${String(before)} -> ${String(value)}`
  })
  return parts.join(', ')
}
