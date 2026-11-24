import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { fetchAuditLogs } from '../api/business'
import type { AuditLogRow } from '../api/models'
import {
  Badge,
  Button,
  DataTable,
  DateText,
  ErrorState,
  Input,
  PageHeader,
  type DataTableColumn,
} from '../components/ui'

/**
 * Append-only audit log screen.
 */
export default function AuditPage() {
  const [actionFilter, setActionFilter] = useState('')
  const [entityFilter, setEntityFilter] = useState('')
  const [appliedAction, setAppliedAction] = useState('')
  const [appliedEntity, setAppliedEntity] = useState('')
  const [page, setPage] = useState(0)

  const query = useQuery({
    queryKey: ['audit-logs', appliedAction, appliedEntity, page],
    queryFn: () =>
      fetchAuditLogs({
        action: appliedAction || undefined,
        entityType: appliedEntity || undefined,
        page,
        size: 50,
      }),
  })

  const rows = query.data?.items ?? []

  const columns: DataTableColumn<AuditLogRow>[] = [
    {
      key: 'occurredAt',
      header: 'When',
      sortable: true,
      sortValue: (row) => row.occurredAt,
      render: (row) => (
        <span className="text-xs text-slate-500">
          <DateText value={row.occurredAt} withTime />
        </span>
      ),
    },
    {
      key: 'actor',
      header: 'Who',
      sortable: true,
      sortValue: (row) => row.actorUsername ?? 'System',
      render: (row) => <span className="font-medium text-slate-800">{row.actorUsername ?? 'System'}</span>,
    },
    {
      key: 'action',
      header: 'What',
      render: (row) => (
        <Badge tone="neutral" className="font-mono text-[11px]">
          {row.action}
        </Badge>
      ),
    },
    {
      key: 'record',
      header: 'Record',
      render: (row) => (
        <span className="text-xs text-slate-600">
          {row.entityType}
          {row.entityId ? (
            <span className="ml-1 font-mono text-slate-400">{row.entityId.slice(0, 8)}</span>
          ) : null}
        </span>
      ),
    },
    {
      key: 'change',
      header: 'Change',
      render: (row) => (
        <span className="block max-w-md truncate text-xs text-slate-600">
          {describeChange(row.oldValues, row.newValues)}
        </span>
      ),
    },
  ]

  return (
    <div className="space-y-6">
      <PageHeader
        title="Audit log"
        description="Append-only operational history across your scoped locations."
      />

      <form
        className="flex flex-wrap items-center gap-2"
        onSubmit={(event) => {
          event.preventDefault()
          setPage(0)
          setAppliedAction(actionFilter.trim())
          setAppliedEntity(entityFilter.trim())
        }}
      >
        <Input
          placeholder="Filter by action (e.g. PRICE_CHANGED)"
          value={actionFilter}
          onChange={(e) => setActionFilter(e.target.value)}
          className="max-w-xs"
          aria-label="Filter by action"
        />
        <Input
          placeholder="Filter by record type (e.g. products)"
          value={entityFilter}
          onChange={(e) => setEntityFilter(e.target.value)}
          className="max-w-xs"
          aria-label="Filter by record type"
        />
        <Button type="submit" variant="outline">
          Filter
        </Button>
        {appliedAction || appliedEntity ? (
          <Button
            variant="ghost"
            onClick={() => {
              setActionFilter('')
              setEntityFilter('')
              setAppliedAction('')
              setAppliedEntity('')
              setPage(0)
            }}
          >
            Clear
          </Button>
        ) : null}
      </form>

      {query.error ? (
        <ErrorState
          title="The audit log could not be loaded."
          error={query.error}
          onRetry={() => query.refetch()}
        />
      ) : (
        <DataTable
          columns={columns}
          rows={rows}
          getRowKey={(row) => row.id}
          isLoading={query.isPending}
          emptyTitle="No audit events"
          emptyDescription="Nothing matching these filters has been recorded yet."
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
    </div>
  )
}

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
