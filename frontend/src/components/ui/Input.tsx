import type { InputHTMLAttributes, Ref } from 'react'

export interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  invalid?: boolean
  ref?: Ref<HTMLInputElement>
}

/**
 * Single-line text/number/search input with consistent focus and error styling.
 */
export function Input({ invalid = false, className = '', ref, ...rest }: InputProps) {
  return (
    <input
      ref={ref}
      aria-invalid={invalid || undefined}
      className={[
        'w-full rounded-md border bg-white px-3 py-2 text-sm text-slate-900 placeholder:text-slate-400',
        'transition-colors outline-none',
        'focus:border-merqo-500 focus:ring-2 focus:ring-merqo-100',
        'disabled:cursor-not-allowed disabled:bg-slate-50 disabled:text-slate-500',
        invalid ? 'border-rose-400 focus:border-rose-500 focus:ring-rose-100' : 'border-slate-300',
        className,
      ].join(' ')}
      {...rest}
    />
  )
}
