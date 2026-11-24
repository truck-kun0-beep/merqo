import { formatMoney } from '../../lib/format'

export interface MoneyTextProps {
  amount: number | string | null | undefined
  className?: string
}

/**
 * Formats BDT currency (`৳ 12,500.00`). Renders nothing when the value is absent
 * (for example, cost or stock-value fields hidden from users without permission).
 */
export function MoneyText({ amount, className = '' }: MoneyTextProps) {
  const formatted = formatMoney(amount)
  if (!formatted) {
    return null
  }
  return <span className={`tabular-nums whitespace-nowrap ${className}`}>{formatted}</span>
}
