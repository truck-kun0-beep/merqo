import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'

export interface BreadcrumbItem {
  label: string
  to?: string
}

export interface PageHeaderProps {
  title: string
  description?: string
  breadcrumbs?: BreadcrumbItem[]
  actions?: ReactNode
}

/**
 * Standard page header with optional breadcrumbs, subtitle, and right-aligned action buttons.
 */
export function PageHeader({ title, description, breadcrumbs, actions }: PageHeaderProps) {
  return (
    <div className="flex flex-col gap-3 border-b border-slate-200 pb-4 sm:flex-row sm:items-end sm:justify-between">
      <div>
        {breadcrumbs && breadcrumbs.length > 0 ? (
          <nav aria-label="Breadcrumb" className="mb-1.5 flex items-center gap-1.5 text-xs text-slate-500">
            {breadcrumbs.map((crumb, index) => (
              <span key={`${crumb.label}-${index}`} className="flex items-center gap-1.5">
                {index > 0 ? <span aria-hidden="true">/</span> : null}
                {crumb.to ? (
                  <Link to={crumb.to} className="font-medium text-merqo-700 hover:underline">
                    {crumb.label}
                  </Link>
                ) : (
                  <span className="text-slate-600">{crumb.label}</span>
                )}
              </span>
            ))}
          </nav>
        ) : null}
        <h1 className="text-2xl font-semibold tracking-tight text-slate-900">{title}</h1>
        {description ? <p className="mt-1 text-sm text-slate-600">{description}</p> : null}
      </div>
      {actions ? <div className="flex flex-wrap items-center gap-2">{actions}</div> : null}
    </div>
  )
}
