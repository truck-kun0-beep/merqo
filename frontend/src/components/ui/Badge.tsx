import type { ReactNode } from 'react'

export type BadgeTone = 'neutral' | 'primary' | 'success' | 'warning' | 'danger' | 'info'

export interface BadgeProps {
  tone?: BadgeTone
  className?: string
  children: ReactNode
}

const TONE_CLASSES: Record<BadgeTone, string> = {
  neutral: 'bg-slate-100 text-slate-700 border-slate-200',
  primary: 'bg-merqo-50 text-merqo-800 border-merqo-200',
  success: 'bg-emerald-50 text-emerald-800 border-emerald-200',
  warning: 'bg-amber-50 text-amber-900 border-amber-200',
  danger: 'bg-rose-50 text-rose-800 border-rose-200',
  info: 'bg-blue-50 text-blue-800 border-blue-200',
}

/**
 * Compact semantic label badge.
 */
export function Badge({ tone = 'neutral', className = '', children }: BadgeProps) {
  return (
    <span
      className={[
        'inline-flex items-center gap-1 rounded-full border px-2.5 py-0.5 text-xs font-medium whitespace-nowrap',
        TONE_CLASSES[tone],
        className,
      ].join(' ')}
    >
      {children}
    </span>
  )
}
