import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useParams, useSearchParams } from 'react-router-dom'
import {
  activateVariant,
  addAlternateBarcode,
  changeVariantCost,
  changeVariantPrice,
  createVariant,
  deactivateVariant,
  fetchProduct,
  fetchVariant,
  previewPrice,
  removeBarcode,
  setPrimaryBarcode,
  updateVariant,
} from '../api/catalog'
import type { VariantDetail, VariantSummary } from '../api/models'
import { ApiError } from '../api/types'
import { canSeeCosts } from '../lib/scopes'
import { validateBarcodeText, variantFormSchema } from '../lib/validation'
import { useSession } from '../session/sessionContext'
import {
  Badge,
  Button,
  DataTable,
  Drawer,
  ErrorState,
  FormField,
  Input,
  Modal,
  MoneyText,
  PageHeader,
  Skeleton,
  StatusPill,
  useToast,
  type DataTableColumn,
} from '../components/ui'

/**
 * Product detail page managing variants, primary/alternate barcodes, pricing, and activation.
 */
export default function ProductDetailPage() {
  const { id } = useParams<{ id: string }>()
  const [searchParams] = useSearchParams()
  const highlightedVariantId = searchParams.get('variant')
  const { user, can } = useSession()
  const queryClient = useQueryClient()
  const { toast } = useToast()

  const canCreate = can('PRODUCT_CREATE')
  const canEdit = can('PRODUCT_EDIT')
  const canEditPrice = can('PRICE_EDIT')
  const canEditBarcode = can('BARCODE_EDIT')
  const showCostColumn = canSeeCosts(user)

  const [createOpen, setCreateOpen] = useState(false)
  const [editingVariant, setEditingVariant] = useState<VariantSummary | null>(null)
  const [priceVariant, setPriceVariant] = useState<VariantSummary | null>(null)
  const [costVariant, setCostVariant] = useState<VariantSummary | null>(null)
  const [barcodeVariantId, setBarcodeVariantId] = useState<string | null>(highlightedVariantId)
  const [activationError, setActivationError] = useState<{
    message: string
    correlationId?: string
    isBarcodeRequired?: boolean
  } | null>(null)

  const productQuery = useQuery({
    queryKey: ['catalog', 'product', id],
    queryFn: () => fetchProduct(id ?? ''),
    enabled: Boolean(id),
  })

  const toggleMutation = useMutation({
    mutationFn: (variant: VariantSummary) =>
      variant.isActive ? deactivateVariant(variant.id) : activateVariant(variant.id),
    onSuccess: (updated) => {
      setActivationError(null)
      queryClient.invalidateQueries({ queryKey: ['catalog', 'product', id] })
      toast({
        title: `${updated.sku} ${updated.isActive ? 'activated' : 'deactivated'}`,
        tone: 'success',
      })
    },
    onError: (caught) => {
      if (caught instanceof ApiError) {
        setActivationError({
          message:
            caught.errorCode === 'BARCODE_REQUIRED'
              ? 'Primary barcode required: assign a primary barcode before activating this variant.'
              : caught.message,
          correlationId: caught.correlationId,
          isBarcodeRequired: caught.errorCode === 'BARCODE_REQUIRED',
        })
      } else {
        setActivationError({ message: 'Could not change variant state.' })
      }
    },
  })

  if (productQuery.isPending) {
    return (
      <div role="status" aria-label="Loading product" className="space-y-4">
        <Skeleton className="h-16 w-full rounded-xl" />
        <Skeleton className="h-64 w-full rounded-xl" />
      </div>
    )
  }

  if (productQuery.error) {
    return (
      <ErrorState
        title="Could not load product"
        error={productQuery.error}
        onRetry={() => productQuery.refetch()}
      />
    )
  }

  const product = productQuery.data
  if (!product) return null

  const variants = product.variants ?? []
  const hasAnyCost = showCostColumn || variants.some((v) => v.costPrice !== undefined)

  const columns: DataTableColumn<VariantSummary>[] = [
    {
      key: 'sku',
      header: 'SKU',
      sortable: true,
      sortValue: (v) => v.sku,
      render: (v) => (
        <div className="flex items-center gap-2">
          <span className="font-mono text-xs font-semibold text-slate-900">{v.sku}</span>
          {v.id === highlightedVariantId ? <Badge tone="info">Scanned</Badge> : null}
        </div>
      ),
    },
    {
      key: 'attributes',
      header: 'Attributes',
      render: (v) => (
        <span className="text-xs text-slate-600">
          {[v.color, v.size, v.otherAttribute].filter(Boolean).join(' · ') || '-'}
        </span>
      ),
    },
    {
      key: 'barcodes',
      header: 'Barcodes',
      render: (v) => (
        <VariantBarcodeSummaryCell
          variantId={v.id}
          canEditBarcode={canEditBarcode}
          onManage={() => setBarcodeVariantId(v.id)}
        />
      ),
    },
    {
      key: 'sellingPrice',
      header: 'Selling price',
      align: 'right',
      render: (v) => (
        <MoneyText amount={v.sellingPrice} className="font-medium text-slate-900" />
      ),
    },
    ...(hasAnyCost
      ? [
          {
            key: 'costPrice',
            header: 'Cost',
            align: 'right' as const,
            render: (v: VariantSummary) => <MoneyText amount={v.costPrice} />,
          },
        ]
      : []),
    {
      key: 'status',
      header: 'Status',
      render: (v) => <StatusPill status={v.isActive ? 'ACTIVE' : 'INACTIVE'} />,
    },
    {
      key: 'actions',
      header: 'Actions',
      render: (v) => (
        <div className="flex flex-wrap items-center gap-2">
          {canEditBarcode ? (
            <button
              type="button"
              onClick={() => setBarcodeVariantId(v.id)}
              className="text-xs font-medium text-merqo-700 hover:underline"
            >
              Barcodes
            </button>
          ) : null}
          {canEditPrice ? (
            <>
              <button
                type="button"
                onClick={() => setPriceVariant(v)}
                className="text-xs font-medium text-merqo-700 hover:underline"
              >
                Change price
              </button>
              <button
                type="button"
                onClick={() => setCostVariant(v)}
                className="text-xs font-medium text-slate-600 hover:underline"
              >
                Cost
              </button>
            </>
          ) : null}
          {canEdit ? (
            <>
              <button
                type="button"
                onClick={() => setEditingVariant(v)}
                className="text-xs font-medium text-slate-600 hover:underline"
              >
                Edit
              </button>
              <button
                type="button"
                onClick={() => toggleMutation.mutate(v)}
                className={`text-xs font-medium hover:underline ${
                  v.isActive ? 'text-rose-700' : 'text-emerald-700'
                }`}
              >
                {v.isActive ? 'Deactivate' : 'Activate'}
              </button>
            </>
          ) : null}
        </div>
      ),
    },
  ]

  return (
    <div className="space-y-6">
      <PageHeader
        breadcrumbs={[{ label: 'Catalog', to: '/catalog/products' }, { label: product.name }]}
        title={product.name}
        description={`Code: ${product.productCode}${
          product.taxRatePercent ? ` · Tax override: ${product.taxRatePercent}%` : ''
        }`}
        actions={
          <div className="flex items-center gap-2">
            <StatusPill status={product.isActive ? 'ACTIVE' : 'INACTIVE'} />
            {canCreate ? (
              <Button onClick={() => setCreateOpen(true)}>Add variant</Button>
            ) : null}
          </div>
        }
      />

      {product.description ? (
        <p className="rounded-xl border border-slate-200 bg-white px-4 py-3 text-sm text-slate-600">
          {product.description}
        </p>
      ) : null}

      {activationError ? (
        <ErrorState
          title={
            activationError.isBarcodeRequired
              ? 'Barcode required for activation'
              : 'Could not update variant status'
          }
          message={activationError.message}
          correlationId={activationError.correlationId}
        />
      ) : null}

      <DataTable
        columns={columns}
        rows={variants}
        getRowKey={(v) => v.id}
        emptyTitle="No variants yet"
        emptyDescription="Add a variant with SKU, selling price, and primary barcode."
      />

      {createOpen ? (
        <VariantFormModal
          productId={product.id}
          onClose={() => setCreateOpen(false)}
          onSaved={() => queryClient.invalidateQueries({ queryKey: ['catalog', 'product', id] })}
        />
      ) : null}

      {editingVariant ? (
        <VariantFormModal
          productId={product.id}
          variant={editingVariant}
          onClose={() => setEditingVariant(null)}
          onSaved={() => queryClient.invalidateQueries({ queryKey: ['catalog', 'product', id] })}
        />
      ) : null}

      {priceVariant ? (
        <PriceChangeModal
          variant={priceVariant}
          onClose={() => setPriceVariant(null)}
          onSaved={() => queryClient.invalidateQueries({ queryKey: ['catalog', 'product', id] })}
        />
      ) : null}

      {costVariant ? (
        <CostChangeModal
          variant={costVariant}
          onClose={() => setCostVariant(null)}
          onSaved={() => queryClient.invalidateQueries({ queryKey: ['catalog', 'product', id] })}
        />
      ) : null}

      <BarcodeDrawer
        variantId={barcodeVariantId}
        canEdit={canEditBarcode}
        onClose={() => setBarcodeVariantId(null)}
      />
    </div>
  )
}

