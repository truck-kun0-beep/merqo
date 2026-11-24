import type { ReactNode } from 'react'

export interface EmptyStateProps {
  title: string
  description?: string
  icon?: ReactNode
  action?: ReactNode
  className?: string
}

/**
 * Calm empty-state card displayed when a list or query returns zero items.
 */
export function EmptyState({
  title,
  description,
  icon,
  action,
  className = '',
}: EmptyStateProps) {
  return (
    <div
      className={[
        'flex flex-col items-center justify-center rounded-xl border border-slate-200 bg-white px-6 py-12 text-center shadow-xs',
        className,
      ].join(' ')}
    >
      {icon ? <div className="mb-3 text-slate-400">{icon}</div> : null}
      <h3 className="text-sm font-semibold text-slate-900">{title}</h3>
      {description ? <p className="mt-1 max-w-md text-sm text-slate-500">{description}</p> : null}
      {action ? <div className="mt-4">{action}</div> : null}
    </div>
  )
}
