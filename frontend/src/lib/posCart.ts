import type { PaymentMethod, PosDiscountType } from '../api/models'
import { divideHalfUp, formatMinorUnits, parseMinorUnits, percentOfMinor } from './money'

export interface PosCartLine {
  variantId: string
  productName: string
  sku: string
  variantLabel: string
  unitPrice: string
  promotionName: string | null
  promotionDiscount: string
  taxRatePercent: string
  taxInclusive: boolean
  availableStock: number
  quantity: number
  manualDiscountType: PosDiscountType | null
  manualDiscountValue: string
}

export interface ComputedCartLine {
  variantId: string
  grossMinor: number
  promoDiscountMinor: number
  manualDiscountMinor: number
  netMinor: number
  taxMinor: number
  lineTotalMinor: number
}

export interface ComputedCartTotals {
  lines: ComputedCartLine[]
  linesSubtotalMinor: number
  billDiscountMinor: number
  subtotalMinor: number
  promoDiscountTotalMinor: number
  manualDiscountTotalMinor: number
  discountTotalMinor: number
  taxTotalMinor: number
  totalMinor: number
  effectiveManualDiscountPercent: number
}

/**
 * Computes exact integer minor-unit line totals and bill totals for the POS cart.
 */
export function calculateCartTotals(
  lines: PosCartLine[],
  billDiscountType: PosDiscountType | null,
  billDiscountValue: string,
): ComputedCartTotals {
  let linesSubtotalMinor = 0
  let promoDiscountTotalMinor = 0
  let manualLineDiscountTotalMinor = 0
  let rawTaxTotalMinor = 0
  let totalAfterPromoMinor = 0

  const computedLines: ComputedCartLine[] = lines.map((line) => {
    const qty = Math.max(1, Math.trunc(line.quantity))
    const unitMinor = parseMinorUnits(line.unitPrice)
    const unitPromoMinor = parseMinorUnits(line.promotionDiscount)

    const grossMinor = unitMinor * qty
    const promoDiscountMinor = Math.min(grossMinor, unitPromoMinor * qty)
    const afterPromoMinor = Math.max(0, grossMinor - promoDiscountMinor)

    let manualDiscountMinor = 0
    if (line.manualDiscountType === 'PERCENT' && line.manualDiscountValue.trim() !== '') {
      manualDiscountMinor = percentOfMinor(afterPromoMinor, line.manualDiscountValue)
    } else if (line.manualDiscountType === 'FIXED' && line.manualDiscountValue.trim() !== '') {
      manualDiscountMinor = parseMinorUnits(line.manualDiscountValue)
    }
    manualDiscountMinor = Math.min(afterPromoMinor, Math.max(0, manualDiscountMinor))

    const netMinor = afterPromoMinor - manualDiscountMinor
    const taxRateBps = parseMinorUnits(line.taxRatePercent) // 5% -> 500
    let taxMinor = 0
    if (taxRateBps > 0 && netMinor > 0) {
      if (line.taxInclusive) {
        const netExcl = divideHalfUp(netMinor * 10_000, 10_000 + taxRateBps)
        taxMinor = netMinor - netExcl
      } else {
        taxMinor = divideHalfUp(netMinor * taxRateBps, 10_000)
      }
    }

    const lineTotalMinor = line.taxInclusive ? netMinor : netMinor + taxMinor

    totalAfterPromoMinor += afterPromoMinor
    linesSubtotalMinor += netMinor
    promoDiscountTotalMinor += promoDiscountMinor
    manualLineDiscountTotalMinor += manualDiscountMinor
    rawTaxTotalMinor += taxMinor

    return {
      variantId: line.variantId,
      grossMinor,
      promoDiscountMinor,
      manualDiscountMinor,
      netMinor,
      taxMinor,
      lineTotalMinor,
    }
  })

  let billDiscountMinor = 0
  if (billDiscountType === 'PERCENT' && billDiscountValue.trim() !== '') {
    billDiscountMinor = percentOfMinor(linesSubtotalMinor, billDiscountValue)
  } else if (billDiscountType === 'FIXED' && billDiscountValue.trim() !== '') {
    billDiscountMinor = parseMinorUnits(billDiscountValue)
  }
  billDiscountMinor = Math.min(linesSubtotalMinor, Math.max(0, billDiscountMinor))

  const subtotalMinor = linesSubtotalMinor - billDiscountMinor
  const taxTotalMinor =
    linesSubtotalMinor > 0
      ? divideHalfUp(rawTaxTotalMinor * subtotalMinor, linesSubtotalMinor)
      : 0

  const allInclusive = lines.length > 0 && lines.every((l) => l.taxInclusive)
  const totalMinor = allInclusive ? subtotalMinor : subtotalMinor + taxTotalMinor

  const manualDiscountTotalMinor = manualLineDiscountTotalMinor + billDiscountMinor
  const discountTotalMinor = promoDiscountTotalMinor + manualDiscountTotalMinor

  const effectiveManualDiscountPercent =
    totalAfterPromoMinor > 0
      ? Number(((manualDiscountTotalMinor * 100) / totalAfterPromoMinor).toFixed(2))
      : 0

  return {
    lines: computedLines,
    linesSubtotalMinor,
    billDiscountMinor,
    subtotalMinor,
    promoDiscountTotalMinor,
    manualDiscountTotalMinor,
    discountTotalMinor,
    taxTotalMinor,
    totalMinor,
    effectiveManualDiscountPercent,
  }
}

export interface PaymentValidationResult {
  valid: boolean
  error?: string
  changeMinor: number
}

/**
 * Rejects full card numbers (PANs) while allowing last 4 digits or approval codes.
 */
export function isRejectedFullCardNumber(raw: string): boolean {
  const trimmed = raw.trim()
  if (!trimmed) {
    return false
  }
  const digits = trimmed.replace(/\D/g, '')
  // Standard PANs are 12-19 digits; also reject any long numeric-like string >= 8 digits
  return digits.length >= 8
}

/**
 * Validates one-method payment input before sale submission.
 */
export function validatePayment(
  method: PaymentMethod,
  totalMinor: number,
  input: { amountGiven: string; trxId: string; cardReference: string },
): PaymentValidationResult {
  if (method === 'CASH') {
    if (input.amountGiven.trim() === '') {
      return { valid: false, error: 'Enter the cash amount received.', changeMinor: 0 }
    }
    const givenMinor = parseMinorUnits(input.amountGiven)
    if (givenMinor < totalMinor) {
      return {
        valid: false,
        error: `Cash received (${formatMinorUnits(givenMinor)}) is less than total (${formatMinorUnits(totalMinor)}).`,
        changeMinor: 0,
      }
    }
    return { valid: true, changeMinor: givenMinor - totalMinor }
  }

  if (method === 'BKASH' || method === 'NAGAD') {
    const trimmed = input.trxId.trim()
    if (trimmed.length < 4) {
      return {
        valid: false,
        error: `${method === 'BKASH' ? 'bKash' : 'Nagad'} TrxID is required (at least 4 characters).`,
        changeMinor: 0,
      }
    }
    return { valid: true, changeMinor: 0 }
  }

  // CARD
  const ref = input.cardReference.trim()
  if (isRejectedFullCardNumber(ref)) {
    return {
      valid: false,
      error: 'Never enter a full card number. Enter only the last 4 digits or approval code.',
      changeMinor: 0,
    }
  }
  if (!ref) {
    return {
      valid: false,
      error: 'Enter the card approval code or last 4 digits.',
      changeMinor: 0,
    }
  }
  return { valid: true, changeMinor: 0 }
}
