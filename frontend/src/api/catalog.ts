import { apiRequest, apiText, toQueryString } from './client'
import type {
  BrandSummary,
  CategorySummary,
  ImportCommitResult,
  ImportValidation,
  LookupResult,
  PageResponse,
  PricePreviewResult,
  ProductSummary,
  VariantDetail,
  VariantSummary,
} from './models'

/**
 * The calls of the catalogue.
 *
 * Every function is a thin wrapper around one endpoint. The frontend never
 * decides what the caller may see: the backend scopes every list by the
 * locations of the signed in user, and an id sent from here is never trusted.
 */

/**
 * The channel a price is calculated for.
 */
export type PriceScope = 'POS' | 'ONLINE' | 'BOTH'

/**
 * The body of a create product request.
 */
export interface CreateProductRequest {
  productCode: string
  name: string
  categoryId?: string | null
  brandId?: string | null
  description?: string | null
  taxRatePercent?: string | null
}

/**
 * The body of an edit product request.
 */
export interface UpdateProductRequest {
  name?: string
  categoryId?: string | null
  brandId?: string | null
  description?: string | null
  taxRatePercent?: string | null
}

/**
 * The body of a create variant request.
 */
export interface CreateVariantRequest {
  sku?: string
  color?: string | null
  size?: string | null
  otherAttribute?: string | null
  sellingPrice: string
  barcode?: string
}

/**
 * The body of an edit variant request.
 */
export interface UpdateVariantRequest {
  sku?: string
  color?: string | null
  size?: string | null
  otherAttribute?: string | null
}

/**
 * The filters of the product list.
 */
export interface ProductFilters {
  q?: string
  categoryId?: string
  brandId?: string
  activeOnly?: boolean
  page?: number
  size?: number
}

/**
 * The filters of the variant list.
 */
export interface VariantFilters {
  q?: string
  activeOnly?: boolean
  page?: number
  size?: number
}

/**
 * Reads the categories of the catalogue.
 *
 * @param activeOnly whether only the active categories are wanted
 * @returns the categories
 */
export function fetchCategories(activeOnly = false): Promise<CategorySummary[]> {
  return apiRequest<CategorySummary[]>(`/catalog/categories${toQueryString({ activeOnly })}`)
}

/**
 * Creates a category.
 *
 * @param request the new category
 * @returns the created category
 */
export function createCategory(request: {
  name: string
  parentId?: string | null
}): Promise<CategorySummary> {
  return apiRequest<CategorySummary>('/catalog/categories', {
    method: 'POST',
    body: JSON.stringify(request),
  })
}

/**
 * Edits a category.
 *
 * @param id the category
 * @param request the new values
 * @returns the changed category
 */
export function updateCategory(
  id: string,
  request: { name?: string; parentId?: string | null },
): Promise<CategorySummary> {
  return apiRequest<CategorySummary>(`/catalog/categories/${id}`, {
    method: 'PUT',
    body: JSON.stringify(request),
  })
}

/**
 * Deactivates a category.
 *
 * @param id the category
 * @returns the deactivated category
 */
export function deactivateCategory(id: string): Promise<CategorySummary> {
  return apiRequest<CategorySummary>(`/catalog/categories/${id}/deactivate`, { method: 'POST' })
}

/**
 * Activates a category again.
 *
 * @param id the category
 * @returns the activated category
 */
export function activateCategory(id: string): Promise<CategorySummary> {
  return apiRequest<CategorySummary>(`/catalog/categories/${id}/activate`, { method: 'POST' })
}

/**
 * Reads the brands of the catalogue.
 *
 * @param activeOnly whether only the active brands are wanted
 * @returns the brands
 */
export function fetchBrands(activeOnly = false): Promise<BrandSummary[]> {
  return apiRequest<BrandSummary[]>(`/catalog/brands${toQueryString({ activeOnly })}`)
}

/**
 * Creates a brand.
 *
 * @param request the new brand
 * @returns the created brand
 */
export function createBrand(request: { name: string }): Promise<BrandSummary> {
  return apiRequest<BrandSummary>('/catalog/brands', {
    method: 'POST',
    body: JSON.stringify(request),
  })
}

/**
 * Edits a brand.
 *
 * @param id the brand
 * @param request the new values
 * @returns the changed brand
 */
export function updateBrand(id: string, request: { name?: string }): Promise<BrandSummary> {
  return apiRequest<BrandSummary>(`/catalog/brands/${id}`, {
    method: 'PUT',
    body: JSON.stringify(request),
  })
}

