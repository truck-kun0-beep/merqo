import { apiRequest } from './client'
import type { DiscountType, PromotionScope, PromotionSummary } from './models'

/**
 * The calls of the discounts.
 *
 * A promotion is never deleted. It is ended or switched off, and both steps
 * have their own endpoint.
 */

/**
 * What the caller wants to do when a promotion fights another one.
 */
export type ConflictAction = 'REFUSE' | 'END_EXISTING'

/**
 * The body of a promotion request.
 */
export interface PromotionRequest {
  name: string
  discountType: DiscountType
  value: string
  scope: PromotionScope
  allLocations: boolean
  locationIds: string[]
  variantIds: string[]
  startsAt?: string | null
  endsAt?: string | null
  note?: string | null
  conflictAction?: ConflictAction
}

/**
 * Reads the promotions the caller may see.
 *
 * @returns the promotions
 */
export function fetchPromotions(): Promise<PromotionSummary[]> {
  return apiRequest<PromotionSummary[]>('/promotions')
}

/**
 * Reads one promotion with what it covers.
 *
 * @param id the promotion
 * @returns the promotion
 */
export function fetchPromotion(id: string): Promise<PromotionSummary> {
  return apiRequest<PromotionSummary>(`/promotions/${id}`)
}

/**
 * Creates a promotion.
 *
 * @param request the new promotion
 * @returns the created promotion
 */
export function createPromotion(request: PromotionRequest): Promise<PromotionSummary> {
  return apiRequest<PromotionSummary>('/promotions', {
    method: 'POST',
    body: JSON.stringify(request),
  })
}

/**
 * Edits a promotion.
 *
 * @param id the promotion
 * @param request the new values
 * @returns the changed promotion
 */
export function updatePromotion(id: string, request: PromotionRequest): Promise<PromotionSummary> {
  return apiRequest<PromotionSummary>(`/promotions/${id}`, {
    method: 'PUT',
    body: JSON.stringify(request),
  })
}

/**
 * Ends a promotion now.
 *
 * @param id the promotion
 * @param note why it ends
 * @returns the ended promotion
 */
export function endPromotion(id: string, note?: string): Promise<PromotionSummary> {
  return apiRequest<PromotionSummary>(`/promotions/${id}/end`, {
    method: 'POST',
    body: JSON.stringify({ note }),
  })
}

/**
 * Switches a promotion on or off.
 *
 * @param id the promotion
 * @param enabled the new state
 * @returns the changed promotion
 */
export function setPromotionEnabled(id: string, enabled: boolean): Promise<PromotionSummary> {
  return apiRequest<PromotionSummary>(`/promotions/${id}/enabled`, {
    method: 'POST',
    body: JSON.stringify({ enabled }),
  })
}