function VariantBarcodeSummaryCell({
  variantId,
  canEditBarcode,
  onManage,
}: {
  variantId: string
  canEditBarcode: boolean
  onManage: () => void
}) {
  const detailQuery = useQuery({
    queryKey: ['catalog', 'variant', variantId],
    queryFn: () => fetchVariant(variantId),
  })

  if (detailQuery.isPending) {
    return <span className="text-xs text-slate-400">Loading...</span>
  }

  const barcodes = detailQuery.data?.barcodes ?? []
  const primary = barcodes.find((b) => b.kind === 'PRIMARY')
  const alternates = barcodes.filter((b) => b.kind === 'ALTERNATE')

  if (barcodes.length === 0) {
    return (
      <span className="text-xs text-amber-700">
        None{' '}
        {canEditBarcode ? (
          <button type="button" onClick={onManage} className="ml-1 font-medium underline">
            Scan/add
          </button>
        ) : null}
      </span>
    )
  }

  return (
    <div className="space-y-0.5">
      {primary ? (
        <span className="block font-mono text-xs font-medium text-slate-800">{primary.barcode}</span>
      ) : null}
      {alternates.length > 0 ? (
        <span className="block font-mono text-[11px] text-slate-500">
          +{alternates.length} alt ({alternates.map((a) => a.barcode).join(', ')})
        </span>
      ) : null}
    </div>
  )
}

