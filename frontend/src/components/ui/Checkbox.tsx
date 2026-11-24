import { useId, type InputHTMLAttributes, type ReactNode } from 'react'

export interface CheckboxProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type'> {
  label?: ReactNode
  description?: string
}

/**
 * Accessible checkbox with optional label and description.
 */
export function Checkbox({
  id,
  label,
  description,
  className = '',
  disabled,
  ...rest
}: CheckboxProps) {
  const autoId = useId()
  const inputId = id ?? autoId

  if (!label && !description) {
    return (
      <input
        id={inputId}
        type="checkbox"
        disabled={disabled}
        className={[
          'h-4 w-4 rounded border-slate-300 text-merqo-700 accent-merqo-700',
          'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-merqo-500 focus-visible:ring-offset-1',
          'disabled:cursor-not-allowed disabled:opacity-50',
          className,
        ].join(' ')}
        {...rest}
      />
    )
  }

  return (
    <div className={`flex items-start gap-2.5 ${className}`}>
      <input
        id={inputId}
        type="checkbox"
        disabled={disabled}
        className={[
          'mt-0.5 h-4 w-4 shrink-0 rounded border-slate-300 text-merqo-700 accent-merqo-700',
          'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-merqo-500 focus-visible:ring-offset-1',
          'disabled:cursor-not-allowed disabled:opacity-50',
        ].join(' ')}
        {...rest}
      />
      <label
        htmlFor={inputId}
        className={`text-sm leading-snug ${disabled ? 'cursor-not-allowed text-slate-400' : 'cursor-pointer text-slate-700'}`}
      >
        {label ? <span className="block font-medium text-slate-800">{label}</span> : null}
        {description ? (
          <span className="mt-0.5 block text-xs text-slate-500">{description}</span>
        ) : null}
      </label>
    </div>
  )
}
