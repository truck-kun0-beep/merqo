import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import type { PriceScope } from '../api/catalog'
import { ApiError } from '../api/types'

export interface ScanContext {
  scope?: PriceScope
  locationId?: string
}

export interface UseScanInputOptions<T> {
  onScan: (code: string, context: ScanContext) => Promise<T>
  onSuccess?: (result: T, code: string) => void
  scope?: PriceScope
  locationId?: string
  autoFocus?: boolean
  restoreFocusWhen?: boolean
}

export interface UseScanInputResult {
  value: string
  setValue: (next: string) => void
  isScanning: boolean
  error: string | null
  clearError: () => void
  inputRef: React.RefObject<HTMLInputElement | null>
  focus: () => void
  handleSubmit: (event?: FormEvent) => Promise<void>
}

/**
 * Keyboard-wedge barcode scanner input hook.
 *
 * Focuses on mount, trims input, submits on Enter, clears after success,
 * preserves focus on not-found / error, and restores focus when dialogs close.
 */
export function useScanInput<T>({
  onScan,
  onSuccess,
  scope,
  locationId,
  autoFocus = true,
  restoreFocusWhen = true,
}: UseScanInputOptions<T>): UseScanInputResult {
  const [value, setValue] = useState('')
  const [isScanning, setIsScanning] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const inputRef = useRef<HTMLInputElement | null>(null)

  const focus = useCallback(() => {
    const el = inputRef.current
    if (el && !el.disabled) {
      el.focus()
    }
  }, [])

  useEffect(() => {
    if (autoFocus) {
      focus()
    }
  }, [autoFocus, focus])

  useEffect(() => {
    if (restoreFocusWhen && autoFocus) {
      focus()
    }
  }, [restoreFocusWhen, autoFocus, focus])

  useEffect(() => {
    if (!autoFocus) {
      return
    }
    function handleDialogClosed() {
      setTimeout(() => {
        focus()
      }, 0)
    }
    window.addEventListener('merqo:dialog-closed', handleDialogClosed)
    return () => window.removeEventListener('merqo:dialog-closed', handleDialogClosed)
  }, [autoFocus, focus])

  const clearError = useCallback(() => setError(null), [])

  const handleSubmit = useCallback(
    async (event?: FormEvent) => {
      event?.preventDefault()
      const trimmed = value.trim()
      if (!trimmed || isScanning) {
        focus()
        return
      }

      setError(null)
      setIsScanning(true)
      try {
        const result = await onScan(trimmed, { scope, locationId })
        setValue('')
        onSuccess?.(result, trimmed)
      } catch (caught) {
        if (
          caught instanceof ApiError &&
          (caught.errorCode === 'BARCODE_NOT_FOUND' ||
            caught.errorCode === 'NOT_FOUND' ||
            caught.status === 404)
        ) {
          setError(`Not found: no active item matches barcode "${trimmed}".`)
        } else if (caught instanceof Error) {
          setError(caught.message)
        } else {
          setError(`Not found: "${trimmed}".`)
        }
      } finally {
        setIsScanning(false)
        focus()
      }
    },
    [value, isScanning, onScan, scope, locationId, onSuccess, focus],
  )

  return {
    value,
    setValue,
    isScanning,
    error,
    clearError,
    inputRef,
    focus,
    handleSubmit,
  }
}