function BarcodeDrawer({
  variantId,
  canEdit,
  onClose,
}: {
  variantId: string | null
  canEdit: boolean
  onClose: () => void
}) {
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const [scanCode, setScanCode] = useState('')
  const [kind, setKind] = useState<'PRIMARY' | 'ALTERNATE'>('PRIMARY')
  const [validationError, setValidationError] = useState<string | null>(null)

  const variantQuery = useQuery({
    queryKey: ['catalog', 'variant', variantId],
    queryFn: () => fetchVariant(variantId!),
    enabled: Boolean(variantId),
  })

  const addMutation = useMutation({
    mutationFn: () =>
      kind === 'PRIMARY'
        ? setPrimaryBarcode(variantId!, { barcode: scanCode.trim() })
        : addAlternateBarcode(variantId!, { barcode: scanCode.trim() }),
    onSuccess: (updated: VariantDetail) => {
      setScanCode('')
      setValidationError(null)
      queryClient.setQueryData(['catalog', 'variant', variantId], updated)
      toast({ title: 'Barcode saved', tone: 'success' })
    },
  })

  const removeMutation = useMutation({
    mutationFn: (barcodeId: string) => removeBarcode(variantId!, barcodeId),
    onSuccess: (updated: VariantDetail) => {
      queryClient.setQueryData(['catalog', 'variant', variantId], updated)
      toast({ title: 'Barcode removed', tone: 'info' })
    },
  })

  function handleAdd(event: FormEvent) {
    event.preventDefault()
    const err = validateBarcodeText(scanCode)
    if (err) {
      setValidationError(err)
      return
    }
    setValidationError(null)
    addMutation.mutate()
  }

  return (
    <Drawer
      open={Boolean(variantId)}
      onClose={onClose}
      title={variantQuery.data ? `Barcodes · ${variantQuery.data.sku}` : 'Barcodes'}
      description="Scan or type a barcode and press Enter to assign it."
    >
      {variantQuery.isPending ? <Skeleton className="h-24 w-full" /> : null}
      {variantQuery.error ? <ErrorState title="Could not load barcodes" error={variantQuery.error} /> : null}

      {variantQuery.data ? (
        <div className="space-y-5">
          {canEdit ? (
            <form onSubmit={handleAdd} className="space-y-3 rounded-lg border border-slate-200 bg-slate-50 p-4">
              <div className="flex items-center gap-2">
                <Button
                  type="button"
                  size="sm"
                  variant={kind === 'PRIMARY' ? 'primary' : 'outline'}
                  onClick={() => setKind('PRIMARY')}
                >
                  Primary
                </Button>
                <Button
                  type="button"
                  size="sm"
                  variant={kind === 'ALTERNATE' ? 'primary' : 'outline'}
                  onClick={() => setKind('ALTERNATE')}
                >
                  Alternate
                </Button>
              </div>
              <FormField
                label={kind === 'PRIMARY' ? 'Scan or enter primary barcode' : 'Scan or enter alternate barcode'}
                error={validationError ?? undefined}
              >
                <div className="flex gap-2">
                  <Input
                    autoFocus
                    value={scanCode}
                    placeholder="Scan barcode and press Enter..."
                    onChange={(e) => {
                      setScanCode(e.target.value)
                      if (validationError) setValidationError(null)
                    }}
                  />
                  <Button type="submit" isLoading={addMutation.isPending}>
                    Assign
                  </Button>
                </div>
              </FormField>
              {addMutation.isError ? (
                <ErrorState title="Could not assign barcode" error={addMutation.error} />
              ) : null}
            </form>
          ) : null}

          {removeMutation.isError ? (
            <ErrorState title="Could not remove barcode" error={removeMutation.error} />
          ) : null}

          <ul className="divide-y divide-slate-100 rounded-lg border border-slate-200 bg-white">
            {variantQuery.data.barcodes.length === 0 ? (
              <li className="px-4 py-6 text-center text-sm text-slate-500">
                No barcodes assigned yet.
              </li>
            ) : (
              variantQuery.data.barcodes.map((b) => (
                <li key={b.id} className="flex items-center justify-between gap-2 px-4 py-3">
                  <div className="flex items-center gap-2">
                    <span className="font-mono text-sm font-medium text-slate-900">
                      {b.barcode}
                    </span>
                    <Badge tone={b.kind === 'PRIMARY' ? 'primary' : 'neutral'}>{b.kind}</Badge>
                  </div>
                  {canEdit ? (
                    <Button
                      variant="ghost"
                      size="sm"
                      className="text-rose-700 hover:bg-rose-50"
                      onClick={() => removeMutation.mutate(b.id)}
                      isLoading={removeMutation.isPending}
                    >
                      Remove
                    </Button>
                  ) : null}
                </li>
              ))
            )}
          </ul>
        </div>
      ) : null}
    </Drawer>
  )
}

