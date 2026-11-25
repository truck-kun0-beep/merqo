import type { CashSessionSummary, PosLookupResponse, SaleDetail } from '../api/models'

export interface MockVariantRecord {
  variantId: string
  productId: string
  productName: string
  sku: string
  variantLabel: string
  barcodes: string[]
  listPrice: string
  costPrice: string
  promotionName: string | null
  promotionDiscount: string
  taxRatePercent: string
  taxInclusive: boolean
  stockByLocation: Record<string, number>
}

const PRODUCT_TEMPLATES: Array<{
  code: string
  name: string
  colors: string[]
  sizes: string[]
  price: string
  cost: string
  promoName: string | null
  promoDisc: string
  tax: string
}> = [
  {
    code: 'TEE01',
    name: 'Cotton Crew T-Shirt',
    colors: ['Black', 'White'],
    sizes: ['M', 'L'],
    price: '750.00',
    cost: '420.00',
    promoName: 'Weekend 10% Off',
    promoDisc: '75.00',
    tax: '5',
  },
  {
    code: 'POL02',
    name: 'Pique Polo Shirt',
    colors: ['Navy', 'Olive'],
    sizes: ['M', 'L'],
    price: '1150.00',
    cost: '680.00',
    promoName: null,
    promoDisc: '0.00',
    tax: '5',
  },
  {
    code: 'CHN03',
    name: 'Slim Chino Trouser',
    colors: ['Khaki', 'Charcoal'],
    sizes: ['30', '32'],
    price: '1850.00',
    cost: '1050.00',
    promoName: null,
    promoDisc: '0.00',
    tax: '7.5',
  },
  {
    code: 'DNM04',
    name: 'Selvedge Denim Jean',
    colors: ['Indigo', 'Washed Black'],
    sizes: ['30', '32'],
    price: '2400.00',
    cost: '1400.00',
    promoName: 'Denim Days',
    promoDisc: '200.00',
    tax: '7.5',
  },
  {
    code: 'SHT05',
    name: 'Oxford Button-Down',
    colors: ['Sky Blue', 'White'],
    sizes: ['M', 'L'],
    price: '1650.00',
    cost: '950.00',
    promoName: null,
    promoDisc: '0.00',
    tax: '5',
  },
  {
    code: 'HOD06',
    name: 'Fleece Zip Hoodie',
    colors: ['Heather Grey', 'Black'],
    sizes: ['M', 'L'],
    price: '2200.00',
    cost: '1300.00',
    promoName: null,
    promoDisc: '0.00',
    tax: '7.5',
  },
  {
    code: 'JKT07',
    name: 'Twill Overshirt',
    colors: ['Olive', 'Tan'],
    sizes: ['M', 'L'],
    price: '2650.00',
    cost: '1600.00',
    promoName: null,
    promoDisc: '0.00',
    tax: '7.5',
  },
  {
    code: 'CAP08',
    name: 'Canvas Baseball Cap',
    colors: ['Sand', 'Black'],
    sizes: ['One Size', 'XL'],
    price: '450.00',
    cost: '210.00',
    promoName: 'Accessory Deal',
    promoDisc: '50.00',
    tax: '5',
  },
  {
    code: 'SCK09',
    name: 'Combed Cotton Socks (3-Pack)',
    colors: ['Mixed', 'Black'],
    sizes: ['M', 'L'],
    price: '380.00',
    cost: '175.00',
    promoName: null,
    promoDisc: '0.00',
    tax: '5',
  },
  {
    code: 'BLT10',
    name: 'Full-Grain Leather Belt',
    colors: ['Brown', 'Black'],
    sizes: ['32', '34'],
    price: '1250.00',
    cost: '720.00',
    promoName: null,
    promoDisc: '0.00',
    tax: '5',
  },
]

/**
 * Generates 40 variants (10 products x 2 colors x 2 sizes) with 200 unique 13-digit barcodes
 * (5 barcodes per variant, starting at `2000000000001`).
 */
export function buildInitialMockVariants(): MockVariantRecord[] {
  const variants: MockVariantRecord[] = []
  let seq = 1
  let barcodeSeq = 2000000000001

  for (let pIndex = 0; pIndex < PRODUCT_TEMPLATES.length; pIndex++) {
    const prod = PRODUCT_TEMPLATES[pIndex]
    const productId = `mock-prod-${String(pIndex + 1).padStart(2, '0')}`
    for (const color of prod.colors) {
      for (const size of prod.sizes) {
        const variantId = `mock-var-${String(seq).padStart(2, '0')}`
        const barcodes: string[] = []
        for (let b = 0; b < 5; b++) {
          barcodes.push(String(barcodeSeq++))
        }
        const shortColor = color.slice(0, 3).toUpperCase()
        const sku = `${prod.code}-${shortColor}-${size.replace(/\s+/g, '')}`

        // Variant #40 intentionally has 0 stock to allow testing INSUFFICIENT_STOCK
        const defaultStock = seq === 40 ? 0 : 12 + (seq % 15)

        variants.push({
          variantId,
          productId,
          productName: prod.name,
          sku,
          variantLabel: `${color} / ${size}`,
          barcodes,
          listPrice: prod.price,
          costPrice: prod.cost,
          promotionName: prod.promoName,
          promotionDiscount: prod.promoDisc,
          taxRatePercent: prod.tax,
          taxInclusive: false,
          stockByLocation: {
            default: defaultStock,
          },
        })
        seq++
      }
    }
  }
  return variants
}

