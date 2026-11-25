import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router-dom'
import {
  activateBrand,
  activateCategory,
  activateProduct,
  createBrand,
  createCategory,
  createProduct,
  deactivateBrand,
  deactivateCategory,
  deactivateProduct,
  fetchBrands,
  fetchCategories,
  fetchProducts,
  lookupBarcode,
  updateBrand,
  updateCategory,
  updateProduct,
} from '../api/catalog'
import type { BrandSummary, CategorySummary, LookupResult, ProductSummary } from '../api/models'
import { productFormSchema } from '../lib/validation'
import { useSession } from '../session/sessionContext'
import { ScanInput } from '../components/ScanInput'
import {
  Badge,
  Button,
  DataTable,
  ErrorState,
  FormField,
  Input,
  Modal,
  MoneyText,
  PageHeader,
  Select,
  StatusPill,
  Tabs,
  Textarea,
  useToast,
  type DataTableColumn,
} from '../components/ui'

/**
 * Catalog products screen with barcode lookup, product CRUD, and category/brand management.
 */
export default function ProductsPage() {
  const { can } = useSession()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { toast } = useToast()

  const canCreate = can('PRODUCT_CREATE')
  const canEdit = can('PRODUCT_EDIT')

  const [search, setSearch] = useState('')
  const [appliedSearch, setAppliedSearch] = useState('')
  const [categoryId, setCategoryId] = useState('')
  const [brandId, setBrandId] = useState('')
  const [activeOnly, setActiveOnly] = useState(false)
  const [page, setPage] = useState(0)

  const [lookupHit, setLookupHit] = useState<LookupResult | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [editingProduct, setEditingProduct] = useState<ProductSummary | null>(null)

  const productsQuery = useQuery({
    queryKey: ['catalog', 'products', appliedSearch, categoryId, brandId, activeOnly, page],
    queryFn: () =>
      fetchProducts({
        q: appliedSearch || undefined,
        categoryId: categoryId || undefined,
        brandId: brandId || undefined,
        activeOnly,
        page,
        size: 25,
      }),
  })

  const categoriesQuery = useQuery({
    queryKey: ['catalog', 'categories'],
    queryFn: () => fetchCategories(false),
  })

  const brandsQuery = useQuery({
    queryKey: ['catalog', 'brands'],
    queryFn: () => fetchBrands(false),
  })

  const toggleMutation = useMutation({
    mutationFn: (product: ProductSummary) =>
      product.isActive ? deactivateProduct(product.id) : activateProduct(product.id),
    onSuccess: (updated) => {
      queryClient.invalidateQueries({ queryKey: ['catalog', 'products'] })
      toast({
        title: `${updated.name} ${updated.isActive ? 'activated' : 'deactivated'}`,
        tone: 'success',
      })
    },
  })

  const categories = categoriesQuery.data ?? []
  const brands = brandsQuery.data ?? []
  const products = productsQuery.data?.items ?? []

  const categoryName = (id: string | null) => categories.find((c) => c.id === id)?.name ?? '-'
  const brandName = (id: string | null) => brands.find((b) => b.id === id)?.name ?? '-'

  const columns: DataTableColumn<ProductSummary>[] = [
    {
      key: 'name',
      header: 'Product',
      sortable: true,
      sortValue: (p) => p.name,
      render: (p) => (
        <div>
          <Link
            to={`/catalog/products/${p.id}`}
            className="font-semibold text-merqo-700 hover:underline"
          >
            {p.name}
          </Link>
          <span className="block font-mono text-xs text-slate-500">{p.productCode}</span>
        </div>
      ),
    },
    {
      key: 'category',
      header: 'Category',
      render: (p) => <span className="text-slate-600">{categoryName(p.categoryId)}</span>,
    },
    {
      key: 'brand',
      header: 'Brand',
      render: (p) => <span className="text-slate-600">{brandName(p.brandId)}</span>,
    },
    {
      key: 'variants',
      header: 'Variants',
      align: 'right',
      render: (p) => (
        <span className="tabular-nums text-slate-700">
          {p.variants.length}{' '}
          <span className="text-xs text-slate-400">
            ({p.variants.filter((v) => v.isActive).length} active)
          </span>
        </span>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      render: (p) => <StatusPill status={p.isActive ? 'ACTIVE' : 'INACTIVE'} />,
    },
    {
      key: 'actions',
      header: 'Actions',
      render: (p) => (
        <div className="flex flex-wrap items-center gap-2">
          <Link
            to={`/catalog/products/${p.id}`}
            className="text-xs font-semibold text-merqo-700 hover:underline"
          >
            Variants
          </Link>
          {canEdit ? (
            <>
              <button
                type="button"
                onClick={() => setEditingProduct(p)}
                className="text-xs font-medium text-slate-600 hover:underline"
              >
                Edit
              </button>
              <button
                type="button"
                onClick={() => toggleMutation.mutate(p)}
                className={`text-xs font-medium hover:underline ${
                  p.isActive ? 'text-rose-700' : 'text-emerald-700'
                }`}
              >
                {p.isActive ? 'Deactivate' : 'Activate'}
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
        title="Catalog"
        description="Products, variants, barcodes, categories, and brands."
        actions={
          <div className="flex flex-wrap items-center gap-2">
            {canCreate || canEdit ? (
              <Link
                to="/catalog/import"
                className="inline-flex h-9 items-center rounded-md border border-slate-300 bg-white px-4 text-sm font-medium text-slate-700 shadow-xs hover:bg-slate-50"
              >
                CSV import
              </Link>
            ) : null}
            {canCreate ? <Button onClick={() => setCreateOpen(true)}>Add product</Button> : null}
          </div>
        }
      />

      <Tabs
        defaultTab="products"
        tabs={[
          {
            id: 'products',
            label: 'Products',
            badge: productsQuery.data ? (
              <Badge tone="neutral">{productsQuery.data.totalItems}</Badge>
            ) : undefined,
            content: (
              <div className="space-y-4">
                <div className="grid gap-4 rounded-xl border border-slate-200 bg-white p-4 shadow-xs lg:grid-cols-2">
                  <ScanInput<LookupResult>
                    label="Scan or type a barcode to find"
                    placeholder="Scan barcode and press Enter..."
                    onScan={(code) => lookupBarcode(code)}
                    onSuccess={(result) => setLookupHit(result)}
                    restoreFocusWhen={!createOpen && !editingProduct}
                  />

                  <form
                    className="flex flex-wrap items-end gap-2"
                    onSubmit={(event) => {
                      event.preventDefault()
                      setPage(0)
                      setAppliedSearch(search.trim())
                    }}
                  >
                    <FormField label="Search products" className="min-w-[180px] flex-1">
                      <Input
                        type="search"
                        value={search}
                        placeholder="Name, code, SKU or barcode"
                        onChange={(e) => setSearch(e.target.value)}
                      />
                    </FormField>
                    <Button type="submit" variant="outline">
                      Search
                    </Button>
                  </form>
                </div>

                {lookupHit ? (
                  <div className="flex flex-wrap items-center justify-between gap-4 rounded-xl border border-merqo-200 bg-merqo-50/70 p-4">
                    <div>
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="text-sm font-semibold text-slate-900">
                          {lookupHit.variant.productName || lookupHit.variant.sku}
                        </span>
                        <Badge tone="primary">{lookupHit.variant.sku}</Badge>
                        {lookupHit.promotion ? (
                          <Badge tone="info">{lookupHit.promotion.name}</Badge>
                        ) : null}
                      </div>
                      <p className="mt-1 text-xs text-slate-600">
                        List: <MoneyText amount={lookupHit.listPrice} /> · Net:{' '}
                        <MoneyText
                          amount={lookupHit.netUnitPrice}
                          className="font-semibold text-slate-900"
                        />
                        {lookupHit.cost !== undefined ? (
                          <>
                            {' '}
                            · Cost: <MoneyText amount={lookupHit.cost} />
                          </>
                        ) : null}
                      </p>
                    </div>
                    <div className="flex items-center gap-2">
                      <Button
                        size="sm"
                        onClick={() =>
                          navigate(
                            `/catalog/products/${lookupHit.variant.productId}?variant=${lookupHit.variant.id}`,
                          )
                        }
                      >
                        Open product
                      </Button>
                      <Button variant="ghost" size="sm" onClick={() => setLookupHit(null)}>
                        Dismiss
                      </Button>
                    </div>
                  </div>
                ) : null}

                <div className="flex flex-wrap items-center gap-3">
                  <Select
                    aria-label="Filter by category"
                    value={categoryId}
                    onChange={(e) => {
                      setCategoryId(e.target.value)
                      setPage(0)
                    }}
                    className="w-full sm:w-48"
                  >
                    <option value="">All categories</option>
                    {categories.map((cat) => (
                      <option key={cat.id} value={cat.id}>
                        {cat.name}
                      </option>
                    ))}
                  </Select>

                  <Select
                    aria-label="Filter by brand"
                    value={brandId}
                    onChange={(e) => {
                      setBrandId(e.target.value)
                      setPage(0)
                    }}
                    className="w-full sm:w-48"
                  >
                    <option value="">All brands</option>
                    {brands.map((brand) => (
                      <option key={brand.id} value={brand.id}>
                        {brand.name}
                      </option>
                    ))}
                  </Select>

                  <label className="inline-flex items-center gap-2 text-sm text-slate-700">
                    <input
                      type="checkbox"
                      checked={activeOnly}
                      onChange={(e) => {
                        setActiveOnly(e.target.checked)
                        setPage(0)
                      }}
                      className="h-4 w-4 rounded border-slate-300 accent-merqo-700"
                    />
                    Active only
                  </label>
                </div>

                {toggleMutation.isError ? (
                  <ErrorState title="Could not update product" error={toggleMutation.error} />
                ) : null}

                {productsQuery.error ? (
                  <ErrorState
                    title="Could not load products"
                    error={productsQuery.error}
                    onRetry={() => productsQuery.refetch()}
                  />
                ) : (
                  <DataTable
                    columns={columns}
                    rows={products}
                    getRowKey={(p) => p.id}
                    isLoading={productsQuery.isPending}
                    emptyTitle="No products found"
                    emptyDescription="Try clearing your filters or add a new product."
                    pagination={
                      productsQuery.data
                        ? {
                            page: productsQuery.data.page,
                            totalPages: productsQuery.data.totalPages,
                            totalItems: productsQuery.data.totalItems,
                            pageSize: productsQuery.data.size,
                            onPageChange: setPage,
                          }
                        : undefined
                    }
                  />
                )}
              </div>
            ),
          },
          {
            id: 'taxonomy',
            label: 'Categories & brands',
            content: (
              <TaxonomyPanel
                categories={categories}
                brands={brands}
                canEdit={canEdit}
                onChanged={() => {
                  queryClient.invalidateQueries({ queryKey: ['catalog', 'categories'] })
                  queryClient.invalidateQueries({ queryKey: ['catalog', 'brands'] })
                }}
              />
            ),
          },
        ]}
      />

      <ProductFormModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        categories={categories}
        brands={brands}
        onSaved={(saved) => navigate(`/catalog/products/${saved.id}`)}
      />

      {editingProduct ? (
        <ProductFormModal
          open
          product={editingProduct}
          onClose={() => setEditingProduct(null)}
          categories={categories}
          brands={brands}
        />
      ) : null}
    </div>
  )
}

function ProductFormModal({
  open,
  onClose,
  product,
  categories,
  brands,
  onSaved,
}: {
  open: boolean
  onClose: () => void
  product?: ProductSummary
  categories: CategorySummary[]
  brands: BrandSummary[]
  onSaved?: (saved: ProductSummary) => void
}) {
  const queryClient = useQueryClient()
  const { toast } = useToast()

  const [productCode, setProductCode] = useState(product?.productCode ?? '')
  const [name, setName] = useState(product?.name ?? '')
  const [categoryId, setCategoryId] = useState(product?.categoryId ?? '')
  const [brandId, setBrandId] = useState(product?.brandId ?? '')
  const [description, setDescription] = useState(product?.description ?? '')
  const [taxRatePercent, setTaxRatePercent] = useState(product?.taxRatePercent ?? '')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})

  const mutation = useMutation({
    mutationFn: () => {
      if (product) {
        return updateProduct(product.id, {
          name: name.trim(),
          categoryId: categoryId || null,
          brandId: brandId || null,
          description: description.trim() || null,
          taxRatePercent: taxRatePercent.trim() || null,
        })
      }
      return createProduct({
        productCode: productCode.trim(),
        name: name.trim(),
        categoryId: categoryId || null,
        brandId: brandId || null,
        description: description.trim() || null,
        taxRatePercent: taxRatePercent.trim() || null,
      })
    },
    onSuccess: (saved) => {
      queryClient.invalidateQueries({ queryKey: ['catalog', 'products'] })
      toast({ title: product ? 'Product updated' : 'Product created', tone: 'success' })
      onClose()
      onSaved?.(saved)
    },
  })

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const parsed = productFormSchema.safeParse({
      productCode: product ? product.productCode : productCode,
      name,
      categoryId,
      brandId,
      description,
      taxRatePercent,
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
    <Modal open={open} onClose={onClose} title={product ? `Edit ${product.name}` : 'New product'}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <div className="grid gap-3 sm:grid-cols-2">
          <FormField label="Product code" error={fieldErrors.productCode} required>
            <Input
              value={productCode}
              disabled={Boolean(product)}
              invalid={Boolean(fieldErrors.productCode)}
              onChange={(e) => setProductCode(e.target.value)}
            />
          </FormField>
          <FormField label="Name" error={fieldErrors.name} required>
            <Input
              value={name}
              invalid={Boolean(fieldErrors.name)}
              onChange={(e) => setName(e.target.value)}
            />
          </FormField>
          <FormField label="Category">
            <Select value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
              <option value="">None</option>
              {categories
                .filter((c) => c.isActive || c.id === categoryId)
                .map((cat) => (
                  <option key={cat.id} value={cat.id}>
                    {cat.name}
                  </option>
                ))}
            </Select>
          </FormField>
          <FormField label="Brand">
            <Select value={brandId} onChange={(e) => setBrandId(e.target.value)}>
              <option value="">None</option>
              {brands
                .filter((b) => b.isActive || b.id === brandId)
                .map((b) => (
                  <option key={b.id} value={b.id}>
                    {b.name}
                  </option>
                ))}
            </Select>
          </FormField>
        </div>

        <FormField
          label="Tax rate (%)"
          hint="Optional override; leave blank to use the global tax rate."
          error={fieldErrors.taxRatePercent}
        >
          <Input
            type="number"
            step="0.01"
            min="0"
            value={taxRatePercent}
            invalid={Boolean(fieldErrors.taxRatePercent)}
            onChange={(e) => setTaxRatePercent(e.target.value)}
          />
        </FormField>

        <FormField label="Description">
          <Textarea value={description} onChange={(e) => setDescription(e.target.value)} />
        </FormField>

        {mutation.isError ? (
          <ErrorState title="Could not save product" error={mutation.error} />
        ) : null}

        <div className="flex justify-end gap-2 pt-2">
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" isLoading={mutation.isPending} loadingText="Saving...">
            {product ? 'Save changes' : 'Create product'}
          </Button>
        </div>
      </form>
    </Modal>
  )
}

function TaxonomyPanel({
  categories,
  brands,
  canEdit,
  onChanged,
}: {
  categories: CategorySummary[]
  brands: BrandSummary[]
  canEdit: boolean
  onChanged: () => void
}) {
  const [newCategoryName, setNewCategoryName] = useState('')
  const [newCategoryParent, setNewCategoryParent] = useState('')
  const [editingCategory, setEditingCategory] = useState<CategorySummary | null>(null)
  const [editCatName, setEditCatName] = useState('')

  const [newBrandName, setNewBrandName] = useState('')
  const [editingBrand, setEditingBrand] = useState<BrandSummary | null>(null)
  const [editBrandName, setEditBrandName] = useState('')

  const createCatMutation = useMutation({
    mutationFn: () =>
      createCategory({ name: newCategoryName.trim(), parentId: newCategoryParent || null }),
    onSuccess: () => {
      setNewCategoryName('')
      setNewCategoryParent('')
      onChanged()
    },
  })

  const updateCatMutation = useMutation({
    mutationFn: () => updateCategory(editingCategory!.id, { name: editCatName.trim() }),
    onSuccess: () => {
      setEditingCategory(null)
      onChanged()
    },
  })

  const toggleCatMutation = useMutation({
    mutationFn: (cat: CategorySummary) =>
      cat.isActive ? deactivateCategory(cat.id) : activateCategory(cat.id),
    onSuccess: onChanged,
  })

  const createBrandMutation = useMutation({
    mutationFn: () => createBrand({ name: newBrandName.trim() }),
    onSuccess: () => {
      setNewBrandName('')
      onChanged()
    },
  })

  const updateBrandMutation = useMutation({
    mutationFn: () => updateBrand(editingBrand!.id, { name: editBrandName.trim() }),
    onSuccess: () => {
      setEditingBrand(null)
      onChanged()
    },
  })

  const toggleBrandMutation = useMutation({
    mutationFn: (brand: BrandSummary) =>
      brand.isActive ? deactivateBrand(brand.id) : activateBrand(brand.id),
    onSuccess: onChanged,
  })

  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <section className="space-y-4 rounded-xl border border-slate-200 bg-white p-5 shadow-xs">
        <h2 className="text-base font-semibold text-slate-900">Categories</h2>

        {canEdit ? (
          <form
            className="flex flex-wrap items-end gap-2"
            onSubmit={(e) => {
              e.preventDefault()
              if (newCategoryName.trim()) createCatMutation.mutate()
            }}
          >
            <FormField label="New category" className="min-w-[160px] flex-1">
              <Input
                value={newCategoryName}
                placeholder="Category name"
                onChange={(e) => setNewCategoryName(e.target.value)}
              />
            </FormField>
            <FormField label="Parent (optional)" className="w-40">
              <Select
                value={newCategoryParent}
                onChange={(e) => setNewCategoryParent(e.target.value)}
              >
                <option value="">None</option>
                {categories.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
              </Select>
            </FormField>
            <Button
              type="submit"
              disabled={!newCategoryName.trim()}
              isLoading={createCatMutation.isPending}
            >
              Add
            </Button>
          </form>
        ) : null}

        {createCatMutation.error || toggleCatMutation.error || updateCatMutation.error ? (
          <ErrorState
            title="Category action failed"
            error={createCatMutation.error ?? toggleCatMutation.error ?? updateCatMutation.error}
          />
        ) : null}

        <ul className="divide-y divide-slate-100 rounded-lg border border-slate-200">
          {categories.map((cat) => (
            <li
              key={cat.id}
              className="flex flex-wrap items-center justify-between gap-2 px-3.5 py-2.5 text-sm"
            >
              {editingCategory?.id === cat.id ? (
                <form
                  className="flex flex-1 items-center gap-2"
                  onSubmit={(e) => {
                    e.preventDefault()
                    updateCatMutation.mutate()
                  }}
                >
                  <Input
                    value={editCatName}
                    onChange={(e) => setEditCatName(e.target.value)}
                    className="h-8 text-xs"
                  />
                  <Button type="submit" size="sm" isLoading={updateCatMutation.isPending}>
                    Save
                  </Button>
                  <Button variant="ghost" size="sm" onClick={() => setEditingCategory(null)}>
                    Cancel
                  </Button>
                </form>
              ) : (
                <>
                  <div className="flex items-center gap-2">
                    <span className="font-medium text-slate-900">{cat.name}</span>
                    <StatusPill status={cat.isActive ? 'ACTIVE' : 'INACTIVE'} />
                  </div>
                  {canEdit ? (
                    <div className="flex items-center gap-2">
                      <button
                        type="button"
                        onClick={() => {
                          setEditingCategory(cat)
                          setEditCatName(cat.name)
                        }}
                        className="text-xs font-medium text-merqo-700 hover:underline"
                      >
                        Rename
                      </button>
                      <button
                        type="button"
                        onClick={() => toggleCatMutation.mutate(cat)}
                        className={`text-xs font-medium hover:underline ${
                          cat.isActive ? 'text-rose-700' : 'text-emerald-700'
                        }`}
                      >
                        {cat.isActive ? 'Deactivate' : 'Activate'}
                      </button>
                    </div>
                  ) : null}
                </>
              )}
            </li>
          ))}
        </ul>
      </section>

      <section className="space-y-4 rounded-xl border border-slate-200 bg-white p-5 shadow-xs">
        <h2 className="text-base font-semibold text-slate-900">Brands</h2>

        {canEdit ? (
          <form
            className="flex flex-wrap items-end gap-2"
            onSubmit={(e) => {
              e.preventDefault()
              if (newBrandName.trim()) createBrandMutation.mutate()
            }}
          >
            <FormField label="New brand" className="min-w-[160px] flex-1">
              <Input
                value={newBrandName}
                placeholder="Brand name"
                onChange={(e) => setNewBrandName(e.target.value)}
              />
            </FormField>
            <Button
              type="submit"
              disabled={!newBrandName.trim()}
              isLoading={createBrandMutation.isPending}
            >
              Add
            </Button>
          </form>
        ) : null}

        {createBrandMutation.error || toggleBrandMutation.error || updateBrandMutation.error ? (
          <ErrorState
            title="Brand action failed"
            error={
              createBrandMutation.error ?? toggleBrandMutation.error ?? updateBrandMutation.error
            }
          />
        ) : null}

        <ul className="divide-y divide-slate-100 rounded-lg border border-slate-200">
          {brands.map((brand) => (
            <li
              key={brand.id}
              className="flex flex-wrap items-center justify-between gap-2 px-3.5 py-2.5 text-sm"
            >
              {editingBrand?.id === brand.id ? (
                <form
                  className="flex flex-1 items-center gap-2"
                  onSubmit={(e) => {
                    e.preventDefault()
                    updateBrandMutation.mutate()
                  }}
                >
                  <Input
                    value={editBrandName}
                    onChange={(e) => setEditBrandName(e.target.value)}
                    className="h-8 text-xs"
                  />
                  <Button type="submit" size="sm" isLoading={updateBrandMutation.isPending}>
                    Save
                  </Button>
                  <Button variant="ghost" size="sm" onClick={() => setEditingBrand(null)}>
                    Cancel
                  </Button>
                </form>
              ) : (
                <>
                  <div className="flex items-center gap-2">
                    <span className="font-medium text-slate-900">{brand.name}</span>
                    <StatusPill status={brand.isActive ? 'ACTIVE' : 'INACTIVE'} />
                  </div>
                  {canEdit ? (
                    <div className="flex items-center gap-2">
                      <button
                        type="button"
                        onClick={() => {
                          setEditingBrand(brand)
                          setEditBrandName(brand.name)
                        }}
                        className="text-xs font-medium text-merqo-700 hover:underline"
                      >
                        Rename
                      </button>
                      <button
                        type="button"
                        onClick={() => toggleBrandMutation.mutate(brand)}
                        className={`text-xs font-medium hover:underline ${
                          brand.isActive ? 'text-rose-700' : 'text-emerald-700'
                        }`}
                      >
                        {brand.isActive ? 'Deactivate' : 'Activate'}
                      </button>
                    </div>
                  ) : null}
                </>
              )}
            </li>
          ))}
        </ul>
      </section>
    </div>
  )
}