function VariantFormModal({
  productId,
  variant,
  onClose,
  onSaved,
}: {
  productId: string
  variant?: VariantSummary
  onClose: () => void
  onSaved: () => void
}) {
  const { toast } = useToast()
  const [sku, setSku] = useState(variant?.sku ?? '')
  const [color, setColor] = useState(variant?.color ?? '')
  const [size, setSize] = useState(variant?.size ?? '')
  const [otherAttribute, setOtherAttribute] = useState(variant?.otherAttribute ?? '')
  const [sellingPrice, setSellingPrice] = useState(variant?.sellingPrice ?? '')
  const [barcode, setBarcode] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})

  const mutation = useMutation({
    mutationFn: () =>
      variant
        ? updateVariant(variant.id, {
            sku: sku.trim() || undefined,
            color: color.trim() || null,
            size: size.trim() || null,
            otherAttribute: otherAttribute.trim() || null,
          })
        : createVariant(productId, {
            sku: sku.trim() || undefined,
            color: color.trim() || null,
            size: size.trim() || null,
            otherAttribute: otherAttribute.trim() || null,
            sellingPrice: sellingPrice.trim(),
            barcode: barcode.trim() || undefined,
          }),
    onSuccess: () => {
      toast({ title: variant ? 'Variant updated' : 'Variant created', tone: 'success' })
      onSaved()
      onClose()
    },
  })

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const parsed = variantFormSchema.safeParse({
      sku,
      color,
      size,
      otherAttribute,
      sellingPrice: variant ? variant.sellingPrice : sellingPrice,
      barcode: variant ? '' : barcode,
    })
    if (!parsed.success) {
      const next: Record<string, string> = {}
      for (const issue of parsed.error.issues) {
        const key = String(issue.path[0] ?? '')
        if (key && !next[key]) next[key] = issue.message
      }
      setFieldErrors(next)
      return
    }
    setFieldErrors({})
    mutation.mutate()
  }

  return (
    <Modal open onClose={onClose} title={variant ? `Edit ${variant.sku}` : 'New variant'}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <div className="grid gap-3 sm:grid-cols-2">
          <FormField label="SKU" hint="Leave blank on create to auto-generate." error={fieldErrors.sku}>
            <Input value={sku} onChange={(e) => setSku(e.target.value)} />
          </FormField>
          {!variant ? (
            <FormField label="Selling price" error={fieldErrors.sellingPrice} required>
              <Input
                type="number"
                step="0.01"
                min="0"
                value={sellingPrice}
                invalid={Boolean(fieldErrors.sellingPrice)}
                onChange={(e) => setSellingPrice(e.target.value)}
              />
            </FormField>
          ) : null}
          <FormField label="Color">
            <Input value={color} onChange={(e) => setColor(e.target.value)} />
          </FormField>
          <FormField label="Size">
            <Input value={size} onChange={(e) => setSize(e.target.value)} />
          </FormField>
          <FormField label="Other attribute" className="sm:col-span-2">
            <Input value={otherAttribute} onChange={(e) => setOtherAttribute(e.target.value)} />
          </FormField>
          {!variant ? (
            <FormField
              label="Primary barcode"
              hint="Variants without a primary barcode are created inactive."
              error={fieldErrors.barcode}
              className="sm:col-span-2"
            >
              <Input
                value={barcode}
                invalid={Boolean(fieldErrors.barcode)}
                onChange={(e) => setBarcode(e.target.value)}
              />
            </FormField>
          ) : null}
        </div>

        {mutation.isError ? <ErrorState title="Could not save variant" error={mutation.error} /> : null}

        <div className="flex justify-end gap-2 pt-2">
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" isLoading={mutation.isPending} loadingText="Saving...">
            {variant ? 'Save changes' : 'Create variant'}
          </Button>
        </div>
      </form>
    </Modal>
  )
}

