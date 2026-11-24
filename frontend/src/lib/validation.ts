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
  if (/\s/.test(trimmed) || [...trimmed].some((ch) => ch.charCodeAt(0) < 32 || ch.charCodeAt(0) === 127)) {
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
  .refine(
    (data) => data.discountType !== 'PERCENT' || Number(data.value) <= 100,
    { path: ['value'], message: 'Percentage discount cannot exceed 100%.' },
  )
  .refine(
    (data) => data.allLocations || data.locationIds.length > 0,
    { path: ['locationIds'], message: 'Select at least one location.' },
  )
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
          quantity: z.number().int('Must be a whole number.').min(1, 'Quantity must be at least 1.'),
        }),
      )
      .min(1, 'At least one line is required.'),
  })
  .refine((data) => data.sourceLocationId !== data.destinationLocationId, {
    path: ['destinationLocationId'],
    message: 'Source and destination must be different locations.',
  })
