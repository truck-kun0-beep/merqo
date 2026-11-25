import { ApiError, BackendUnreachableError } from '../api/types'

/**
 * Formats an amount in BDT with thousands separators, e.g. `৳ 12,500.00`.
 *
 * Returns an empty string if the amount is absent (`null` or `undefined`),
 * so permission-hidden cost/margin/value fields never appear as `0` or `N/A`.
 */
export function formatMoney(amount: number | string | null | undefined): string {
  if (amount === null || amount === undefined || amount === '') {
    return ''
  }
  const numeric = typeof amount === 'string' ? Number(amount) : amount
  if (Number.isNaN(numeric)) {
    return ''
  }
  const sign = numeric < 0 ? '-' : ''
  const formatted = new Intl.NumberFormat('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(Math.abs(numeric))
  return `${sign}৳ ${formatted}`
}

/**
 * Formats a whole-number quantity with thousands separators.
 */
export function formatQuantity(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return ''
  }
  return new Intl.NumberFormat('en-US', {
    maximumFractionDigits: 0,
  }).format(Math.round(value))
}

/**
 * Formats a business date as `20 Nov 2026` in Asia/Dhaka.
 */
export function formatDate(value: string | Date | null | undefined): string {
  if (!value) {
    return ''
  }
  const date = typeof value === 'string' ? new Date(value) : value
  if (Number.isNaN(date.getTime())) {
    return ''
  }
  return new Intl.DateTimeFormat('en-GB', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    timeZone: 'Asia/Dhaka',
  }).format(date)
}

/**
 * Formats a timestamp in the shop's time zone (`Asia/Dhaka`).
 */
export function formatDateTime(value: string | Date | null | undefined): string {
  if (!value) {
    return ''
  }
  const date = typeof value === 'string' ? new Date(value) : value
  if (Number.isNaN(date.getTime())) {
    return ''
  }
  return new Intl.DateTimeFormat('en-GB', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
    timeZone: 'Asia/Dhaka',
  }).format(date)
}

export interface FormattedError {
  message: string
  errorCode?: string
  correlationId?: string
  fieldErrors?: Record<string, string>
}

/**
 * Extracts a friendly message and RFC 7807 metadata from any thrown error.
 */
export function readErrorDetails(caught: unknown): FormattedError {
  if (caught instanceof ApiError) {
    return {
      message: caught.message,
      errorCode: caught.errorCode,
      correlationId: caught.correlationId,
      fieldErrors: caught.fieldErrors,
    }
  }
  if (caught instanceof BackendUnreachableError) {
    return {
      message: 'The server could not be reached. It may still be waking up.',
    }
  }
  if (caught instanceof Error) {
    return { message: caught.message }
  }
  return { message: 'Something went wrong. Try again.' }
}
