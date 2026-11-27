import type {
  CashSessionSummary,
  InspectionItem,
  LookupResult,
  SaleDetail,
} from '../api/models'

export interface MockVariantRecord {
  variantId: string
  productId: string
  productName: string
  sku: string
  color: string | null
  size: string | null
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
        const defaultStock = seq === 40 ? 0 : 12 + (seq % 15)

        variants.push({
          variantId,
          productId,
          productName: prod.name,
          sku,
          color,
          size,
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

export function buildInitialMockInspections(defaultLocationId = 'loc-1'): InspectionItem[] {
  return [
    {
      locationId: defaultLocationId,
      variantId: 'mock-var-09',
      sku: 'CHN03-KHA-30',
      productName: 'Slim Chino Trouser',
      color: 'Khaki',
      size: '30',
      inInspection: 3,
      onHand: 18,
      available: 15,
    },
  ]
}

export function toLookupResponse(
  variant: MockVariantRecord,
  _barcode: string,
  locationId: string,
): LookupResult {
  const listCents = Math.round(Number(variant.listPrice) * 100)
  const promoCents = Math.round(Number(variant.promotionDiscount) * 100)
  const netCents = Math.max(0, listCents - promoCents)
  const costCents = Math.round(Number(variant.costPrice) * 100)
  const taxRate = Number(variant.taxRatePercent) || 0
  const taxCents = Math.round((netCents * taxRate) / 100)
  const totalCents = variant.taxInclusive ? netCents : netCents + taxCents
  const stock = variant.stockByLocation[locationId] ?? variant.stockByLocation.default ?? 10

  return {
    variant: {
      id: variant.variantId,
      productId: variant.productId,
      productName: variant.productName,
      sku: variant.sku,
      color: variant.color,
      size: variant.size,
      otherAttribute: null,
      sellingPrice: variant.listPrice,
      isActive: true,
      costPrice: variant.costPrice,
    },
    barcodes: variant.barcodes.map((b, index) => ({
      id: `${variant.variantId}-bc-${index + 1}`,
      barcode: b,
      kind: index === 0 ? 'PRIMARY' : 'ALTERNATE',
    })),
    promotion: variant.promotionName
      ? {
          id: `promo-${variant.variantId}`,
          name: variant.promotionName,
          discountType: 'FIXED',
          value: variant.promotionDiscount,
          scope: 'POS',
        }
      : null,
    listPrice: variant.listPrice,
    promotionDiscount: variant.promotionDiscount,
    manualDiscount: '0.00',
    netUnitPrice: (netCents / 100).toFixed(2),
    quantity: 1,
    subtotal: (netCents / 100).toFixed(2),
    taxRatePercent: variant.taxRatePercent,
    taxInclusive: variant.taxInclusive,
    tax: (taxCents / 100).toFixed(2),
    total: (totalCents / 100).toFixed(2),
    belowCost: netCents < costCents,
    cost: variant.costPrice,
    stock: {
      locationId,
      onHand: stock,
      reserved: 0,
      available: stock,
      inInspection: 0,
    },
  }
}

export function buildSeededSales(defaultLocationId = 'loc-1'): SaleDetail[] {
  const receipt1: SaleDetail = {
    id: 'sale-seed-1',
    receiptNumber: 'MQ-2026-000101',
    locationId: defaultLocationId,
    locationName: 'Banani Branch',
    cashSessionId: 'sess-seed-0',
    cashierId: 'u-cashier',
    cashierUsername: 'cashier.one',
    createdAt: '2026-11-25T10:15:00Z',
    subtotal: '1350.00',
    promotionDiscount: '150.00',
    manualDiscount: '0.00',
    taxTotal: '67.50',
    total: '1417.50',
    paymentMethod: 'CASH',
    changeGiven: '82.50',
    payment: {
      method: 'CASH',
      amount: '1417.50',
      tendered: '1500.00',
      changeGiven: '82.50',
      reference: null,
    },
    lines: [
      {
        id: 'sl-seed-1',
        lineIndex: 0,
        variantId: 'mock-var-01',
        productName: 'Cotton Crew T-Shirt',
        sku: 'TEE01-BLA-M',
        barcode: '2000000000001',
        color: 'Black',
        size: 'M',
        quantity: 2,
        listPrice: '750.00',
        promotionName: 'Weekend 10% Off',
        promotionDiscount: '75.00',
        manualDiscount: '0.00',
        billDiscountAllocated: '0.00',
        netUnitPrice: '675.00',
        taxRatePercent: '5.00',
        taxInclusive: false,
        taxAmount: '67.50',
        lineSubtotal: '1350.00',
        lineTotal: '1417.50',
      },
    ],
    note: null,
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
          lineTotal: '1417.50',
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
    cashierId: 'u-cashier',
    cashierUsername: 'cashier.one',
    createdAt: '2026-11-25T11:40:00Z',
    subtotal: '1850.00',
    promotionDiscount: '0.00',
    manualDiscount: '0.00',
    taxTotal: '138.75',
    total: '1988.75',
    paymentMethod: 'BKASH',
    changeGiven: '0.00',
    payment: {
      method: 'BKASH',
      amount: '1988.75',
      tendered: null,
      changeGiven: '0.00',
      reference: 'BKX998812',
    },
    lines: [
      {
        id: 'sl-seed-2',
        lineIndex: 0,
        variantId: 'mock-var-09',
        productName: 'Slim Chino Trouser',
        sku: 'CHN03-KHA-30',
        barcode: '2000000000041',
        color: 'Khaki',
        size: '30',
        quantity: 1,
        listPrice: '1850.00',
        promotionName: null,
        promotionDiscount: '0.00',
        manualDiscount: '0.00',
        billDiscountAllocated: '0.00',
        netUnitPrice: '1850.00',
        taxRatePercent: '7.50',
        taxInclusive: false,
        taxAmount: '138.75',
        lineSubtotal: '1850.00',
        lineTotal: '1988.75',
      },
    ],
    note: null,
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
          lineTotal: '1988.75',
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
  sessionNumber: string,
  locationId: string,
  openingCash: string,
): CashSessionSummary {
  return {
    id,
    sessionNumber,
    locationId,
    cashierId: 'u-cashier',
    cashierUsername: 'cashier.demo',
    openedAt: new Date().toISOString(),
    closedAt: null,
    status: 'OPEN',
    openingCash,
    expectedCash: openingCash,
    countedCash: null,
    variance: null,
    closeNote: null,
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
