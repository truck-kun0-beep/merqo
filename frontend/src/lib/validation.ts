import { z } from 'zod'

/**
 * Mirrors `com.merqo.catalog.domain.BarcodeRules`:
 * trimmed 1..64 chars, no whitespace/control characters, cannot start with `MQ-`.
 */
export function validateBarcodeText(raw: string): string | null {
  const trimmed = raw.trim()
  if (trimmed.length === 0) {
    return 'Barcode cannot be empty.'
  }
  if (trimmed.length > 64) {
    return 'Barcode cannot be longer than 64 characters.'
  }
  if (trimmed.toUpperCase().startsWith('MQ-')) {
    return 'The prefix MQ- is reserved for Merqo document numbers.'
  }
  if (
    /\s/.test(trimmed) ||
    [...trimmed].some((ch) => ch.charCodeAt(0) < 32 || ch.charCodeAt(0) === 127)
  ) {
    return 'Barcode may not contain spaces or control characters.'
  }
  return null
}

export const barcodeSchema = z
  .string()
  .trim()
  .superRefine((val, ctx) => {
    const err = validateBarcodeText(val)
    if (err) {
      ctx.addIssue({ code: 'custom', message: err })
    }
  })

export const optionalBarcodeSchema = z
  .string()
  .trim()
  .superRefine((val, ctx) => {
    if (val.length === 0) return
    const err = validateBarcodeText(val)
    if (err) {
      ctx.addIssue({ code: 'custom', message: err })
    }
  })

export const productFormSchema = z.object({
  productCode: z.string().trim().min(1, 'Product code is required.'),
  name: z.string().trim().min(1, 'Product name is required.'),
  categoryId: z.string().optional(),
  brandId: z.string().optional(),
  description: z.string().optional(),
  taxRatePercent: z
    .string()
    .trim()
    .refine((val) => val === '' || (!Number.isNaN(Number(val)) && Number(val) >= 0), {
      message: 'Tax rate must be 0 or greater.',
    }),
})

export const variantFormSchema = z.object({
  sku: z.string().trim(),
  color: z.string().trim(),
  size: z.string().trim(),
  otherAttribute: z.string().trim(),
  sellingPrice: z
    .string()
    .trim()
    .min(1, 'Selling price is required.')
    .refine((val) => !Number.isNaN(Number(val)) && Number(val) >= 0, {
      message: 'Selling price must not be negative.',
    }),
  barcode: optionalBarcodeSchema,
})

export const adjustmentFormSchema = z
  .object({
    locationId: z.string().min(1, 'Location is required.'),
    reason: z.enum(['DAMAGED', 'LOST', 'FOUND', 'DATA_CORRECTION', 'OTHER']),
    note: z.string().trim(),
    lines: z
      .array(
        z.object({
          variantId: z.string().min(1, 'Variant is required.'),
          quantityDelta: z
            .number()
            .int('Quantity must be a whole number.')
            .refine((n) => n !== 0, 'Quantity change cannot be 0.'),
        }),
      )
      .min(1, 'At least one adjustment line is required.'),
  })
  .refine((data) => data.reason !== 'OTHER' || data.note.trim().length > 0, {
    path: ['note'],
    message: 'A note is required when the reason is OTHER.',
  })

export const policyFormSchema = z.object({
  reorderPoint: z.number().int('Must be a whole number.').min(0, 'Must be 0 or more.'),
  targetStock: z.number().int('Must be a whole number.').min(0, 'Must be 0 or more.'),
  onlineSafetyStock: z.number().int('Must be a whole number.').min(0, 'Must be 0 or more.'),
})

export const promotionFormSchema = z
  .object({
    name: z.string().trim().min(1, 'Promotion name is required.'),
    discountType: z.enum(['PERCENT', 'FIXED']),
    value: z
      .string()
      .trim()
      .min(1, 'Discount value is required.')
      .refine((v) => !Number.isNaN(Number(v)) && Number(v) > 0, {
        message: 'Discount value must be greater than 0.',
      }),
    scope: z.enum(['POS', 'ONLINE', 'BOTH']),
    allLocations: z.boolean(),
    locationIds: z.array(z.string()),
    variantIds: z.array(z.string()).min(1, 'Select at least one product, category, or brand.'),
    startsAt: z.string().optional(),
    endsAt: z.string().optional(),
    note: z.string().optional(),
  })
  .refine((data) => data.discountType !== 'PERCENT' || Number(data.value) <= 100, {
    path: ['value'],
    message: 'Percentage discount cannot exceed 100%.',
  })
  .refine((data) => data.allLocations || data.locationIds.length > 0, {
    path: ['locationIds'],
    message: 'Select at least one location.',
  })
  .refine(
    (data) =>
      !data.startsAt ||
      !data.endsAt ||
      new Date(data.endsAt).getTime() > new Date(data.startsAt).getTime(),
    { path: ['endsAt'], message: 'End date must be after start date.' },
  )

