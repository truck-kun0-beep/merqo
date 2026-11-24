import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { fetchLocations } from '../api/business'
import { fetchBrands, fetchCategories, fetchProducts, fetchVariants } from '../api/catalog'
import {
  createPromotion,
  endPromotion,
  fetchPromotions,
  setPromotionEnabled,
  type ConflictAction,
} from '../api/promotions'
import type {
  DiscountType,
  PromotionScope,
  PromotionStatus,
  PromotionSummary,
  VariantSummary,
} from '../api/models'
import { ApiError } from '../api/types'
import { filterLocationsByPermission } from '../lib/scopes'
import { promotionFormSchema } from '../lib/validation'
import { useSession } from '../session/sessionContext'
import {
  Badge,
  Button,
  Checkbox,
  ConfirmDialog,
  DataTable,
  DateText,
  ErrorState,
  FormField,
  Input,
  Modal,
  MoneyText,
  PageHeader,
  Select,
  StatusPill,
  Textarea,
  useToast,
  type DataTableColumn,
} from '../components/ui'

/**
 * Promotions list, create wizard with conflict/below-cost preview, and end-now action.
 */
export default function PromotionsPage() {
  const { user, can } = useSession()
  const queryClient = useQueryClient()
  const { toast } = useToast()

  const canManage = can('DISCOUNT_MANAGE')
  const [statusFilter, setStatusFilter] = useState<string>('ALL')
  const [createOpen, setCreateOpen] = useState(false)
  const [endingPromo, setEndingPromo] = useState<PromotionSummary | null>(null)

  const promotionsQuery = useQuery({ queryKey: ['promotions'], queryFn: fetchPromotions })
  const locationsQuery = useQuery({ queryKey: ['locations'], queryFn: fetchLocations })

  const endMutation = useMutation({
    mutationFn: (id: string) => endPromotion(id, 'Ended manually'),
    onSuccess: (updated) => {
      queryClient.invalidateQueries({ queryKey: ['promotions'] })
      setEndingPromo(null)
      toast({ title: `Ended "${updated.name}"`, tone: 'info' })
    },
  })

  const toggleEnabledMutation = useMutation({
    mutationFn: (promo: PromotionSummary) => setPromotionEnabled(promo.id, !promo.isEnabled),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['promotions'] }),
  })

  const locations = locationsQuery.data ?? []
  const promotions = useMemo(() => promotionsQuery.data ?? [], [promotionsQuery.data])
  const filtered = useMemo(
    () =>
      statusFilter === 'ALL'
        ? promotions
        : promotions.filter((p) => p.status === (statusFilter as PromotionStatus)),
    [promotions, statusFilter],
  )

  const columns: DataTableColumn<PromotionSummary>[] = [
    {
      key: 'name',
      header: 'Promotion',
      sortable: true,
      sortValue: (p) => p.name,
      render: (p) => (
        <div>
          <span className="font-semibold text-slate-900">{p.name}</span>
          <span className="block text-xs text-slate-500">
            {p.variantIds.length} variant(s) ·{' '}
            {p.allLocations ? 'All locations' : `${p.locationIds.length} location(s)`}
          </span>
        </div>
      ),
    },
    {
      key: 'discount',
      header: 'Discount',
      render: (p) =>
        p.discountType === 'PERCENT' ? (
          <span className="font-medium tabular-nums">{p.value}%</span>
        ) : (
          <MoneyText amount={p.value} className="font-medium" />
        ),
    },
    {
      key: 'scope',
      header: 'Channel',
      render: (p) => <Badge tone="neutral">{p.scope}</Badge>,
    },
    {
      key: 'dates',
      header: 'Schedule',
      render: (p) => (
        <span className="text-xs text-slate-600">
          {p.startsAt ? <DateText value={p.startsAt} /> : 'Immediate'} →{' '}
          {p.endsAt ? <DateText value={p.endsAt} /> : 'No end date'}
        </span>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      render: (p) => <StatusPill status={p.status} />,
    },
    {
      key: 'actions',
      header: 'Actions',
      render: (p) => (
        <div className="flex flex-wrap items-center gap-2">
          {canManage && p.status !== 'EXPIRED' ? (
            <>
              <button
                type="button"
                onClick={() => toggleEnabledMutation.mutate(p)}
                className="text-xs font-medium text-slate-700 hover:underline"
              >
                {p.isEnabled ? 'Disable' : 'Enable'}
              </button>
              <button
                type="button"
                onClick={() => setEndingPromo(p)}
                className="text-xs font-medium text-rose-700 hover:underline"
              >
                End now
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
        title="Promotions"
        description="Channel and location-scoped discounts with conflict protection."
        actions={
          canManage ? <Button onClick={() => setCreateOpen(true)}>New promotion</Button> : undefined
        }
      />

      <div className="flex flex-wrap items-center gap-2">
        {(['ALL', 'ACTIVE', 'SCHEDULED', 'EXPIRED', 'DISABLED'] as const).map((status) => (
          <Button
            key={status}
            size="sm"
            variant={statusFilter === status ? 'primary' : 'outline'}
            onClick={() => setStatusFilter(status)}
          >
            {status === 'ALL' ? 'All' : status.charAt(0) + status.slice(1).toLowerCase()}
          </Button>
        ))}
      </div>

      {toggleEnabledMutation.error ? (
        <ErrorState title="Could not update promotion" error={toggleEnabledMutation.error} />
      ) : null}

      {promotionsQuery.error ? (
        <ErrorState
          title="Could not load promotions"
          error={promotionsQuery.error}
          onRetry={() => promotionsQuery.refetch()}
        />
      ) : (
        <DataTable
          columns={columns}
          rows={filtered}
          getRowKey={(p) => p.id}
          isLoading={promotionsQuery.isPending}
          emptyTitle="No promotions found"
          emptyDescription="Create a promotion for selected products, a category, or a brand."
        />
      )}

      {createOpen ? (
        <CreatePromotionModal
          onClose={() => setCreateOpen(false)}
          existingPromotions={promotions}
          locations={filterLocationsByPermission(user, 'DISCOUNT_MANAGE', locations)}
          isOwner={user?.accountType === 'OWNER'}
          onCreated={() => queryClient.invalidateQueries({ queryKey: ['promotions'] })}
        />
      ) : null}

      <ConfirmDialog
        open={Boolean(endingPromo)}
        onClose={() => setEndingPromo(null)}
        onConfirm={() => endingPromo && endMutation.mutate(endingPromo.id)}
        title="End promotion now?"
        confirmLabel="End now"
        confirmVariant="danger"
        isConfirming={endMutation.isPending}
      >
        {endingPromo ? (
          <p>
            End <strong>{endingPromo.name}</strong> immediately? It will stop applying to new sales.
          </p>
        ) : null}
      </ConfirmDialog>
    </div>
  )
}

function CreatePromotionModal({
  onClose,
  existingPromotions,
  locations,
  isOwner,
  onCreated,
}: {
  onClose: () => void
  existingPromotions: PromotionSummary[]
  locations: ReturnType<typeof filterLocationsByPermission>
  isOwner: boolean
  onCreated: () => void
}) {
  const { toast } = useToast()
  const [step, setStep] = useState<'form' | 'preview'>('form')

  const [name, setName] = useState('')
  const [discountType, setDiscountType] = useState<DiscountType>('PERCENT')
  const [value, setValue] = useState('10')
  const [scope, setScope] = useState<PromotionScope>('POS')
  const [allLocations, setAllLocations] = useState(isOwner)
  const [selectedLocationIds, setSelectedLocationIds] = useState<string[]>(() =>
    !isOwner && locations.length > 0 ? [locations[0].id] : [],
  )
  const [selectorMode, setSelectorMode] = useState<'PRODUCTS' | 'CATEGORY' | 'BRAND'>('PRODUCTS')
  const [selectedProductIds, setSelectedProductIds] = useState<string[]>([])
  const [selectedCategoryId, setSelectedCategoryId] = useState('')
  const [selectedBrandId, setSelectedBrandId] = useState('')
  const [startsAt, setStartsAt] = useState('')
  const [endsAt, setEndsAt] = useState('')
  const [note, setNote] = useState('')
  const [conflictAction, setConflictAction] = useState<ConflictAction>('REFUSE')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [serverConflictDetail, setServerConflictDetail] = useState<string | null>(null)

  const productsQuery = useQuery({
    queryKey: ['catalog', 'products', 'selector'],
    queryFn: () => fetchProducts({ activeOnly: true, size: 200 }),
  })
  const variantsQuery = useQuery({
    queryKey: ['catalog', 'variants', 'selector'],
    queryFn: () => fetchVariants({ activeOnly: true, size: 200 }),
  })
  const categoriesQuery = useQuery({
    queryKey: ['catalog', 'categories', 'active'],
    queryFn: () => fetchCategories(true),
  })
  const brandsQuery = useQuery({
    queryKey: ['catalog', 'brands', 'active'],
    queryFn: () => fetchBrands(true),
  })

  const [openedAt] = useState(() => Date.now())

  const products = useMemo(() => productsQuery.data?.items ?? [], [productsQuery.data])
  const variantsList = useMemo(() => variantsQuery.data?.items ?? [], [variantsQuery.data])

  const expandedVariants = useMemo<VariantSummary[]>(() => {
    const map = new Map<string, VariantSummary>()
    const variantById = new Map(variantsList.map((v) => [v.id, v]))

    const matchingProducts = products.filter((p) => {
      if (selectorMode === 'PRODUCTS') return selectedProductIds.includes(p.id)
      if (selectorMode === 'CATEGORY') return Boolean(selectedCategoryId && p.categoryId === selectedCategoryId)
      return Boolean(selectedBrandId && p.brandId === selectedBrandId)
    })

    for (const product of matchingProducts) {
      for (const variant of product.variants) {
        if (!variant.isActive) continue
        const enriched = variantById.get(variant.id) ?? variant
        map.set(variant.id, enriched)
      }
    }
    return Array.from(map.values())
  }, [
    products,
    variantsList,
    selectorMode,
    selectedProductIds,
    selectedCategoryId,
    selectedBrandId,
  ])

  const selectedVariantIds = useMemo(
    () => expandedVariants.map((v) => v.id),
    [expandedVariants],
  )

  const previewConflicts = useMemo(() => {
    const newStart = startsAt ? new Date(startsAt).getTime() : -Infinity
    const newEnd = endsAt ? new Date(endsAt).getTime() : Infinity

    return existingPromotions.filter((other) => {
      if (other.status === 'EXPIRED' || other.status === 'DISABLED') return false
      if (other.endsAt && new Date(other.endsAt).getTime() <= openedAt) return false

      // Scope overlap
      const scopesOverlap =
        scope === 'BOTH' || other.scope === 'BOTH' || scope === other.scope
      if (!scopesOverlap) return false

      // Date overlap
      const otherStart = other.startsAt ? new Date(other.startsAt).getTime() : -Infinity
      const otherEnd = other.endsAt ? new Date(other.endsAt).getTime() : Infinity
      if (newEnd <= otherStart || otherEnd <= newStart) return false

      // Variant overlap
      const sharesVariant = selectedVariantIds.some((id) => other.variantIds.includes(id))
      if (!sharesVariant) return false

      // Location overlap
      if (allLocations || other.allLocations) return true
      return selectedLocationIds.some((id) => other.locationIds.includes(id))
    })
  }, [
    existingPromotions,
    scope,
    startsAt,
    endsAt,
    selectedVariantIds,
    allLocations,
    selectedLocationIds,
    openedAt,
  ])

  const belowCostVariants = useMemo(() => {
    const val = Number(value)
    if (Number.isNaN(val) || val <= 0) return []
    return expandedVariants.filter((v) => {
      if (v.costPrice === undefined || v.costPrice === null) return false
      const cost = Number(v.costPrice)
      const list = Number(v.sellingPrice)
      if (Number.isNaN(cost) || Number.isNaN(list)) return false
      const net =
        discountType === 'PERCENT' ? list * (1 - val / 100) : Math.max(0, list - val)
      return net < cost
    })
  }, [expandedVariants, discountType, value])

  const createMutation = useMutation({
    mutationFn: () =>
      createPromotion({
        name: name.trim(),
        discountType,
        value: value.trim(),
        scope,
        allLocations,
        locationIds: allLocations ? [] : selectedLocationIds,
        variantIds: selectedVariantIds,
        startsAt: startsAt ? new Date(startsAt).toISOString() : null,
        endsAt: endsAt ? new Date(endsAt).toISOString() : null,
        note: note.trim() || null,
        conflictAction,
      }),
    onSuccess: (created) => {
      toast({ title: `Promotion "${created.name}" created`, tone: 'success' })
      onCreated()
      onClose()
    },
    onError: (caught) => {
      if (caught instanceof ApiError && caught.errorCode === 'PROMOTION_CONFLICT') {
        setServerConflictDetail(caught.message)
      }
    },
  })

  function handleContinueToPreview() {
    const parsed = promotionFormSchema.safeParse({
      name,
      discountType,
      value,
      scope,
      allLocations,
      locationIds: selectedLocationIds,
      variantIds: selectedVariantIds,
      startsAt: startsAt || undefined,
      endsAt: endsAt || undefined,
      note: note || undefined,
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
    setServerConflictDetail(null)
    setStep('preview')
  }

  return (
    <Modal
      open
      onClose={onClose}
      size="lg"
      title={step === 'form' ? 'Create promotion' : 'Preview & confirm promotion'}
    >
      {step === 'form' ? (
        <div className="space-y-4">
          <div className="grid gap-3 sm:grid-cols-2">
            <FormField label="Promotion name" error={fieldErrors.name} required>
              <Input value={name} onChange={(e) => setName(e.target.value)} />
            </FormField>
            <FormField label="Channel scope" required>
              <Select
                value={scope}
                onChange={(e) => setScope(e.target.value as PromotionScope)}
              >
                <option value="POS">POS</option>
                {isOwner ? <option value="ONLINE">Online</option> : null}
                {isOwner ? <option value="BOTH">Both (POS + Online)</option> : null}
              </Select>
            </FormField>
            <FormField label="Discount type" required>
              <Select
                value={discountType}
                onChange={(e) => setDiscountType(e.target.value as DiscountType)}
              >
                <option value="PERCENT">Percentage (%)</option>
                <option value="FIXED">Fixed amount (৳)</option>
              </Select>
            </FormField>
            <FormField label="Discount value" error={fieldErrors.value} required>
              <Input
                type="number"
                step="0.01"
                min="0.01"
                value={value}
                invalid={Boolean(fieldErrors.value)}
                onChange={(e) => setValue(e.target.value)}
              />
            </FormField>
            <FormField label="Starts at (optional)">
              <Input
                type="datetime-local"
                value={startsAt}
                onChange={(e) => setStartsAt(e.target.value)}
              />
            </FormField>
            <FormField label="Ends at (optional)" error={fieldErrors.endsAt}>
              <Input
                type="datetime-local"
                value={endsAt}
                invalid={Boolean(fieldErrors.endsAt)}
                onChange={(e) => setEndsAt(e.target.value)}
              />
            </FormField>
          </div>

          <div className="space-y-2 rounded-lg border border-slate-200 p-3.5">
            <p className="text-xs font-semibold tracking-wide text-slate-600 uppercase">
              Target products (bulk selector)
            </p>
            <div className="flex flex-wrap gap-2">
              {(['PRODUCTS', 'CATEGORY', 'BRAND'] as const).map((mode) => (
                <Button
                  key={mode}
                  type="button"
                  size="sm"
                  variant={selectorMode === mode ? 'primary' : 'outline'}
                  onClick={() => setSelectorMode(mode)}
                >
                  {mode === 'PRODUCTS'
                    ? 'Chosen products'
                    : mode === 'CATEGORY'
                      ? 'By category'
                      : 'By brand'}
                </Button>
              ))}
            </div>

            {selectorMode === 'PRODUCTS' ? (
              <div className="max-h-40 space-y-1.5 overflow-y-auto rounded border border-slate-200 bg-slate-50 p-2.5">
                {products.map((product) => (
                  <Checkbox
                    key={product.id}
                    checked={selectedProductIds.includes(product.id)}
                    onChange={(e) =>
                      setSelectedProductIds((prev) =>
                        e.target.checked
                          ? [...prev, product.id]
                          : prev.filter((id) => id !== product.id),
                      )
                    }
                    label={`${product.name} (${product.variants.filter((v) => v.isActive).length} active variants)`}
                  />
                ))}
              </div>
            ) : null}

            {selectorMode === 'CATEGORY' ? (
              <Select
                value={selectedCategoryId}
                onChange={(e) => setSelectedCategoryId(e.target.value)}
              >
                <option value="">Choose a category</option>
                {(categoriesQuery.data ?? []).map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
              </Select>
            ) : null}

            {selectorMode === 'BRAND' ? (
              <Select
                value={selectedBrandId}
                onChange={(e) => setSelectedBrandId(e.target.value)}
              >
                <option value="">Choose a brand</option>
                {(brandsQuery.data ?? []).map((b) => (
                  <option key={b.id} value={b.id}>
                    {b.name}
                  </option>
                ))}
              </Select>
            ) : null}

            <p className="text-xs text-slate-500">
              Resolved snapshot: <strong>{selectedVariantIds.length}</strong> active variant(s).
            </p>
            {fieldErrors.variantIds ? (
              <p role="alert" className="text-xs font-medium text-rose-700">
                {fieldErrors.variantIds}
              </p>
            ) : null}
          </div>

          <div className="space-y-2 rounded-lg border border-slate-200 p-3.5">
            <p className="text-xs font-semibold tracking-wide text-slate-600 uppercase">
              Locations
            </p>
            {isOwner ? (
              <Checkbox
                checked={allLocations}
                onChange={(e) => setAllLocations(e.target.checked)}
                label="All locations"
              />
            ) : null}
            {!allLocations ? (
              <div className="grid gap-2 sm:grid-cols-2">
                {locations.map((loc) => (
                  <Checkbox
                    key={loc.id}
                    checked={selectedLocationIds.includes(loc.id)}
                    onChange={(e) =>
                      setSelectedLocationIds((prev) =>
                        e.target.checked
                          ? [...prev, loc.id]
                          : prev.filter((id) => id !== loc.id),
                      )
                    }
                    label={`${loc.name} (${loc.code})`}
                  />
                ))}
              </div>
            ) : null}
            {fieldErrors.locationIds ? (
              <p role="alert" className="text-xs font-medium text-rose-700">
                {fieldErrors.locationIds}
              </p>
            ) : null}
          </div>

          <FormField label="Note (optional)">
            <Textarea rows={2} value={note} onChange={(e) => setNote(e.target.value)} />
          </FormField>

          <div className="flex justify-end gap-2 pt-2">
            <Button variant="outline" onClick={onClose}>
              Cancel
            </Button>
            <Button onClick={handleContinueToPreview}>Preview promotion</Button>
          </div>
        </div>
      ) : (
        <div className="space-y-4">
          <div className="rounded-lg border border-slate-200 bg-slate-50 p-4 text-sm">
            <p className="font-semibold text-slate-900">{name}</p>
            <p className="mt-1 text-xs text-slate-600">
              {discountType === 'PERCENT' ? `${value}% off` : `৳ ${value} off`} · Scope: {scope}{' '}
              · {selectedVariantIds.length} variant(s) ·{' '}
              {allLocations ? 'All locations' : `${selectedLocationIds.length} location(s)`}
            </p>
          </div>

          {previewConflicts.length > 0 || serverConflictDetail ? (
            <div
              role="alert"
              className="space-y-2 rounded-lg border border-amber-200 bg-amber-50 p-4 text-sm text-amber-950"
            >
              <p className="font-semibold">
                {serverConflictDetail ? 'PROMOTION_CONFLICT: ' : ''}Conflicting promotions detected
              </p>
              {serverConflictDetail ? (
                <p className="text-xs">{serverConflictDetail}</p>
              ) : null}
              {previewConflicts.length > 0 ? (
                <ul className="list-disc pl-5 text-xs">
                  {previewConflicts.map((c) => (
                    <li key={c.id}>
                      <strong>{c.name}</strong> ({c.scope}, {c.status})
                    </li>
                  ))}
                </ul>
              ) : null}

              <div className="pt-2">
                <Checkbox
                  checked={conflictAction === 'END_EXISTING'}
                  onChange={(e) =>
                    setConflictAction(e.target.checked ? 'END_EXISTING' : 'REFUSE')
                  }
                  label="End the conflicting promotions now"
                  description="Ends overlapping active/scheduled promotions before saving this one."
                />
              </div>
            </div>
          ) : (
            <div className="rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3 text-xs font-medium text-emerald-900">
              No conflicting promotions detected for the selected variants, scope, and dates.
            </div>
          )}

          {belowCostVariants.length > 0 ? (
            <div
              role="alert"
              className="rounded-lg border border-rose-200 bg-rose-50 p-4 text-sm text-rose-900"
            >
              <p className="font-semibold">Below-cost warning</p>
              <p className="mt-0.5 text-xs">
                {belowCostVariants.length} variant(s) will sell below unit cost with this discount:
              </p>
              <ul className="mt-1 list-disc pl-5 font-mono text-xs">
                {belowCostVariants.map((v) => (
                  <li key={v.id}>{v.sku}</li>
                ))}
              </ul>
            </div>
          ) : null}

          {createMutation.isError && !serverConflictDetail ? (
            <ErrorState title="Could not create promotion" error={createMutation.error} />
          ) : null}

          <div className="flex justify-between gap-2 pt-2">
            <Button variant="outline" onClick={() => setStep('form')}>
              Back
            </Button>
            <div className="flex gap-2">
              <Button variant="outline" onClick={onClose}>
                Cancel
              </Button>
              <Button
                onClick={() => createMutation.mutate()}
                isLoading={createMutation.isPending}
                loadingText="Saving..."
              >
                Save promotion
              </Button>
            </div>
          </div>
        </div>
      )}
    </Modal>
  )
}