/**
 * Deactivates a brand.
 *
 * @param id the brand
 * @returns the deactivated brand
 */
export function deactivateBrand(id: string): Promise<BrandSummary> {
  return apiRequest<BrandSummary>(`/catalog/brands/${id}/deactivate`, { method: 'POST' })
}

/**
 * Activates a brand again.
 *
 * @param id the brand
 * @returns the activated brand
 */
export function activateBrand(id: string): Promise<BrandSummary> {
  return apiRequest<BrandSummary>(`/catalog/brands/${id}/activate`, { method: 'POST' })
}

/**
 * Reads a page of the products.
 *
 * @param filters the search filters
 * @returns the page of products
 */
export function fetchProducts(filters: ProductFilters = {}): Promise<PageResponse<ProductSummary>> {
  return apiRequest<PageResponse<ProductSummary>>(`/catalog/products${toQueryString(filters)}`)
}

/**
 * Reads one product with its variants.
 *
 * @param id the product
 * @returns the product
 */
export function fetchProduct(id: string): Promise<ProductSummary> {
  return apiRequest<ProductSummary>(`/catalog/products/${id}`)
}

/**
 * Creates a product.
 *
 * @param request the new product
 * @returns the created product
 */
export function createProduct(request: CreateProductRequest): Promise<ProductSummary> {
  return apiRequest<ProductSummary>('/catalog/products', {
    method: 'POST',
    body: JSON.stringify(request),
  })
}

/**
 * Edits a product.
 *
 * @param id the product
 * @param request the new values
 * @returns the changed product
 */
export function updateProduct(id: string, request: UpdateProductRequest): Promise<ProductSummary> {
  return apiRequest<ProductSummary>(`/catalog/products/${id}`, {
    method: 'PUT',
    body: JSON.stringify(request),
  })
}

/**
 * Deactivates a product.
 *
 * @param id the product
 * @returns the deactivated product
 */
export function deactivateProduct(id: string): Promise<ProductSummary> {
  return apiRequest<ProductSummary>(`/catalog/products/${id}/deactivate`, { method: 'POST' })
}

/**
 * Activates a product again.
 *
 * @param id the product
 * @returns the activated product
 */
export function activateProduct(id: string): Promise<ProductSummary> {
  return apiRequest<ProductSummary>(`/catalog/products/${id}/activate`, { method: 'POST' })
}

/**
 * Reads a page of the variants.
 *
 * @param filters the search filters
 * @returns the page of variants
 */
export function fetchVariants(filters: VariantFilters = {}): Promise<PageResponse<VariantSummary>> {
  return apiRequest<PageResponse<VariantSummary>>(`/catalog/variants${toQueryString(filters)}`)
}

/**
 * Reads one variant with its barcodes.
 *
 * @param id the variant
 * @returns the variant
 */
export function fetchVariant(id: string): Promise<VariantDetail> {
  return apiRequest<VariantDetail>(`/catalog/variants/${id}`)
}

/**
 * Creates a variant of a product.
 *
 * @param productId the product
 * @param request the new variant
 * @returns the created variant
 */
export function createVariant(
  productId: string,
  request: CreateVariantRequest,
): Promise<VariantSummary> {
  return apiRequest<VariantSummary>(`/catalog/products/${productId}/variants`, {
    method: 'POST',
    body: JSON.stringify(request),
  })
}

/**
 * Edits the details of a variant. The price has its own endpoint.
 *
 * @param id the variant
 * @param request the new values
 * @returns the changed variant
 */
export function updateVariant(id: string, request: UpdateVariantRequest): Promise<VariantSummary> {
  return apiRequest<VariantSummary>(`/catalog/variants/${id}`, {
    method: 'PUT',
    body: JSON.stringify(request),
  })
}

/**
 * Deactivates a variant.
 *
 * @param id the variant
 * @returns the deactivated variant
 */
export function deactivateVariant(id: string): Promise<VariantSummary> {
  return apiRequest<VariantSummary>(`/catalog/variants/${id}/deactivate`, { method: 'POST' })
}

/**
 * Activates a variant again.
 *
 * @param id the variant
 * @returns the activated variant
 */
export function activateVariant(id: string): Promise<VariantSummary> {
  return apiRequest<VariantSummary>(`/catalog/variants/${id}/activate`, { method: 'POST' })
}

/**
 * Changes the selling price of a variant.
 *
 * @param id the variant
 * @param request the new price
 * @returns the changed variant
 */
export function changeVariantPrice(
  id: string,
  request: { sellingPrice: string },
): Promise<VariantSummary> {
  return apiRequest<VariantSummary>(`/catalog/variants/${id}/price`, {
    method: 'POST',
    body: JSON.stringify(request),
  })
}

