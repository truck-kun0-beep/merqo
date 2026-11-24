import { useState } from 'react'
import type { ReceiptData } from '../api/models'
import { Receipt, type ReceiptPaperWidth } from '../components/Receipt'
import { Button, Checkbox, PageHeader } from '../components/ui'

const SAMPLE_RECEIPT: ReceiptData = {
  businessName: 'Northline Outfitters',
  branchName: 'Banani Flagship',
  branchAddress: 'Road 11, Block F, Banani, Dhaka',
  branchPhone: '+880 1700-000111',
  receiptNumber: 'MQ-2026-000142',
  issuedAt: '2026-11-20T14:35:00Z',
  cashierName: 'Ayesha Rahman',
  lines: [
    {
      name: 'Cotton T-Shirt',
      variant: 'Black / M',
      quantity: 2,
      unitPrice: '750.00',
      discount: '100.00',
      lineTotal: '1400.00',
    },
    {
      name: 'Chino Trousers',
      variant: 'Olive / 32',
      quantity: 1,
      unitPrice: '1850.00',
      discount: '0.00',
      lineTotal: '1850.00',
    },
  ],
  subtotal: '3250.00',
  discount: '100.00',
  tax: '243.75',
  total: '3493.75',
  paymentMethod: 'CARD',
  paymentReference: 'TXN-884120',
  footerText: 'Exchange within 7 days with original receipt.',
  isDuplicate: false,
}

/**
 * Development-only preview route for thermal receipt formatting and printing.
 */
export default function DevReceiptPreviewPage() {
  const [paperWidth, setPaperWidth] = useState<ReceiptPaperWidth>('80mm')
  const [isDuplicate, setIsDuplicate] = useState(false)

  return (
    <div className="space-y-6">
      <PageHeader
        title="Receipt preview"
        description="Development preview for 80 mm and 58 mm thermal receipt printouts."
        actions={
          <div className="flex flex-wrap items-center gap-3">
            <Button
              size="sm"
              variant={paperWidth === '80mm' ? 'primary' : 'outline'}
              onClick={() => setPaperWidth('80mm')}
            >
              80 mm
            </Button>
            <Button
              size="sm"
              variant={paperWidth === '58mm' ? 'primary' : 'outline'}
              onClick={() => setPaperWidth('58mm')}
            >
              58 mm
            </Button>
            <Checkbox
              checked={isDuplicate}
              onChange={(e) => setIsDuplicate(e.target.checked)}
              label="DUPLICATE stamp"
            />
            <Button onClick={() => window.print()}>Print</Button>
          </div>
        }
      />

      <div className="flex justify-center rounded-xl border border-slate-200 bg-slate-100 p-8">
        <Receipt data={{ ...SAMPLE_RECEIPT, isDuplicate }} paperWidth={paperWidth} />
      </div>
    </div>
  )
}
