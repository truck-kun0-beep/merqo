import { cloneElement, isValidElement, useId, type ReactElement, type ReactNode } from 'react'

export interface FormFieldProps {
  label: string
  htmlFor?: string
  hint?: string
  error?: string
  required?: boolean
  className?: string
  children: ReactNode
}

/**
 * Wraps a form control with an accessible label, optional hint, and validation error.
 */
export function FormField({
  label,
  htmlFor,
  hint,
  error,
  required = false,
  className = '',
  children,
}: FormFieldProps) {
  const generatedId = useId()
  const controlId = htmlFor ?? generatedId
  const hintId = hint ? `${controlId}-hint` : undefined
  const errorId = error ? `${controlId}-error` : undefined
  const describedBy = [hintId, errorId].filter(Boolean).join(' ') || undefined

  let enhancedChild = children
  if (isValidElement(children)) {
    const childProps = children.props as Record<string, unknown>
    const nextProps: Record<string, unknown> = {}
    if (!childProps.id) {
      nextProps.id = controlId
    }
    if (describedBy && !childProps['aria-describedby']) {
      nextProps['aria-describedby'] = describedBy
    }
    if (error && childProps['aria-invalid'] === undefined) {
      nextProps['aria-invalid'] = true
    }
    enhancedChild = cloneElement(children as ReactElement<Record<string, unknown>>, nextProps)
  }

  return (
    <div className={`space-y-1.5 ${className}`}>
      <div className="flex items-center gap-1">
        <label htmlFor={controlId} className="block text-sm font-medium text-slate-700">
          {label}
        </label>
        {required ? (
          <span aria-hidden="true" className="text-xs font-medium text-rose-600">
            *
          </span>
        ) : null}
      </div>
      <div>{enhancedChild}</div>
      {hint ? (
        <p id={hintId} className="text-xs text-slate-500">
          {hint}
        </p>
      ) : null}
      {error ? (
        <p id={errorId} role="alert" className="text-xs font-medium text-rose-700">
          {error}
        </p>
      ) : null}
    </div>
  )
}
