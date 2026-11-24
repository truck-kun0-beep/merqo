import { useId } from 'react'
import type { PriceScope } from '../api/catalog'
import { useScanInput, type ScanContext } from '../hooks/useScanInput'
import { Button, Input } from './ui'

export interface ScanInputProps<T> {
  onScan: (code: string, context: ScanContext) => Promise<T>
  onSuccess?: (result: T, code: string) => void
  scope?: PriceScope
  locationId?: string
  label?: string
  placeholder?: string
  submitLabel?: string
  autoFocus?: boolean
  restoreFocusWhen?: boolean
  disabled?: boolean
  className?: string
}

/**
 * Generic keyboard-wedge barcode scan input reusable across Catalog, Inventory, and POS.
 */
export function ScanInput<T>({
  onScan,
  onSuccess,
  scope,
  locationId,
  label = 'Scan or type a barcode',
  placeholder = 'Scan barcode and press Enter...',
  submitLabel = 'Find',
  autoFocus = true,
  restoreFocusWhen = true,
  disabled = false,
  className = '',
}: ScanInputProps<T>) {
  const inputId = useId()
  const errorId = useId()

  const { value, setValue, isScanning, error, clearError, inputRef, handleSubmit } =
    useScanInput({
      onScan,
      onSuccess,
      scope,
      locationId,
      autoFocus,
      restoreFocusWhen,
    })

  return (
    <form onSubmit={handleSubmit} className={`space-y-1.5 ${className}`} noValidate>
      <label htmlFor={inputId} className="block text-xs font-medium text-slate-700">
        {label}
      </label>
      <div className="flex items-center gap-2">
        <Input
          ref={inputRef}
          id={inputId}
          type="text"
          value={value}
          disabled={disabled}
          readOnly={isScanning}
          invalid={Boolean(error)}
          aria-describedby={error ? errorId : undefined}
          placeholder={placeholder}
          autoComplete="off"
          onChange={(event) => {
            if (error) {
              clearError()
            }
            setValue(event.target.value)
          }}
        />
        <Button
          type="submit"
          variant="outline"
          disabled={disabled}
          isLoading={isScanning}
          loadingText="Finding..."
        >
          {submitLabel}
        </Button>
      </div>
      {error ? (
        <p id={errorId} role="alert" className="text-xs font-medium text-rose-700">
          {error}
        </p>
      ) : null}
    </form>
  )
}
