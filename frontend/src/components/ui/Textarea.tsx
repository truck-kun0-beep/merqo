import type { Ref, TextareaHTMLAttributes } from 'react'

export interface TextareaProps extends TextareaHTMLAttributes<HTMLTextAreaElement> {
  invalid?: boolean
  ref?: Ref<HTMLTextAreaElement>
}

/**
 * Multi-line text input with consistent focus and validation states.
 */
export function Textarea({ invalid = false, className = '', rows = 3, ref, ...rest }: TextareaProps) {
  return (
    <textarea
      ref={ref}
      rows={rows}
      aria-invalid={invalid || undefined}
      className={[
        'w-full rounded-md border bg-white px-3 py-2 text-sm text-slate-900 placeholder:text-slate-400',
        'transition-colors outline-none',
        'focus:border-merqo-500 focus:ring-2 focus:ring-merqo-100',
        'disabled:cursor-not-allowed disabled:bg-slate-50 disabled:text-slate-500',
        invalid
          ? 'border-rose-400 focus:border-rose-500 focus:ring-rose-100'
          : 'border-slate-300',
        className,
      ].join(' ')}
      {...rest}
    />
  )
}
