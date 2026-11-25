/**
 * Exact integer minor-unit (paisa) money arithmetic.
 *
 * Avoids IEEE-754 floating-point rounding drift in cart, discount, tax, and
 * cash reconciliation calculations.
 */

/**
 * Parses a decimal string or number into integer minor units (1 unit = 100 minor units).
 */
export function parseMinorUnits(value: string | number | null | undefined): number {
  if (value === null || value === undefined || value === '') {
    return 0
  }
  const raw = typeof value === 'number' ? value.toFixed(2) : value.trim()
  if (!raw) {
    return 0
  }
  const match = raw.match(/^(-?)(\d+)(?:\.(\d+))?$/)
  if (!match) {
    return 0
  }
  const sign = match[1] === '-' ? -1 : 1
  const whole = Number.parseInt(match[2], 10)
  const fracRaw = match[3] ?? ''
  const padded = `${fracRaw}000`
  const twoDigits = Number.parseInt(padded.slice(0, 2), 10)
  const thirdDigit = Number.parseInt(padded[2], 10)
  const roundedFrac = thirdDigit >= 5 ? twoDigits + 1 : twoDigits
  return sign * (whole * 100 + roundedFrac)
}

/**
 * Formats integer minor units as a 2-decimal string (e.g. `125000` -> `"1250.00"`).
 */
export function formatMinorUnits(minor: number): string {
  const safe = Math.round(minor)
  const sign = safe < 0 ? '-' : ''
  const abs = Math.abs(safe)
  const whole = Math.floor(abs / 100)
  const frac = String(abs % 100).padStart(2, '0')
  return `${sign}${whole}.${frac}`
}

/**
 * Integer half-up division: rounds `numerator / denominator` to the nearest integer,
 * with `.5` rounded away from zero.
 */
export function divideHalfUp(numerator: number, denominator: number): number {
  if (denominator === 0) {
    return 0
  }
  const sign = numerator < 0 !== denominator < 0 ? -1 : 1
  const num = Math.abs(numerator)
  const den = Math.abs(denominator)
  const quotient = Math.floor(num / den)
  const remainder = num % den
  const rounded = remainder * 2 >= den ? quotient + 1 : quotient
  return sign * rounded
}

/**
 * Computes `percentStr`% of `amountMinor` using exact integer arithmetic (basis points * 100).
 * Supports up to 2 decimal places on percentages (e.g. `"7.5"` or `"12.25"`).
 */
export function percentOfMinor(amountMinor: number, percentStr: string): number {
  const bps = parseMinorUnits(percentStr) // 7.5% -> 750 (out of 10,000)
  if (bps <= 0 || amountMinor <= 0) {
    return 0
  }
  return divideHalfUp(amountMinor * bps, 10_000)
}
