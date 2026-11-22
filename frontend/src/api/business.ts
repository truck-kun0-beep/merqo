import { apiRequest } from './client'
import type {
  AssignmentSummary,
  ExplainedPermission,
  LocationDetail,
  LocationSummary,
  PageResponse,
  PermissionCatalogueEntry,
  PermissionTemplate,
  AuditLogRow,
  SettingsGroups,
  UserDetail,
  UserOverride,
  UserSummary,
  UserTemplateLink,
} from './models'

/**
 * The business calls of Merqo that Prompt 1 needs.
 *
 * Every function is a thin wrapper around one endpoint. The frontend never
 * decides what the caller may see: the backend scopes every list by the
 * locations of the signed in user, and a branch id sent from here is never
 * trusted.
 */

/**
 * Reads the locations the signed in user may see.
 *
 * @returns the locations
 */
export function fetchLocations(): Promise<LocationSummary[]> {
  return apiRequest<LocationSummary[]>('/locations')
}

/**
 * Reads one location with its assignments.
 *
 * @param id the location id
 * @returns the location
 */
export function fetchLocation(id: string): Promise<LocationDetail> {
  return apiRequest<LocationDetail>(`/locations/${id}`)
}

/**
 * Creates a location. Only the owner may do this.
 *
 * @param request the new location
 * @returns the created location
 */
export function createLocation(request: {
  code: string
  name: string
  locationType: 'BRANCH' | 'WAREHOUSE'
  address?: string
  phone?: string
}): Promise<LocationSummary> {
  return apiRequest<LocationSummary>('/locations', {
    method: 'POST',
    body: JSON.stringify(request),
  })
}

/**
 * Edits a location. Only the owner may do this.
 *
 * @param id the location id
 * @param request the new values
 * @returns the changed location
 */
export function updateLocation(
  id: string,
  request: { name?: string; address?: string; phone?: string },
): Promise<LocationSummary> {
  return apiRequest<LocationSummary>(`/locations/${id}`, {
    method: 'PUT',
    body: JSON.stringify(request),
  })
}

/**
 * Deactivates a location. Only the owner may do this.
 *
 * @param id the location id
 * @returns the deactivated location
 */
export function deactivateLocation(id: string): Promise<LocationSummary> {
  return apiRequest<LocationSummary>(`/locations/${id}/deactivate`, { method: 'POST' })
}

/**
 * Activates a location again. Only the owner may do this.
 *
 * @param id the location id
 * @returns the activated location
 */
export function activateLocation(id: string): Promise<LocationSummary> {
  return apiRequest<LocationSummary>(`/locations/${id}/activate`, { method: 'POST' })
}

/**
 * Makes one warehouse the online fulfilment location.
 *
 * @param id the location id
 * @returns the changed location
 */
export function setOnlineFulfillment(id: string): Promise<LocationSummary> {
  return apiRequest<LocationSummary>(`/locations/${id}/online-fulfillment`, { method: 'POST' })
}

/**
 * Reads the assignments of a location.
 *
 * @param locationId the location id
 * @returns the assignments
 */
export function fetchLocationAssignments(locationId: string): Promise<AssignmentSummary[]> {
  return apiRequest<AssignmentSummary[]>(`/locations/${locationId}/assignments`)
}

/**
 * Assigns a user to a location.
 *
 * @param request the assignment
 * @returns the new assignment
 */
export function createAssignment(request: {
  userId: string
  locationId: string
  assignmentType: 'MANAGER' | 'STAFF'
  replaceExisting?: boolean
}): Promise<AssignmentSummary> {
  return apiRequest<AssignmentSummary>('/assignments', {
    method: 'POST',
    body: JSON.stringify({ replaceExisting: false, ...request }),
  })
}

/**
 * Ends an assignment. The row stays in the table.
 *
 * @param id the assignment id
 * @returns the ended assignment
 */
export function endAssignment(id: string): Promise<AssignmentSummary> {
  return apiRequest<AssignmentSummary>(`/assignments/${id}/end`, { method: 'POST' })
}

/**
 * Reads the accounts the signed in user may see.
 *
 * @param filters the search filters
 * @returns the page of accounts
 */
export function fetchUsers(
  filters: {
    q?: string
    accountType?: string
    active?: boolean
    locationId?: string
    page?: number
    size?: number
  } = {},
): Promise<PageResponse<UserSummary>> {
  return apiRequest<PageResponse<UserSummary>>(`/users${toQueryString(filters)}`)
}

/**
 * Reads one account.
 *
 * @param id the account id
 * @returns the account
 */
export function fetchUser(id: string): Promise<UserDetail> {
  return apiRequest<UserDetail>(`/users/${id}`)
}

/**
 * Edits the profile of an account.
 *
 * @param id the account id
 * @param request the new values
 * @returns the changed account
 */
export function updateUser(
  id: string,
  request: {
    fullName?: string
    phone?: string
    email?: string
    maxDiscountPercent?: string
  },
): Promise<UserSummary> {
  return apiRequest<UserSummary>(`/users/${id}`, {
    method: 'PUT',
    body: JSON.stringify(request),
  })
}

/**
 * Reads the effective permissions of an account, with the reason for each one.
 *
 * @param id the account id
 * @returns the explained permissions
 */
export function fetchEffectivePermissions(id: string): Promise<ExplainedPermission[]> {
  return apiRequest<ExplainedPermission[]>(`/users/${id}/effective-permissions`)
}

/**
 * Replaces the templates of an account.
 *
 * @param id the account id
 * @param templateIds the templates the account holds
 * @returns the templates of the account
 */
export function replaceUserTemplates(
  id: string,
  templateIds: string[],
): Promise<UserTemplateLink[]> {
  return apiRequest<UserTemplateLink[]>(`/users/${id}/templates`, {
    method: 'PUT',
    body: JSON.stringify({ templateIds }),
  })
}