export function toLookupResponse(
  variant: MockVariantRecord,
  barcode: string,
  locationId: string,
): PosLookupResponse {
  const listCents = Math.round(Number(variant.listPrice) * 100)
  const promoCents = Math.round(Number(variant.promotionDiscount) * 100)
  const netCents = Math.max(0, listCents - promoCents)
  const costCents = Math.round(Number(variant.costPrice) * 100)
  const stock = variant.stockByLocation[locationId] ?? variant.stockByLocation.default ?? 10

  return {
    variantId: variant.variantId,
    productId: variant.productId,
    productName: variant.productName,
    sku: variant.sku,
    variantLabel: variant.variantLabel,
    barcode,
    listPrice: variant.listPrice,
    promotionName: variant.promotionName,
    promotionDiscount: variant.promotionDiscount,
    netUnitPrice: (netCents / 100).toFixed(2),
    taxRatePercent: variant.taxRatePercent,
    taxInclusive: variant.taxInclusive,
    availableStock: stock,
    belowCost: netCents < costCents,
  }
}

export function buildSeededSales(defaultLocationId = 'loc-1'): SaleDetail[] {
  const receipt1: SaleDetail = {
    id: 'sale-seed-1',
    receiptNumber: 'MQ-2026-000101',
    locationId: defaultLocationId,
    locationName: 'Banani Branch',
    cashSessionId: 'sess-seed-0',
    cashierUsername: 'cashier.one',
    cashierName: 'Ayesha Rahman',
    soldAt: '2026-11-25T10:15:00Z',
    status: 'COMPLETED',
    subtotal: '1350.00',
    discountTotal: '150.00',
    taxTotal: '67.50',
    total: '1417.50',
    paymentMethod: 'CASH',
    paymentReference: null,
    itemCount: 2,
    lines: [
      {
        id: 'sl-seed-1',
        variantId: 'mock-var-01',
        sku: 'TEE01-BLA-M',
        productName: 'Cotton Crew T-Shirt',
        variantLabel: 'Black / M',
        quantity: 2,
        returnedQuantity: 0,
        unitPrice: '750.00',
        promotionDiscount: '150.00',
        manualDiscount: '0.00',
        tax: '67.50',
        lineTotal: '1417.50',
      },
    ],
    returns: [],
    receipt: {
      businessName: 'Merqo Store',
      branchName: 'Banani Branch',
      branchAddress: 'Road 11, Banani, Dhaka',
      branchPhone: '+880 1700-000111',
      receiptNumber: 'MQ-2026-000101',
      issuedAt: '2026-11-25T10:15:00Z',
      cashierName: 'Ayesha Rahman',
      lines: [
        {
          name: 'Cotton Crew T-Shirt',
          variant: 'Black / M',
          quantity: 2,
          unitPrice: '750.00',
          discount: '150.00',
          lineTotal: '1350.00',
        },
      ],
      subtotal: '1350.00',
      discount: '150.00',
      tax: '67.50',
      total: '1417.50',
      paymentMethod: 'CASH',
      paymentReference: null,
      footerText: 'Exchange within 7 days with original receipt.',
      isDuplicate: false,
    },
  }

  const receipt2: SaleDetail = {
    id: 'sale-seed-2',
    receiptNumber: 'MQ-2026-000102',
    locationId: defaultLocationId,
    locationName: 'Banani Branch',
    cashSessionId: 'sess-seed-0',
    cashierUsername: 'cashier.one',
    cashierName: 'Ayesha Rahman',
    soldAt: '2026-11-25T11:40:00Z',
    status: 'COMPLETED',
    subtotal: '1850.00',
    discountTotal: '0.00',
    taxTotal: '138.75',
    total: '1988.75',
    paymentMethod: 'BKASH',
    paymentReference: 'BKX998812',
    itemCount: 1,
    lines: [
      {
        id: 'sl-seed-2',
        variantId: 'mock-var-09',
        sku: 'CHN03-KHA-30',
        productName: 'Slim Chino Trouser',
        variantLabel: 'Khaki / 30',
        quantity: 1,
        returnedQuantity: 0,
        unitPrice: '1850.00',
        promotionDiscount: '0.00',
        manualDiscount: '0.00',
        tax: '138.75',
        lineTotal: '1988.75',
      },
    ],
    returns: [],
    receipt: {
      businessName: 'Merqo Store',
      branchName: 'Banani Branch',
      branchAddress: 'Road 11, Banani, Dhaka',
      branchPhone: '+880 1700-000111',
      receiptNumber: 'MQ-2026-000102',
      issuedAt: '2026-11-25T11:40:00Z',
      cashierName: 'Ayesha Rahman',
      lines: [
        {
          name: 'Slim Chino Trouser',
          variant: 'Khaki / 30',
          quantity: 1,
          unitPrice: '1850.00',
          discount: '0.00',
          lineTotal: '1850.00',
        },
      ],
      subtotal: '1850.00',
      discount: '0.00',
      tax: '138.75',
      total: '1988.75',
      paymentMethod: 'BKASH',
      paymentReference: 'BKX998812',
      footerText: 'Exchange within 7 days with original receipt.',
      isDuplicate: false,
    },
  }

  return [receipt2, receipt1]
}

export function buildEmptySession(
  id: string,
  locationId: string,
  openingCash: string,
): CashSessionSummary {
  return {
    id,
    locationId,
    cashierUsername: 'cashier.demo',
    openedAt: new Date().toISOString(),
    closedAt: null,
    status: 'OPEN',
    openingCash,
    expectedCash: openingCash,
    countedCash: null,
    variance: null,
    saleCount: 0,
    refundTotal: '0.00',
    byMethod: [
      { method: 'CASH', expected: openingCash, counted: null, difference: null },
      { method: 'BKASH', expected: '0.00', counted: null, difference: null },
      { method: 'NAGAD', expected: '0.00', counted: null, difference: null },
      { method: 'CARD', expected: '0.00', counted: null, difference: null },
    ],
  }
}
