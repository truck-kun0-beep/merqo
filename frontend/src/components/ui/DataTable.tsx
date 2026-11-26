import { useMemo, useState, type ReactNode } from 'react'
import { EmptyState } from './EmptyState'
import { Pagination, type PaginationProps } from './Pagination'
import { Skeleton } from './Skeleton'

export type SortDirection = 'asc' | 'desc'

export interface SortState {
  key: string
  direction: SortDirection
}

export interface DataTableColumn<T> {
  key: string
  header: ReactNode
  render: (row: T) => ReactNode
  align?: 'left' | 'right' | 'center'
  sortable?: boolean
  sortValue?: (row: T) => string | number | boolean | null | undefined
  headerClassName?: string
  cellClassName?: string
}

export interface DataTableProps<T> {
  columns: DataTableColumn<T>[]
  rows: T[]
  getRowKey: (row: T, index: number) => string
  isLoading?: boolean
  loadingRows?: number
  emptyTitle?: string
  emptyDescription?: string
  emptyAction?: ReactNode
  sort?: SortState | null
  defaultSort?: SortState | null
  onSortChange?: (next: SortState | null) => void
  pagination?: PaginationProps
  onRowClick?: (row: T) => void
  caption?: string
}

/**
 * Reusable typed data table with sorting hooks, loading skeletons, empty state, and pagination footer.
 */
export function DataTable<T>({
  columns,
  rows,
  getRowKey,
  isLoading = false,
  loadingRows = 5,
  emptyTitle = 'No records found',
  emptyDescription,
  emptyAction,
  sort: controlledSort,
  defaultSort = null,
  onSortChange,
  pagination,
  onRowClick,
  caption,
}: DataTableProps<T>) {
  const [internalSort, setInternalSort] = useState<SortState | null>(defaultSort)
  const activeSort = controlledSort !== undefined ? controlledSort : internalSort

  function handleToggleSort(columnKey: string) {
    let next: SortState | null
    if (!activeSort || activeSort.key !== columnKey) {
      next = { key: columnKey, direction: 'asc' }
    } else if (activeSort.direction === 'asc') {
      next = { key: columnKey, direction: 'desc' }
    } else {
      next = null
    }
    if (controlledSort === undefined) {
      setInternalSort(next)
    }
    onSortChange?.(next)
  }

  const sortedRows = useMemo(() => {
    if (!activeSort) {
      return rows
    }
    const column = columns.find((col) => col.key === activeSort.key)
    if (!column?.sortValue) {
      return rows
    }
    const extract = column.sortValue
    const copy = [...rows]
    copy.sort((a, b) => {
      const av = extract(a)
      const bv = extract(b)
      if (av === bv) return 0
      if (av === null || av === undefined) return 1
      if (bv === null || bv === undefined) return -1
      const cmp =
        typeof av === 'number' && typeof bv === 'number'
          ? av - bv
          : String(av).localeCompare(String(bv), undefined, { numeric: true })
      return activeSort.direction === 'asc' ? cmp : -cmp
    })
    return copy
  }, [rows, columns, activeSort])

  if (isLoading) {
    return (
      <div
        role="status"
        aria-label="Loading table"
        className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-xs"
      >
        <div className="divide-y divide-slate-100">
          <div className="grid grid-cols-4 gap-4 bg-slate-50 px-4 py-3">
            {columns.slice(0, 4).map((col) => (
              <Skeleton key={col.key} className="h-3.5 w-24" />
            ))}
          </div>
          {Array.from({ length: loadingRows }).map((_, index) => (
            <div key={index} className="grid grid-cols-4 gap-4 px-4 py-3.5">
              {columns.slice(0, 4).map((col) => (
                <Skeleton key={col.key} className="h-4 w-full" />
              ))}
            </div>
          ))}
        </div>
      </div>
    )
  }

  if (sortedRows.length === 0) {
    return <EmptyState title={emptyTitle} description={emptyDescription} action={emptyAction} />
  }

  return (
    <div className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-xs">
      <div className="overflow-x-auto">
        <table className="min-w-full divide-y divide-slate-200 text-sm">
          {caption ? <caption className="sr-only">{caption}</caption> : null}
          <thead className="bg-slate-50 text-xs font-medium tracking-wide text-slate-500 uppercase">
            <tr>
              {columns.map((col) => {
                const isSorted = activeSort?.key === col.key
                const alignClass =
                  col.align === 'right'
                    ? 'text-right'
                    : col.align === 'center'
                      ? 'text-center'
                      : 'text-left'
                const canSort = Boolean(col.sortable || col.sortValue)
                return (
                  <th
                    key={col.key}
                    scope="col"
                    aria-sort={
                      isSorted
                        ? activeSort.direction === 'asc'
                          ? 'ascending'
                          : 'descending'
                        : undefined
                    }
                    className={`px-4 py-3 whitespace-nowrap ${alignClass} ${col.headerClassName ?? ''}`}
                  >
                    {canSort ? (
                      <button
                        type="button"
                        onClick={() => handleToggleSort(col.key)}
                        aria-label={
                          typeof col.header === 'string'
                            ? `Sort by ${col.header}`
                            : `Sort by ${col.key}`
                        }
                        className="inline-flex items-center gap-1 rounded font-medium text-slate-600 hover:text-slate-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-merqo-500"
                      >
                        <span>{col.header}</span>
                        <span aria-hidden="true" className="text-[10px]">
                          {isSorted ? (activeSort.direction === 'asc' ? '▲' : '▼') : '↕'}
                        </span>
                      </button>
                    ) : (
                      col.header
                    )}
                  </th>
                )
              })}
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100 bg-white">
            {sortedRows.map((row, index) => (
              <tr
                key={getRowKey(row, index)}
                onClick={onRowClick ? () => onRowClick(row) : undefined}
                className={
                  onRowClick
                    ? 'cursor-pointer transition-colors hover:bg-slate-50'
                    : 'transition-colors hover:bg-slate-50/60'
                }
              >
                {columns.map((col) => {
                  const alignClass =
                    col.align === 'right'
                      ? 'text-right'
                      : col.align === 'center'
                        ? 'text-center'
                        : 'text-left'
                  return (
                    <td
                      key={col.key}
                      className={`px-4 py-3 text-slate-700 ${alignClass} ${col.cellClassName ?? ''}`}
                    >
                      {col.render(row)}
                    </td>
                  )
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {pagination ? <Pagination {...pagination} /> : null}
    </div>
  )
}