function PriceChangeModal({
  variant,
  onClose,
  onSaved,
}: {
  variant: VariantSummary
  onClose: () => void
  onSaved: () => void
}) {
  const { toast } = useToast()
  const [sellingPrice, setSellingPrice] = useState(variant.sellingPrice)
  const [error, setError] = useState<string | undefined>(undefined)

  const previewQuery = useQuery({
    queryKey: ['catalog', 'price-preview', variant.id],
    queryFn: () => previewPrice(variant.id),
  })

  const costString = variant.costPrice ?? previewQuery.data?.cost
  const numericPrice = Number(sellingPrice)
  const numericCost = costString ? Number(costString) : undefined
  const isBelowCost =
    numericCost !== undefined &&
    !Number.isNaN(numericCost) &&
    !Number.isNaN(numericPrice) &&
    numericPrice < numericCost

  const mutation = useMutation({
    mutationFn: () => changeVariantPrice(variant.id, { sellingPrice: sellingPrice.trim() }),
    onSuccess: () => {
      toast({ title: `Price updated for ${variant.sku}`, tone: 'success' })
      onSaved()
      onClose()
    },
  })

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (sellingPrice.trim() === '' || Number.isNaN(numericPrice) || numericPrice < 0) {
      setError('Selling price must be 0 or greater.')
      return
    }
    setError(undefined)
    mutation.mutate()
  }

  return (
    <Modal open onClose={onClose} title={`Change price · ${variant.sku}`}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <div className="rounded-lg border border-slate-200 bg-slate-50 px-4 py-3 text-xs text-slate-600">
          Current price: <MoneyText amount={variant.sellingPrice} className="font-semibold text-slate-900" />
          {costString !== undefined ? (
            <>
              {' '}
              · Current cost: <MoneyText amount={costString} className="font-semibold text-slate-900" />
            </>
          ) : null}
        </div>

        <FormField label="New selling price" error={error} required>
          <Input
            type="number"
            step="0.01"
            min="0"
            value={sellingPrice}
            invalid={Boolean(error)}
            onChange={(e) => setSellingPrice(e.target.value)}
          />
        </FormField>

        {isBelowCost || previewQuery.data?.belowCost ? (
          <div
            role="alert"
            className="rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900"
          >
            <p className="font-semibold">Below-cost warning</p>
            <p className="mt-0.5 text-xs">
              {isBelowCost
                ? `The entered price (${sellingPrice}) is below the unit cost.`
                : 'Current net selling price after active promotions is below cost.'}
            </p>
          </div>
        ) : null}

        {mutation.isError ? <ErrorState title="Could not change price" error={mutation.error} /> : null}

        <div className="flex justify-end gap-2 pt-2">
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" isLoading={mutation.isPending} loadingText="Saving...">
            Update price
          </Button>
        </div>
      </form>
    </Modal>
  )
}