export const transferRequestSchema = z
  .object({
    sourceLocationId: z.string().min(1, 'Source location is required.'),
    destinationLocationId: z.string().min(1, 'Destination location is required.'),
    note: z.string().optional(),
    lines: z
      .array(
        z.object({
          variantId: z.string().min(1, 'Variant is required.'),
          quantity: z
            .number()
            .int('Must be a whole number.')
            .min(1, 'Quantity must be at least 1.'),
        }),
      )
      .min(1, 'At least one line is required.'),
  })
  .refine((data) => data.sourceLocationId !== data.destinationLocationId, {
    path: ['destinationLocationId'],
    message: 'Source and destination must be different locations.',
  })

/** Mirrors `com.merqo.orders.domain.PhoneNumber`. Returns canonical `01[3-9]XXXXXXXX` or `null`. */
export function normalizeBdPhone(raw: string): string | null {
  const trimmed = raw.trim()
  if (!trimmed) return null
  let stripped = trimmed.replace(/[\s\-()]/g, '')
  if (stripped.startsWith('+880')) {
    stripped = '0' + stripped.slice(4)
  } else if (stripped.startsWith('880')) {
    stripped = '0' + stripped.slice(3)
  }
  return /^01[3-9]\d{8}$/.test(stripped) ? stripped : null
}

export const bdPhoneSchema = z
  .string()
  .trim()
  .min(1, 'Phone number is required.')
  .refine((val) => normalizeBdPhone(val) !== null, {
    message: 'Enter a valid Bangladeshi mobile number (e.g. 01712345678).',
  })

export const callLogFormSchema = z
  .object({
    result: z.enum(['ANSWERED', 'NO_ANSWER', 'BUSY', 'WRONG_NUMBER', 'CALL_BACK_REQUESTED']),
    note: z.string().trim(),
    followUpAt: z.string().optional(),
  })
  .refine((data) => data.result === 'ANSWERED' || data.note.trim().length > 0, {
    path: ['note'],
    message: 'A note is required when the customer does not answer normally.',
  })

export const declineOrderFormSchema = z
  .object({
    reason: z.enum([
      'CUSTOMER_UNREACHABLE',
      'CUSTOMER_DECLINED',
      'OUT_OF_STOCK',
      'INVALID_ADDRESS',
      'SUSPECTED_FAKE',
      'OTHER',
    ]),
    note: z.string().trim(),
  })
  .refine((data) => data.reason !== 'OTHER' || data.note.trim().length > 0, {
    path: ['note'],
    message: 'Please explain the decline reason when choosing OTHER.',
  })

export const cancelOrderFormSchema = z
  .object({
    reason: z.enum(['CUSTOMER_REQUESTED', 'STOCK_PROBLEM', 'ADDRESS_PROBLEM', 'OTHER']),
    note: z.string().trim(),
  })
  .refine((data) => data.reason !== 'OTHER' || data.note.trim().length > 0, {
    path: ['note'],
    message: 'Please explain the cancellation reason when choosing OTHER.',
  })

export const editCustomerFormSchema = z.object({
  customerName: z.string().trim().min(1, 'Customer name is required.'),
  phone: bdPhoneSchema,
  address: z.string().trim().min(1, 'Delivery address is required.'),
  district: z.string().trim().optional(),
  note: z.string().trim().optional(),
})

export const manualOrderFormSchema = z.object({
  customerName: z.string().trim().min(1, 'Customer name is required.'),
  phone: bdPhoneSchema,
  address: z.string().trim().min(1, 'Delivery address is required.'),
  district: z.string().trim().optional(),
  note: z.string().trim().optional(),
  shippingFee: z
    .string()
    .trim()
    .refine((v) => v === '' || (!Number.isNaN(Number(v)) && Number(v) >= 0), {
      message: 'Shipping fee must not be negative.',
    }),
  discountTotal: z
    .string()
    .trim()
    .refine((v) => v === '' || (!Number.isNaN(Number(v)) && Number(v) >= 0), {
      message: 'Discount must not be negative.',
    }),
  paymentMode: z.enum(['COD', 'FULL_PREPAID', 'ADVANCE_COD']),
  advanceAmount: z.string().trim().optional(),
  lines: z
    .array(
      z.object({
        variantId: z.string().min(1, 'Select a variant.'),
        quantity: z.number().int().min(1, 'Quantity must be at least 1.'),
      }),
    )
    .min(1, 'Add at least one item.')
    .max(50, 'Orders cannot exceed 50 lines.'),
})

/** Mirrors `com.merqo.payments.domain.CardNumberGuard`. Detects PAN-like 13-19 digit strings. */
export function containsCardNumberLikeText(raw: string): boolean {
  if (!raw || !raw.trim()) return false
  return /(?:^|\D)\d(?:[\s-]?\d){12,18}(?!\d)/.test(raw)
}

