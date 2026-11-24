import { Button } from './Button'

export interface PaginationProps {
  page: number
  totalPages: number
  totalItems: number
  pageSize?: number
  onPageChange: (nextPage: number) => void
  className?: string
}

/**
 * Page navigation footer for server-paginated and client-paginated tables.
 */
export function Pagination({
  page,
  totalPages,
  totalItems,
  pageSize,
  onPageChange,
  className = '',
}: PaginationProps) {
  const safeTotalPages = Math.max(totalPages, 1)
  const start = totalItems === 0 ? 0 : page * (pageSize ?? 20) + 1
  const end = pageSize ? Math.min((page + 1) * pageSize, totalItems) : totalItems

  return (
    <div
      className={[
        'flex flex-wrap items-center justify-between gap-3 border-t border-slate-200 bg-white px-4 py-3 text-xs text-slate-600',
        className,
      ].join(' ')}
    >
      <p>
        {totalItems === 0 ? (
          'No results'
        ) : pageSize ? (
          <>
            Showing <span className="font-medium text-slate-900">{start}</span> to{' '}
            <span className="font-medium text-slate-900">{end}</span> of{' '}
            <span className="font-medium text-slate-900">{totalItems}</span>
          </>
        ) : (
          <>
            Page <span className="font-medium text-slate-900">{page + 1}</span> of{' '}
            <span className="font-medium text-slate-900">{safeTotalPages}</span> ({totalItems} total)
          </>
        )}
      </p>

      <div className="flex items-center gap-2">
        <Button
          variant="outline"
          size="sm"
          disabled={page <= 0}
          onClick={() => onPageChange(page - 1)}
        >
          Previous
        </Button>
        <span className="px-1 text-slate-500 tabular-nums">
          {page + 1} / {safeTotalPages}
        </span>
        <Button
          variant="outline"
          size="sm"
          disabled={page + 1 >= safeTotalPages}
          onClick={() => onPageChange(page + 1)}
        >
          Next
        </Button>
      </div>
    </div>
  )
}