/**
 * Replaces the overrides of an account.
 *
 * @param id the account id
 * @param overrides the overrides
 * @returns the stored overrides
 */
export function replaceUserOverrides(
  id: string,
  overrides: Array<{ permission: string; effect: 'ALLOW' | 'DENY'; locationId: string | null }>,
): Promise<UserOverride[]> {
  return apiRequest<UserOverride[]>(`/users/${id}/overrides`, {
    method: 'PUT',
    body: JSON.stringify({ overrides }),
  })
}

/**
 * Replaces the delegation list of a manager. Only the owner may do this.
 *
 * @param id the manager id
 * @param permissions the permissions the manager may delegate
 * @returns the stored delegations
 */
export function replaceUserDelegations(
  id: string,
  permissions: string[],
): Promise<Array<{ permission: string }>> {
  return apiRequest<Array<{ permission: string }>>(`/users/${id}/delegations`, {
    method: 'PUT',
    body: JSON.stringify({ permissions }),
  })
}

/**
 * Reads the assignments of an account.
 *
 * @param id the account id
 * @returns the assignments
 */
export function fetchUserAssignments(id: string): Promise<AssignmentSummary[]> {
  return apiRequest<AssignmentSummary[]>(`/users/${id}/assignments`)
}

/**
 * Creates an account.
 *
 * @param request the new account
 * @returns the created account and, when the backend generated one, the temporary password
 */
export function createUser(request: {
  username: string
  email?: string
  fullName?: string
  phone?: string
  accountType: 'OWNER' | 'MANAGER' | 'STAFF'
  password?: string
  maxDiscountPercent?: string
  templateIds?: string[]
}): Promise<UserSummary & { temporaryPassword?: string }> {
  return apiRequest<UserSummary & { temporaryPassword?: string }>('/users/create', {
    method: 'POST',
    body: JSON.stringify(request),
  })
}

/**
 * Deactivates an account.
 *
 * @param id the account id
 * @returns the deactivated account
 */
export function deactivateUser(id: string): Promise<UserSummary> {
  return apiRequest<UserSummary>(`/users/${id}/deactivate`, { method: 'POST' })
}

/**
 * Activates an account again.
 *
 * @param id the account id
 * @returns the activated account
 */
export function activateUser(id: string): Promise<UserSummary> {
  return apiRequest<UserSummary>(`/users/${id}/activate`, { method: 'POST' })
}

/**
 * Generates a temporary password for an account.
 *
 * @param id the account id
 * @returns the temporary password, which is shown once
 */
export function resetUserPassword(id: string): Promise<{ temporaryPassword: string }> {
  return apiRequest<{ temporaryPassword: string }>(`/users/${id}/reset-password`, {
    method: 'POST',
  })
}

/**
 * Reads the permission catalogue, grouped by module.
 *
 * @returns the catalogue
 */
export function fetchPermissionCatalogue(): Promise<PermissionCatalogueEntry[]> {
  return apiRequest<PermissionCatalogueEntry[]>('/permissions')
}

/**
 * Reads the permission templates. Only the owner may do this.
 *
 * @returns the templates
 */
export function fetchPermissionTemplates(): Promise<PermissionTemplate[]> {
  return apiRequest<PermissionTemplate[]>('/permission-templates')
}

/**
 * Creates a permission template. Only the owner may do this.
 *
 * @param request the new template
 * @returns the created template
 */
export function createPermissionTemplate(request: {
  code: string
  name: string
  description?: string
  permissions: string[]
}): Promise<PermissionTemplate> {
  return apiRequest<PermissionTemplate>('/permission-templates', {
    method: 'POST',
    body: JSON.stringify(request),
  })
}

/**
 * Edits a permission template. Only the owner may do this.
 *
 * @param id the template id
 * @param request the new values
 * @returns the changed template
 */
export function updatePermissionTemplate(
  id: string,
  request: {
    name?: string
    description?: string
    permissions?: string[]
    active?: boolean
  },
): Promise<PermissionTemplate> {
  return apiRequest<PermissionTemplate>(`/permission-templates/${id}`, {
    method: 'PUT',
    body: JSON.stringify(request),
  })
}

/**
 * Reads the audit rows the signed in user may see.
 *
 * @param filters the search filters
 * @returns the page of audit rows
 */
export function fetchAuditLogs(
  filters: {
    action?: string
    entityType?: string
    locationId?: string
    page?: number
    size?: number
  } = {},
): Promise<PageResponse<AuditLogRow>> {
  return apiRequest<PageResponse<AuditLogRow>>(`/audit-logs${toQueryString(filters)}`)
}

/**
 * Reads the business settings, grouped the way the settings screen shows them.
 *
 * @returns the settings
 */
export function fetchSettings(): Promise<{ groups: SettingsGroups }> {
  return apiRequest<{ groups: SettingsGroups }>('/settings')
}

/**
 * Writes a batch of settings. The backend validates every value before it writes anything.
 *
 * @param values the key and value pairs
 * @returns the values that were written
 */
export function saveSettings(values: Record<string, string>): Promise<Record<string, string>> {
  return apiRequest<Record<string, string>>('/settings', {
    method: 'PUT',
    body: JSON.stringify({ values }),
  })
}

/**
 * Builds a query string from the filters that are actually set.
 *
 * @param filters the filters
 * @returns the query string, with a leading question mark or empty
 */
function toQueryString(filters: Record<string, string | number | boolean | undefined>): string {
  const parts: string[] = []
  for (const [key, value] of Object.entries(filters)) {
    if (value === undefined || value === '') {
      continue
    }
    parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
  }
  return parts.length ? `?${parts.join('&')}` : ''
}
