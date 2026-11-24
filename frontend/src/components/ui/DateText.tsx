import { formatDate, formatDateTime } from '../../lib/format'

export interface DateTextProps {
  value: string | Date | null | undefined
  withTime?: boolean
  fallback?: string
  className?: string
}

/**
 * Formats a business date (`20 Nov 2026`) or timestamp in Asia/Dhaka.
 */
export function DateText({ value, withTime = false, fallback = '', className = '' }: DateTextProps) {
  const formatted = withTime ? formatDateTime(value) : formatDate(value)
  if (!formatted) {
    return fallback ? <span className={className}>{fallback}</span> : null
  }
  return <span className={`whitespace-nowrap ${className}`}>{formatted}</span>
}