export function validateOnlineMethodReference(
  method: 'BKASH' | 'NAGAD' | 'CARD' | 'BANK',
  rawReference: string,
): string | null {
  const ref = rawReference.trim().toUpperCase()
  if (containsCardNumberLikeText(rawReference)) {
    return 'Do not enter a full card number. Enter only the last 4 digits or approval code.'
  }
  if (!ref) {
    return 'Payment reference is required.'
  }
  if (method === 'BKASH' || method === 'NAGAD') {
    if (!/^[A-Z0-9]{6,20}$/.test(ref)) {
      return 'TrxID must be 6 to 20 letters/digits with no spaces.'
    }
    return null
  }
  if (method === 'CARD') {
    if (!/^[A-Z0-9]{4,12}$/.test(ref)) {
      return 'Card reference must be 4 to 12 letters/digits (last 4 or approval code).'
    }
    return null
  }
  if (!/^(?=.*[A-Z0-9])[A-Z0-9-]{4,30}$/.test(ref)) {
    return 'Bank reference must be 4 to 30 letters, digits, or hyphens.'
  }
  return null
}

export const recordOrderPaymentSchema = z
  .object({
    method: z.enum(['BKASH', 'NAGAD', 'CARD', 'BANK']),
    amount: z
      .string()
      .trim()
      .refine((v) => !Number.isNaN(Number(v)) && Number(v) > 0, {
        message: 'Payment amount must be greater than zero.',
      }),
    reference: z.string().trim(),
    payerNote: z.string().trim().optional(),
  })
  .superRefine((data, ctx) => {
    const refErr = validateOnlineMethodReference(data.method, data.reference)
    if (refErr) {
      ctx.addIssue({ code: 'custom', path: ['reference'], message: refErr })
    }
    if (data.payerNote && containsCardNumberLikeText(data.payerNote)) {
      ctx.addIssue({
        code: 'custom',
        path: ['payerNote'],
        message: 'Do not enter a full card number in notes.',
      })
    }
  })

export const verifyPaymentFormSchema = z
  .object({
    decision: z.enum(['VERIFIED', 'REJECTED']),
    confirmedAmount: z.string().trim().optional(),
    evidence: z.string().trim(),
  })
  .superRefine((data, ctx) => {
    if (data.decision === 'VERIFIED') {
      if (!data.confirmedAmount || Number.isNaN(Number(data.confirmedAmount)) || Number(data.confirmedAmount) <= 0) {
        ctx.addIssue({
          code: 'custom',
          path: ['confirmedAmount'],
          message: 'Enter the confirmed payment amount.',
        })
      }
    } else if (!data.evidence.trim()) {
      ctx.addIssue({
        code: 'custom',
        path: ['evidence'],
        message: 'Rejection reason or evidence is required when rejecting a payment.',
      })
    }
    if (containsCardNumberLikeText(data.evidence)) {
      ctx.addIssue({
        code: 'custom',
        path: ['evidence'],
        message: 'Do not enter a full card number in evidence.',
      })
    }
  })

export const refundRequestFormSchema = z
  .object({
    amount: z
      .string()
      .trim()
      .refine((v) => !Number.isNaN(Number(v)) && Number(v) > 0, {
        message: 'Refund amount must be greater than zero.',
      }),
    reason: z.string().trim().min(1, 'Refund reason is required.'),
    note: z.string().trim().optional(),
  })
  .refine((d) => !d.note || !containsCardNumberLikeText(d.note), {
    path: ['note'],
    message: 'Do not enter a full card number in notes.',
  })

export const refundPayFormSchema = z
  .object({
    payoutMethod: z.enum(['BKASH', 'NAGAD', 'CARD', 'BANK', 'CASH']),
    payoutReference: z.string().trim(),
    note: z.string().trim(),
  })
  .superRefine((data, ctx) => {
    if (data.payoutMethod === 'CASH') {
      if (data.payoutReference.length > 0) {
        ctx.addIssue({
          code: 'custom',
          path: ['payoutReference'],
          message: 'Leave payout reference blank for cash refunds.',
        })
      }
      if (!data.note) {
        ctx.addIssue({
          code: 'custom',
          path: ['note'],
          message: 'An explanatory note is required for cash refund payouts.',
        })
      }
    } else {
      const refErr = validateOnlineMethodReference(data.payoutMethod, data.payoutReference)
      if (refErr) {
        ctx.addIssue({ code: 'custom', path: ['payoutReference'], message: refErr })
      }
    }
    if (data.note && containsCardNumberLikeText(data.note)) {
      ctx.addIssue({
        code: 'custom',
        path: ['note'],
        message: 'Do not enter a full card number in notes.',
      })
    }
  })

export const codCollectionFormSchema = z.object({
  courier: z.string().trim().min(1, 'Courier name is required.'),
  amount: z
    .string()
    .trim()
    .refine((v) => !Number.isNaN(Number(v)) && Number(v) > 0, {
      message: 'Collected amount must be greater than zero.',
    }),
  collectedOn: z.string().trim().min(1, 'Collection date is required.'),
  note: z.string().trim().optional(),
})