function CostChangeModal({
  variant,
  onClose,
  onSaved,
}: {
  variant: VariantSummary
  onClose: () => void
  onSaved: () => void
}) {
  const { toast } = useToast()
  const [costPrice, setCostPrice] = useState(variant.costPrice ?? '')
  const [error, setError] = useState<string | undefined>(undefined)

  const mutation = useMutation({
    mutationFn: () => changeVariantCost(variant.id, { costPrice: costPrice.trim() }),
    onSuccess: () => {
      toast({ title: `Cost updated for ${variant.sku}`, tone: 'success' })
      onSaved()
      onClose()
    },
  })

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const num = Number(costPrice)
    if (costPrice.trim() === '' || Number.isNaN(num) || num < 0) {
      setError('Cost must be 0 or greater.')
      return
    }
    setError(undefined)
    mutation.mutate()
  }

  return (
    <Modal open onClose={onClose} title={`Change cost · ${variant.sku}`}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <FormField label="Unit cost" error={error} required>
          <Input
            type="number"
            step="0.01"
            min="0"
            value={costPrice}
            invalid={Boolean(error)}
            onChange={(e) => setCostPrice(e.target.value)}
          />
        </FormField>

        {mutation.isError ? <ErrorState title="Could not update cost" error={mutation.error} /> : null}

        <div className="flex justify-end gap-2 pt-2">
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" isLoading={mutation.isPending} loadingText="Saving...">
            Update cost
          </Button>
        </div>
      </form>
    </Modal>
  )
}
