import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useMutation, useQuery } from '@tanstack/react-query'
import { fetchVariants } from '../api/catalog'
import {
  createManualOrder,
  fetchCustomerHistory,
  type ClaimedAdvanceMethod,
  type OrderPaymentMode,
} from '../api/orders'
import { RiskBadge } from '../components/OrderBadges'
import {
  Button,
  Checkbox,
  ErrorState,
  FormField,
  Input,
  MoneyText,
  PageHeader,
  Select,
  Textarea,
  useToast,
} from '../components/ui'
import { manualOrderFormSchema, normalizeBdPhone } from '../lib/validation'
import { useSession } from '../session/sessionContext'

interface DraftLine {
  variantId: string
  quantity: number
}

export default function ManualOrderPage() {
  const { can } = useSession()
  const navigate = useNavigate()
  const { toast } = useToast()

  const variantsQuery = useQuery({
    queryKey: ['catalog', 'variants', 'manual-order'],
    queryFn: () => fetchVariants({ activeOnly: true, page: 0, size: 100 }),
  })

  const [phone, setPhone] = useState('')
  const [customerName, setCustomerName] = useState('')
  const [address, setAddress] = useState('')
  const [district, setDistrict] = useState('Dhaka')
  const [note, setNote] = useState('')
  const [lines, setLines] = useState<DraftLine[]>([{ variantId: '', quantity: 1 }])
  const [shippingFee, setShippingFee] = useState('60.00')
  const [discountTotal, setDiscountTotal] = useState('0.00')
  const [paymentMode, setPaymentMode] = useState<OrderPaymentMode>('COD')
  const [advanceAmount, setAdvanceAmount] = useState('')
  const [claimedAdvanceMethod, setClaimedAdvanceMethod] =
    useState<ClaimedAdvanceMethod>('BKASH')
  const [claimedAdvanceReference, setClaimedAdvanceReference] = useState('')
  const [confirmNow, setConfirmNow] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)

  const normalizedPhone = normalizeBdPhone(phone)
  const historyQuery = useQuery({
    queryKey: ['customers', 'history', normalizedPhone],
    queryFn: () => fetchCustomerHistory(normalizedPhone!),
    enabled: Boolean(normalizedPhone),
  })

  const variants = variantsQuery.data?.items ?? []

  const createMutation = useMutation({
    mutationFn: () =>
      createManualOrder({
        customerName: customerName.trim(),
        phone: phone.trim(),
        address: address.trim(),
        district: district.trim() || null,
        note: note.trim() || null,
        lines: lines.map((l) => ({ variantId: l.variantId, quantity: l.quantity })),
        shippingFee: shippingFee.trim() || '0.00',
        discountTotal: discountTotal.trim() || '0.00',
        paymentMode,
        advanceAmount: paymentMode === 'ADVANCE_COD' ? advanceAmount.trim() : undefined,
        claimedAdvanceMethod: paymentMode === 'COD' ? null : claimedAdvanceMethod,
        claimedAdvanceReference:
          paymentMode === 'COD' ? null : claimedAdvanceReference.trim() || null,
        confirmNow,
      }),
    onSuccess: (order) => {
      toast({ title: `Order ${order.orderNumber} created`, tone: 'success' })
      navigate(`/orders/${order.id}`)
    },
  })

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    const parsed = manualOrderFormSchema.safeParse({
      customerName,
      phone,
      address,
      district,
      note,
      shippingFee,
      discountTotal,
      paymentMode,
      advanceAmount,
      lines,
    })
    if (!parsed.success) {
      setFormError(parsed.error.issues[0]?.message ?? 'Check manual order fields.')
      return
    }
    setFormError(null)
    createMutation.mutate()
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-2 text-xs text-slate-500">
        <Link to="/orders" className="font-medium text-merqo-700 hover:underline">
          &larr; Back to online orders
        </Link>
      </div>

      <PageHeader
        title="New manual order"
        description="Phone and WhatsApp orders priced from Merqo's server online catalog & promotions."
      />

      <form onSubmit={handleSubmit} className="grid gap-6 lg:grid-cols-3">
        <div className="space-y-4 rounded-xl border border-slate-200 bg-white p-5 shadow-xs lg:col-span-2">
          <div className="grid gap-4 sm:grid-cols-2">
            <FormField label="Customer phone (BD)" required>
              <Input
                aria-label="Customer phone"
                value={phone}
                onChange={(e) => setPhone(e.target.value)}
                placeholder="01712345678"
              />
            </FormField>
            <FormField label="Customer name" required>
              <Input
                aria-label="Customer name"
                value={customerName}
                onChange={(e) => setCustomerName(e.target.value)}
                placeholder={historyQuery.data?.customerName ?? 'Full name'}
              />
            </FormField>
          </div>

          {historyQuery.data ? (
            <div className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-slate-200 bg-slate-50 p-3 text-xs">
              <div>
                <p className="font-semibold text-slate-800">
                  Customer history ({historyQuery.data.phoneNormalized})
                </p>
                <p className="text-slate-600">
                  Total {historyQuery.data.history.total} · Delivered{' '}
                  {historyQuery.data.history.delivered} · Cancelled{' '}
                  {historyQuery.data.history.cancelled} · Refused{' '}
                  {historyQuery.data.history.refused}
                </p>
              </div>
              <RiskBadge tier={historyQuery.data.riskTier} />
            </div>
          ) : null}

          <div className="grid gap-4 sm:grid-cols-2">
            <FormField label="Delivery address" required>
              <Textarea
                aria-label="Delivery address"
                value={address}
                onChange={(e) => setAddress(e.target.value)}
              />
            </FormField>
            <div className="space-y-3">
              <FormField label="District">
                <Input value={district} onChange={(e) => setDistrict(e.target.value)} />
              </FormField>
              <FormField label="Order note">
                <Input value={note} onChange={(e) => setNote(e.target.value)} />
              </FormField>
            </div>
          </div>

          <div className="space-y-2 border-t border-slate-100 pt-4">
            <div className="flex items-center justify-between">
              <h2 className="text-sm font-semibold text-slate-900">Items (priced server-side)</h2>
              <Button
                type="button"
                size="sm"
                variant="outline"
                onClick={() => setLines([...lines, { variantId: '', quantity: 1 }])}
              >
                + Add item
              </Button>
            </div>

            {lines.map((line, index) => {
              const selectedVariant = variants.find((v) => v.id === line.variantId)
              return (
                <div key={index} className="grid items-end gap-2 sm:grid-cols-[1fr_110px_auto]">
                  <FormField label={`Item ${index + 1}`}>
                    <Select
                      aria-label={`Item ${index + 1}`}
                      value={line.variantId}
                      onChange={(e) =>
                        setLines(
                          lines.map((item, idx) =>
                            idx === index ? { ...item, variantId: e.target.value } : item,
                          ),
                        )
                      }
                    >
                      <option value="">Select variant...</option>
                      {variants.map((v) => (
                        <option key={v.id} value={v.id}>
                          {v.sku} · {[v.color, v.size].filter(Boolean).join('/')} (৳ {v.sellingPrice})
                        </option>
                      ))}
                    </Select>
                  </FormField>
                  <FormField label="Qty">
                    <Input
                      type="number"
                      min="1"
                      value={line.quantity}
                      onChange={(e) =>
                        setLines(
                          lines.map((item, idx) =>
                            idx === index ? { ...item, quantity: Number(e.target.value) || 1 } : item,
                          ),
                        )
                      }
                    />
                  </FormField>
                  {lines.length > 1 ? (
                    <Button
                      type="button"
                      variant="outline"
                      onClick={() => setLines(lines.filter((_, idx) => idx !== index))}
                    >
                      Remove
                    </Button>
                  ) : null}
                  {selectedVariant ? (
                    <p className="text-xs text-slate-500 sm:col-span-3">
                      List price: <MoneyText amount={selectedVariant.sellingPrice} /> (active
                      ONLINE promotions apply automatically on save)
                    </p>
                  ) : null}
                </div>
              )
            })}
          </div>
        </div>

        <div className="space-y-4 rounded-xl border border-slate-200 bg-white p-5 shadow-xs">
          <h2 className="text-sm font-semibold text-slate-900">Charges & payment terms</h2>
          <FormField label="Shipping fee (BDT)">
            <Input value={shippingFee} onChange={(e) => setShippingFee(e.target.value)} />
          </FormField>
          <FormField label="Order discount (BDT)">
            <Input value={discountTotal} onChange={(e) => setDiscountTotal(e.target.value)} />
          </FormField>
          <FormField label="Payment mode">
            <Select
              aria-label="Payment mode"
              value={paymentMode}
              onChange={(e) => setPaymentMode(e.target.value as OrderPaymentMode)}
            >
              <option value="COD">Cash on delivery (COD)</option>
              <option value="FULL_PREPAID">Full prepaid</option>
              <option value="ADVANCE_COD">Partial advance + COD</option>
            </Select>
          </FormField>

          {paymentMode === 'ADVANCE_COD' ? (
            <FormField label="Claimed advance amount" required>
              <Input
                aria-label="Claimed advance amount"
                value={advanceAmount}
                onChange={(e) => setAdvanceAmount(e.target.value)}
              />
            </FormField>
          ) : null}

          {paymentMode !== 'COD' ? (
            <>
              <FormField label="Advance channel">
                <Select
                  value={claimedAdvanceMethod}
                  onChange={(e) =>
                    setClaimedAdvanceMethod(e.target.value as ClaimedAdvanceMethod)
                  }
                >
                  <option value="BKASH">bKash</option>
                  <option value="NAGAD">Nagad</option>
                  <option value="CARD">Card</option>
                  <option value="BANK">Bank</option>
                </Select>
              </FormField>
              <FormField label="Claimed TrxID / reference">
                <Input
                  value={claimedAdvanceReference}
                  onChange={(e) => setClaimedAdvanceReference(e.target.value)}
                />
              </FormField>
            </>
          ) : null}

          {can('ORDER_CONFIRM') ? (
            <label className="flex items-center gap-2 text-sm text-slate-700">
              <Checkbox
                checked={confirmNow}
                onChange={(e) => setConfirmNow(e.target.checked)}
              />
              <span>Confirm immediately &amp; reserve warehouse stock</span>
            </label>
          ) : null}

          {formError ? (
            <p role="alert" className="text-xs font-medium text-rose-700">
              {formError}
            </p>
          ) : null}
          {createMutation.isError ? (
            <ErrorState title="Could not create manual order" error={createMutation.error} />
          ) : null}

          <Button type="submit" isLoading={createMutation.isPending} className="w-full">
            Create manual order
          </Button>
        </div>
      </form>
    </div>
  )
}
