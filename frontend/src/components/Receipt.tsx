import type { ReceiptData } from '../api/models'
import { encodeCode128B } from '../lib/code128'
import { formatDateTime, formatMoney, formatQuantity } from '../lib/format'
import './receipt-print.css'

export type ReceiptPaperWidth = '80mm' | '58mm'

export interface ReceiptProps {
  data: ReceiptData
  paperWidth?: ReceiptPaperWidth
  className?: string
}

function tryEncodeCode128(text: string): ReturnType<typeof encodeCode128B> | null {
  try {
    return encodeCode128B(text)
  } catch {
    return null
  }
}

/**
 * Thermal POS receipt supporting 80 mm (default) and 58 mm print widths,
 * optional DUPLICATE stamp, and a client-side Code 128 SVG barcode.
 */
export function Receipt({ data, paperWidth = '80mm', className = '' }: ReceiptProps) {
  const widthClass =
    paperWidth === '58mm'
      ? 'merqo-receipt--58mm w-[58mm] text-[11px]'
      : 'merqo-receipt--80mm w-[80mm] text-xs'

  const barcode = tryEncodeCode128(data.receiptNumber)

  const quietZone = 10

  return (
    <article
      aria-label={`Receipt ${data.receiptNumber}`}
      data-paper-width={paperWidth}
      className={[
        'merqo-receipt mx-auto rounded-md border border-slate-200 bg-white p-3 font-mono text-slate-900 shadow-xs',
        widthClass,
        className,
      ].join(' ')}
    >
      <header className="border-b border-dashed border-slate-300 pb-2 text-center">
        <h2 className="text-sm font-bold tracking-tight uppercase">{data.businessName}</h2>
        <p className="mt-0.5 font-semibold">{data.branchName}</p>
        {data.branchAddress ? (
          <p className="text-[11px] text-slate-600">{data.branchAddress}</p>
        ) : null}
        {data.branchPhone ? <p className="text-[11px] text-slate-600">{data.branchPhone}</p> : null}
        {data.isDuplicate ? (
          <p
            aria-label="Duplicate receipt stamp"
            className="mt-2 inline-block border-2 border-slate-900 px-2 py-0.5 text-xs font-bold tracking-widest uppercase"
          >
            DUPLICATE
          </p>
        ) : null}
      </header>

      <dl className="mt-2 space-y-0.5 border-b border-dashed border-slate-300 pb-2 text-[11px]">
        <div className="flex justify-between gap-2">
          <dt className="text-slate-500">Receipt:</dt>
          <dd className="font-semibold">{data.receiptNumber}</dd>
        </div>
        <div className="flex justify-between gap-2">
          <dt className="text-slate-500">Date:</dt>
          <dd>{formatDateTime(data.issuedAt)}</dd>
        </div>
        <div className="flex justify-between gap-2">
          <dt className="text-slate-500">Cashier:</dt>
          <dd>{data.cashierName}</dd>
        </div>
      </dl>

      <div className="mt-2 border-b border-dashed border-slate-300 pb-2">
        <div className="mb-1 flex justify-between text-[10px] font-semibold tracking-wide text-slate-500 uppercase">
          <span>Item</span>
          <span>Total</span>
        </div>
        <ul className="space-y-1.5">
          {data.lines.map((line, index) => (
            <li key={`${line.name}-${index}`}>
              <div className="flex justify-between gap-2 font-medium">
                <span>
                  {line.name}
                  {line.variant ? ` (${line.variant})` : ''}
                </span>
                <span className="tabular-nums whitespace-nowrap">
                  {formatMoney(line.lineTotal)}
                </span>
              </div>
              <div className="flex justify-between text-[11px] text-slate-600">
                <span>
                  {formatQuantity(line.quantity)} x {formatMoney(line.unitPrice)}
                </span>
                {Number(line.discount) > 0 ? (
                  <span className="tabular-nums">Disc -{formatMoney(line.discount)}</span>
                ) : null}
              </div>
            </li>
          ))}
        </ul>
      </div>

      <dl className="mt-2 space-y-0.5 border-b border-dashed border-slate-300 pb-2 tabular-nums">
        <div className="flex justify-between">
          <dt className="text-slate-600">Subtotal</dt>
          <dd>{formatMoney(data.subtotal)}</dd>
        </div>
        {Number(data.discount) > 0 ? (
          <div className="flex justify-between">
            <dt className="text-slate-600">Discount</dt>
            <dd>-{formatMoney(data.discount)}</dd>
          </div>
        ) : null}
        <div className="flex justify-between">
          <dt className="text-slate-600">Tax</dt>
          <dd>{formatMoney(data.tax)}</dd>
        </div>
        <div className="flex justify-between pt-1 text-sm font-bold">
          <dt>TOTAL</dt>
          <dd>{formatMoney(data.total)}</dd>
        </div>
      </dl>

      <dl className="mt-2 space-y-0.5 border-b border-dashed border-slate-300 pb-2 text-[11px]">
        <div className="flex justify-between">
          <dt className="text-slate-500">Payment</dt>
          <dd className="font-semibold">{data.paymentMethod}</dd>
        </div>
        {data.paymentReference ? (
          <div className="flex justify-between">
            <dt className="text-slate-500">Ref</dt>
            <dd>{data.paymentReference}</dd>
          </div>
        ) : null}
      </dl>

      {barcode ? (
        <div className="mt-3 flex flex-col items-center">
          <svg
            role="img"
            aria-label={`Barcode for ${data.receiptNumber}`}
            viewBox={`0 0 ${barcode.totalModules + quietZone * 2} 44`}
            className="h-11 w-full max-w-[220px]"
            shapeRendering="crispEdges"
          >
            <rect
              x={0}
              y={0}
              width={barcode.totalModules + quietZone * 2}
              height={44}
              fill="#ffffff"
            />
            {barcode.bars.map((bar, index) => (
              <rect
                key={index}
                x={bar.x + quietZone}
                y={2}
                width={bar.width}
                height={40}
                fill="#000000"
              />
            ))}
          </svg>
          <span className="mt-0.5 text-[10px] tracking-widest text-slate-600">
            {data.receiptNumber}
          </span>
        </div>
      ) : null}

      {data.footerText ? (
        <footer className="mt-2 text-center text-[11px] text-slate-600">{data.footerText}</footer>
      ) : null}
    </article>
  )
}
