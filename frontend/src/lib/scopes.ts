import type { CurrentUser, LocationSummary } from '../api/models'

/**
 * Checks whether the signed-in user holds a permission, optionally at a specific location.
 *
 * The backend includes every permission in `/auth/me` for the owner with empty `locationIds`,
 * so `accountType === 'OWNER'` resolves to all locations when the permission is present.
 */
export function hasLocationPermission(
  user: CurrentUser | undefined,
  permissionCode: string,
  locationId?: string,
): boolean {
  if (!user) {
    return false
  }
  const perm = user.permissions.find((entry) => entry.code === permissionCode)
  if (!perm) {
    return false
  }
  if (user.accountType === 'OWNER' || perm.scope === 'ALL') {
    return true
  }
  if (!locationId) {
    return perm.locationIds.length > 0
  }
  return perm.locationIds.includes(locationId)
}

/**
 * Filters a list of locations to only those where the caller holds `permissionCode`.
 */
export function filterLocationsByPermission(
  user: CurrentUser | undefined,
  permissionCode: string,
  locations: LocationSummary[],
): LocationSummary[] {
  if (!user) {
    return []
  }
  const perm = user.permissions.find((entry) => entry.code === permissionCode)
  if (!perm) {
    return []
  }
  if (user.accountType === 'OWNER' || perm.scope === 'ALL') {
    return locations
  }
  return locations.filter((loc) => perm.locationIds.includes(loc.id))
}

/**
 * Returns whether the caller may view cost / stock-value figures.
 */
export function canSeeCosts(user: CurrentUser | undefined): boolean {
  if (!user) {
    return false
  }
  return user.permissions.some(
    (perm) => perm.code === 'COST_VIEW' || perm.code === 'PRICE_EDIT',
  )
}
