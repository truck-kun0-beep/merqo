import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { CODE128_PATTERNS, encodeCode128B } from '../lib/code128'
import { Receipt } from './Receipt'
import type { ReceiptData } from '../api/models'

const sampleData: ReceiptData = {
  businessName: 'Northline Store',
  branchName: 'Banani',
  branchAddress: 'Road 11, Dhaka',
  branchPhone: '01700000000',
  receiptNumber: 'MQ-0001',
  issuedAt: '2026-11-20T12:00:00Z',
  cashierName: 'Kasem',
  lines: [
    {
      name: 'Cotton Tee',
      variant: 'Black / M',
      quantity: 2,
      unitPrice: '500.00',
      discount: '50.00',
      lineTotal: '950.00',
    },
  ],
  subtotal: '950.00',
  discount: '50.00',
  tax: '47.50',
  total: '997.50',
  paymentMethod: 'CASH',
  paymentReference: null,
  footerText: 'Thank you for shopping.',
}

describe('Code 128B encoder', () => {
  it('validates all 107 ISO/IEC 15417 run-length patterns', () => {
    expect(CODE128_PATTERNS).toHaveLength(107)
    for (let i = 0; i < 106; i++) {
      const sum = [...CODE128_PATTERNS[i]].reduce((acc, d) => acc + Number(d), 0)
      expect(sum).toBe(11)
    }
    const stopSum = [...CODE128_PATTERNS[106]].reduce((acc, d) => acc + Number(d), 0)
    expect(stopSum).toBe(13)
  })

  it('produces the exact Start B, checksum, and Stop bar pattern for known inputs', () => {
    // Known input "A":
    // Start B = 104 (pattern 211214 -> bits 11010010000)
    // 'A' = ASCII 65 -> value 33 (pattern 111323 -> bits 10100011000)
    // Checksum = (104 + 1 * 33) % 103 = 137 % 103 = 34 (pattern 131123 -> bits 10001011000)
    // Stop = 106 (pattern 2331112 -> bits 1100011101011)
    const encodedA = encodeCode128B('A')
    expect(encodedA.codes).toEqual([104, 33, 34, 106])
    expect(encodedA.checksum).toBe(34)
    expect(encodedA.pattern).toBe('2112141113231311232331112')
    expect(encodedA.bits).toBe('11010010000' + '10100011000' + '10001011000' + '1100011101011')
    expect(encodedA.totalModules).toBe(11 * 3 + 13)

    // Known receipt number "MQ-0001":
    // M=45, Q=49, -=13, 0=16, 0=16, 0=16, 1=17
    // Sum = 104 + 1*45 + 2*49 + 3*13 + 4*16 + 5*16 + 6*16 + 7*17
    //     = 104 + 45 + 98 + 39 + 64 + 80 + 96 + 119 = 645
    // 645 % 103 = 27
    const encodedReceipt = encodeCode128B('MQ-0001')
    expect(encodedReceipt.codes).toEqual([104, 45, 49, 13, 16, 16, 16, 17, 27, 106])
    expect(encodedReceipt.checksum).toBe(27)
    expect(encodedReceipt.totalModules).toBe(11 * 9 + 13)
  })
})

describe('Receipt component', () => {
  it('renders receipt fields and SVG barcode, and shows DUPLICATE stamp only when asked', () => {
    const { rerender } = render(<Receipt data={sampleData} paperWidth="80mm" />)

    expect(screen.getByText('Northline Store')).toBeInTheDocument()
    expect(screen.getAllByText('MQ-0001')).toHaveLength(2)
    expect(screen.getByRole('img', { name: 'Barcode for MQ-0001' })).toBeInTheDocument()
    expect(screen.queryByText('DUPLICATE')).not.toBeInTheDocument()

    rerender(<Receipt data={{ ...sampleData, isDuplicate: true }} paperWidth="58mm" />)
    expect(screen.getByText('DUPLICATE')).toBeInTheDocument()
    expect(screen.getByRole('article')).toHaveAttribute('data-paper-width', '58mm')
  })
})
