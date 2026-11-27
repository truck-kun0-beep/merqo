import { describe, expect, it } from 'vitest'
import { divideHalfUp, formatMinorUnits, parseMinorUnits, percentOfMinor } from './money'
import { calculateCartTotals, validatePayment, type PosCartLine } from './posCart'

function makeLine(overrides: Partial<PosCartLine> = {}): PosCartLine {
  return {
    variantId: 'v-1',
    productName: 'Cotton Tee',
    sku: 'TEE-01',
    variantLabel: 'Black / M',
    unitPrice: '19.99',
    promotionName: null,
    promotionDiscount: '0.00',
    taxRatePercent: '7.5',
    taxInclusive: false,
    availableStock: 10,
    quantity: 1,
    manualDiscountType: null,
    manualDiscountValue: '',
    ...overrides,
  }
}

describe('Integer minor-unit money & cart math', () => {
  it('parses and formats decimals without floating-point drift', () => {
    expect(parseMinorUnits('0.10') + parseMinorUnits('0.20')).toBe(30)
    expect(formatMinorUnits(30)).toBe('0.30')
    expect(parseMinorUnits('1250.555')).toBe(125056)
    expect(divideHalfUp(15, 2)).toBe(8)
    expect(percentOfMinor(155, '10')).toBe(16) // 15.5 rounds half-up to 16 minor units
  })

  it('computes line promo, manual line discount, bill discount, and proportional tax accurately', () => {
    const line1 = makeLine({
      quantity: 3,
      unitPrice: '333.33', // 999.99
      promotionDiscount: '33.33', // 99.99 promo -> 900.00 after promo
      manualDiscountType: 'PERCENT',
      manualDiscountValue: '10', // 90.00 manual -> 810.00 net
      taxRatePercent: '5', // 40.50 tax
    })

    const totals = calculateCartTotals([line1], 'FIXED', '10.00')
    expect(totals.promoDiscountTotalMinor).toBe(9999)
    expect(totals.manualDiscountTotalMinor).toBe(10000) // 90.00 line + 10.00 bill
    expect(totals.subtotalMinor).toBe(80000) // 800.00
    expect(totals.taxTotalMinor).toBe(4000) // 40.50 * 800 / 810 = 40.00
    expect(totals.totalMinor).toBe(84000) // 840.00
    expect(totals.effectiveManualDiscountPercent).toBe(11.11) // 100 / 900 = 11.11%
  })

  it('validates single-method payments and rejects full card numbers', () => {
    const cashFail = validatePayment('CASH', 50000, {
      tendered: '400.00',
      reference: '',
    })
    expect(cashFail.valid).toBe(false)

    const cashOk = validatePayment('CASH', 50000, {
      tendered: '600.00',
      reference: '',
    })
    expect(cashOk.valid).toBe(true)
    expect(cashOk.changeMinor).toBe(10000)

    const bkashFail = validatePayment('BKASH', 50000, {
      tendered: '',
      reference: '12',
    })
    expect(bkashFail.valid).toBe(false)
    expect(bkashFail.error).toMatch(/TrxID is required/i)

    const fullCardReject = validatePayment('CARD', 50000, {
      tendered: '',
      reference: '4111111111111111',
    })
    expect(fullCardReject.valid).toBe(false)
    expect(fullCardReject.error).toMatch(/Never enter a full card number/i)

    const last4Ok = validatePayment('CARD', 50000, {
      tendered: '',
      reference: '4242',
    })
    expect(last4Ok.valid).toBe(true)
  })
})
