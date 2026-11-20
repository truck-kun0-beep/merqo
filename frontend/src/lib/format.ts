/**
 * Formats an amount the way staff expect to see it.
 *
 * @param amount the amount as a plain number
 * @returns the formatted amount, for example `৳ 1,250.50`
 */
export function formatMoney(amount: number): string {
  return new Intl.NumberFormat('en-BD', {
    style: 'currency',
    currency: 'BDT',
    currencyDisplay: 'symbol',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(amount)
}

/**
 * Formats a business date the way staff expect to see it.
 *
 * @param value the date to format
 * @returns the formatted date, for example `20 Nov 2026`
 */
export function formatDate(value: string | Date): string {
  const date = typeof value === 'string' ? new Date(value) : value
  return new Intl.DateTimeFormat('en-GB', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    timeZone: 'Asia/Dhaka',
  }).format(date)
}

/**
 * Formats a timestamp in the shop's own time zone.
 *
 * @param value the timestamp to format
 * @returns the formatted timestamp
 */
export function formatDateTime(value: string | Date): string {
  const date = typeof value === 'string' ? new Date(value) : value
  return new Intl.DateTimeFormat('en-GB', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
    timeZone: 'Asia/Dhaka',
  }).format(date)
}
