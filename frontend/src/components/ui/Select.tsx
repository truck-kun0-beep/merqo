import type { Ref, SelectHTMLAttributes } from 'react'

export interface SelectProps extends SelectHTMLAttributes<HTMLSelectElement> {
  invalid?: boolean
  ref?: Ref<HTMLSelectElement>
}

/**
 * Native select dropdown styled to match {@link Input}.
 */
export function Select({ invalid = false, className = '', children, ref, ...rest }: SelectProps) {
  return (
    <select
      ref={ref}
      aria-invalid={invalid || undefined}
      className={[
        'w-full rounded-md border bg-white px-3 py-2 text-sm text-slate-900',
        'transition-colors outline-none',
        'focus:border-merqo-500 focus:ring-2 focus:ring-merqo-100',
        'disabled:cursor-not-allowed disabled:bg-slate-50 disabled:text-slate-500',
        invalid ? 'border-rose-400 focus:border-rose-500 focus:ring-rose-100' : 'border-slate-300',
        className,
      ].join(' ')}
      {...rest}
    >
      {children}
    </select>
  )
}