/**
 * Changes the cost of a variant.
 *
 * @param id the variant
 * @param request the new cost
 * @returns the changed variant
 */
export function changeVariantCost(
  id: string,
  request: { costPrice: string },
): Promise<VariantSummary> {
  return apiRequest<VariantSummary>(`/catalog/variants/${id}/cost`, {
    method: 'POST',
    body: JSON.stringify(request),
  })
}

/**
 * Sets the primary barcode of a variant.
 *
 * @param id the variant
 * @param request the barcode
 * @returns the changed variant with its barcodes
 */
export function setPrimaryBarcode(
  id: string,
  request: { barcode: string },
): Promise<VariantDetail> {
  return apiRequest<VariantDetail>(`/catalog/variants/${id}/barcode`, {
    method: 'POST',
    body: JSON.stringify(request),
  })
}

/**
 * Adds an alternate barcode to a variant.
 *
 * @param id the variant
 * @param request the barcode
 * @returns the changed variant with its barcodes
 */
export function addAlternateBarcode(
  id: string,
  request: { barcode: string },
): Promise<VariantDetail> {
  return apiRequest<VariantDetail>(`/catalog/variants/${id}/barcodes`, {
    method: 'POST',
    body: JSON.stringify(request),
  })
}

/**
 * Removes a barcode of a variant.
 *
 * @param variantId the variant
 * @param barcodeId the barcode
 * @returns the changed variant with its barcodes
 */
export function removeBarcode(variantId: string, barcodeId: string): Promise<VariantDetail> {
  return apiRequest<VariantDetail>(`/catalog/variants/${variantId}/barcodes/${barcodeId}`, {
    method: 'DELETE',
  })
}

/**
 * Looks a scanned barcode up and answers with the variant and its price.
 *
 * @param barcode the scanned code
 * @param options the channel and the location of the purchase
 * @returns the variant with its price breakdown
 */
export function lookupBarcode(
  barcode: string,
  options: { scope?: PriceScope; locationId?: string } = {},
): Promise<LookupResult> {
  return apiRequest<LookupResult>(
    `/catalog/lookup${toQueryString({ barcode, scope: options.scope, locationId: options.locationId })}`,
  )
}

/**
 * Reads the price of a variant, so that the price can be checked in the browser.
 *
 * @param variantId the variant
 * @param options the channel, the location and the quantity
 * @returns the price breakdown
 */
export function previewPrice(
  variantId: string,
  options: { scope?: PriceScope; locationId?: string; quantity?: number } = {},
): Promise<PricePreviewResult> {
  return apiRequest<PricePreviewResult>(
    `/catalog/variants/${variantId}/price-preview${toQueryString({
      scope: options.scope,
      locationId: options.locationId,
      quantity: options.quantity,
    })}`,
  )
}

/**
 * Checks a CSV file of products and opening stock. Nothing is written.
 *
 * The file is uploaded as a form, so the browser writes the boundary of the
 * multipart body itself.
 *
 * @param file the uploaded file
 * @param openingLocationId the location the opening stock would go to
 * @returns the errors and the summary of the file
 */
export function validateImport(file: File, openingLocationId?: string): Promise<ImportValidation> {
  return apiRequest<ImportValidation>(
    `/catalog/import/validate${toQueryString({ openingLocationId })}`,
    {
      method: 'POST',
      body: toUploadBody(file),
    },
  )
}

/**
 * Applies a CSV file of products and opening stock.
 *
 * The file is uploaded as a form, so the browser writes the boundary of the
 * multipart body itself.
 *
 * @param file the uploaded file
 * @param openingLocationId the location the opening stock goes to
 * @returns what was written
 */
export function commitImport(file: File, openingLocationId?: string): Promise<ImportCommitResult> {
  return apiRequest<ImportCommitResult>(
    `/catalog/import/commit${toQueryString({ openingLocationId })}`,
    {
      method: 'POST',
      body: toUploadBody(file),
    },
  )
}

/**
 * Reads the template of the import file.
 *
 * The answer is CSV text and not JSON, so it is read as text.
 *
 * @returns the template as CSV text
 */
export function fetchImportTemplate(): Promise<string> {
  return apiText('/catalog/import/template')
}

/**
 * Builds the multipart body of an upload.
 *
 * @param file the uploaded file
 * @returns the form that carries the file
 */
function toUploadBody(file: File): FormData {
  const form = new FormData()
  form.append('file', file)
  return form
}
