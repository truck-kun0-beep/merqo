import { apiRequest } from './client'
import type {
  AdjustmentDetail,
  AdjustmentReason,
  InventoryPolicyRow,
  LocationTotal,
  PageResponse,
  StockAdjustmentRow,
  StockCountDetail,
  StockCountRow,
  StockMovementRow,
  StockRow,
} from './models'

/**
 * The calls of the stock: the balances, the adjustments, the policies and the
 * counts.
 *
 * Every list is scoped by the backend, so the location in the query is a filter
 * inside the scope of the caller and never a way out of it.
 */

/**
 * The filters of the stock list.
 */
export interface StockFilters {
  locationId?: string
  q?: string
  lowStockOnly?: boolean
  includeInactive?: boolean
}

/**
 * The filters of the movement list.
 */
export interface MovementFilters {
  locationId?: string
  variantId?: string
  page?: number
  size?: number
}

/**
 * One line of a stock adjustment.
 */
export interface AdjustmentLineRequest {
  variantId: string
  quantityDelta: number
}

/**
 * The body of a create adjustment request.
 */
export interface CreateAdjustmentRequest {
  locationId: string
  reason: AdjustmentReason
  note?: string | null
  lines: AdjustmentLineRequest[]
}

/**
 * The body of a save policy request.
 */
export interface SavePolicyRequest {
  locationId: string
  variantId: string
  reorderPoint: number
  targetStock: number
  onlineSafetyStock: number
}

/**
 * The body of a count line request.
 */
export interface CountLineRequest {
  variantId: string
  countedQuantity: number
}

/**
 * Reads the stock of the locations the caller may see.
 *
 * @param filters the filters of the list
 * @returns the rows, one per location and variant
 */
export function fetchStock(filters: StockFilters = {}): Promise<StockRow[]> {
  return apiRequest<StockRow[]>(`/inventory/stock${toQueryString(filters)}`)
}

/**
 * Reads the totals of every location the caller may see.
 *
 * @returns the totals, one per location
 */
export function fetchLocationTotals(): Promise<LocationTotal[]> {
  return apiRequest<LocationTotal[]>('/inventory/stock/totals')
}

/**
 * Reads a page of the movements.
 *
 * @param filters the filters of the list
 * @returns the page of movements
 */
export function fetchMovements(
  filters: MovementFilters = {},
): Promise<PageResponse<StockMovementRow>> {
  return apiRequest<PageResponse<StockMovementRow>>(`/inventory/movements${toQueryString(filters)}`)
}

/**
 * Creates a stock adjustment. All lines are applied or none of them is.
 *
 * @param request the adjustment
 * @returns the created adjustment
 */
export function createAdjustment(request: CreateAdjustmentRequest): Promise<StockAdjustmentRow> {
  return apiRequest<StockAdjustmentRow>('/inventory/adjustments', {
    method: 'POST',
    body: JSON.stringify(request),
  })
}

/**
 * Reads the adjustments of the locations the caller may see.
 *
 * @param locationId the location filter
 * @returns the adjustments
 */
export function fetchAdjustments(locationId?: string): Promise<StockAdjustmentRow[]> {
  return apiRequest<StockAdjustmentRow[]>(`/inventory/adjustments${toQueryString({ locationId })}`)
}

/**
 * Reads one adjustment with its lines.
 *
 * @param id the adjustment
 * @returns the adjustment with its lines
 */
export function fetchAdjustmentLines(id: string): Promise<AdjustmentDetail> {
  return apiRequest<AdjustmentDetail>(`/inventory/adjustments/${id}`)
}

/**
 * Changes the reorder point of a variant at a location.
 *
 * @param request the policy
 * @returns the changed policy
 */
export function saveInventoryPolicy(request: SavePolicyRequest): Promise<InventoryPolicyRow> {
  return apiRequest<InventoryPolicyRow>('/inventory/policies', {
    method: 'PUT',
    body: JSON.stringify(request),
  })
}

/**
 * Reads the policy of a variant at a location.
 *
 * The answer is empty when the variant has no policy at that location yet.
 *
 * @param locationId the location
 * @param variantId the variant
 * @returns the policy, or an empty object
 */
export function fetchInventoryPolicy(
  locationId: string,
  variantId: string,
): Promise<Partial<InventoryPolicyRow>> {
  return apiRequest<Partial<InventoryPolicyRow>>(
    `/inventory/policies${toQueryString({ locationId, variantId })}`,
  )
}

/**
 * Reads the counts of the locations the caller may see.
 *
 * @param locationId the location filter
 * @returns the counts
 */
export function fetchStockCounts(locationId?: string): Promise<StockCountRow[]> {
  return apiRequest<StockCountRow[]>(`/inventory/counts${toQueryString({ locationId })}`)
}

/**
 * Starts a count of one location.
 *
 * @param request the count to start
 * @returns the new count
 */
export function startStockCount(request: {
  locationId: string
  categoryId?: string | null
}): Promise<StockCountRow> {
  return apiRequest<StockCountRow>('/inventory/counts', {
    method: 'POST',
    body: JSON.stringify(request),
  })
}

/**
 * Reads one count with its lines.
 *
 * @param id the count
 * @returns the count with its lines
 */
export function fetchStockCount(id: string): Promise<StockCountDetail> {
  return apiRequest<StockCountDetail>(`/inventory/counts/${id}`)
}

/**
 * Writes the quantity the counter counted for one line.
 *
 * @param id the count
 * @param request the counted quantity
 * @returns the changed count
 */
export function saveStockCountLine(
  id: string,
  request: CountLineRequest,
): Promise<StockCountDetail> {
  return apiRequest<StockCountDetail>(`/inventory/counts/${id}/lines`, {
    method: 'PUT',
    body: JSON.stringify(request),
  })
}

/**
 * Submits a count. The variances become visible.
 *
 * @param id the count
 * @returns the submitted count
 */
export function submitStockCount(id: string): Promise<StockCountRow> {
  return apiRequest<StockCountRow>(`/inventory/counts/${id}/submit`, { method: 'POST' })
}

/**
 * Applies a count. The variances change the stock.
 *
 * @param id the count
 * @returns the applied count
 */
export function applyStockCount(id: string): Promise<StockCountRow> {
  return apiRequest<StockCountRow>(`/inventory/counts/${id}/apply`, { method: 'POST' })
}

/**
 * Cancels a count.
 *
 * @param id the count
 * @returns the cancelled count
 */
export function cancelStockCount(id: string): Promise<StockCountRow> {
  return apiRequest<StockCountRow>(`/inventory/counts/${id}/cancel`, { method: 'POST' })
}

/**
 * Builds a query string from the filters that are actually set.
 *
 * @param filters the filters
 * @returns the query string, with a leading question mark or empty
 */
function toQueryString(filters: object): string {
  const parts: string[] = []
  for (const [key, value] of Object.entries(filters)) {
    if (value === undefined || value === '') {
      continue
    }
    parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
  }
  return parts.length ? `?${parts.join('&')}` : ''
}
