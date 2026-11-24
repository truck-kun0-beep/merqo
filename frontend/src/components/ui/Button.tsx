import type { ButtonHTMLAttributes, ReactNode } from 'react'

export type ButtonVariant = 'primary' | 'secondary' | 'outline' | 'ghost' | 'danger'
export type ButtonSize = 'sm' | 'md' | 'lg'

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant
  size?: ButtonSize
  isLoading?: boolean
  loadingText?: string
  leftIcon?: ReactNode
}

const VARIANT_CLASSES: Record<ButtonVariant, string> = {
  primary:
    'bg-merqo-700 text-white hover:bg-merqo-800 active:bg-merqo-900 border-transparent shadow-xs',
  secondary:
    'bg-slate-800 text-white hover:bg-slate-900 active:bg-slate-950 border-transparent shadow-xs',
  outline:
    'bg-white text-slate-700 border-slate-300 hover:bg-slate-50 hover:border-slate-400 shadow-xs',
  ghost: 'bg-transparent text-slate-700 border-transparent hover:bg-slate-100',
  danger:
    'bg-rose-700 text-white hover:bg-rose-800 active:bg-rose-900 border-transparent shadow-xs',
}

const SIZE_CLASSES: Record<ButtonSize, string> = {
  sm: 'h-8 px-3 text-xs gap-1.5 rounded-md',
  md: 'h-9 px-4 text-sm gap-2 rounded-md',
  lg: 'h-11 px-5 text-sm gap-2 rounded-lg',
}

/**
 * Accessible action button with consistent variants, sizes, and loading spinner.
 */
export function Button({
  variant = 'primary',
  size = 'md',
  isLoading = false,
  loadingText,
  leftIcon,
  disabled,
  className = '',
  type = 'button',
  children,
  ...rest
}: ButtonProps) {
  const isDisabled = disabled || isLoading

  return (
    <button
      type={type}
      disabled={isDisabled}
      aria-busy={isLoading || undefined}
      className={[
        'inline-flex items-center justify-center border font-medium transition-colors',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-merqo-500 focus-visible:ring-offset-2',
        'disabled:cursor-not-allowed disabled:opacity-55',
        VARIANT_CLASSES[variant],
        SIZE_CLASSES[size],
        className,
      ].join(' ')}
      {...rest}
    >
      {isLoading ? (
        <span
          aria-hidden="true"
          className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-current border-t-transparent"
        />
      ) : (
        leftIcon
      )}
      <span>{isLoading && loadingText ? loadingText : children}</span>
    </button>
  )
}
