/**
 * The shapes of the Merqo API that the frontend uses.
 *
 * Every type here mirrors one backend response. The backend is the owner of
 * the contract, so these types are deliberately plain: no behaviour, no
 * fetching, no formatting. The formatting lives in `lib/format.ts`.
 */

/**
 * The kind of account.
 */
export type AccountType = 'OWNER' | 'MANAGER' | 'STAFF'

/**
 * The kind of location: a branch or the warehouse.
 */
export type LocationType = 'BRANCH' | 'WAREHOUSE'

/**
 * One location the signed in user may see.
 */
export interface LocationSummary {
  id: string
  code: string
  name: string
  locationType: LocationType
  address: string | null
  phone: string | null
  isActive: boolean
  isOnlineFulfillment: boolean
}

/**
 * One assignment of a user to a location.
 */
export interface AssignmentSummary {
  id: string
  /** The account, when the answer comes from an assignment endpoint. */
  userId?: string
  /** The location, when the answer comes from an assignment endpoint. */
  locationId?: string
  assignmentType: 'MANAGER' | 'STAFF'
  startedAt: string
  endedAt: string | null
  /** Whether the assignment is still running. */
  isActive?: boolean
}

/**
 * One location with its assignments.
 */
export interface LocationDetail extends LocationSummary {
  assignments: AssignmentSummary[]
}

/**
 * One permission the signed in user holds.
 */
export interface EffectivePermission {
  code: string
  module: string
  description: string
  scope: 'ALL' | 'LOCATION'
  locationIds: string[]
}

/**
 * The signed in user, with the scope and the effective permissions.
 */
export interface CurrentUser {
  id: string
  username: string
  fullName: string
  email: string | null
  accountType: AccountType
  mustChangePassword: boolean
  isDemo: boolean
  employeeCode: string | null
  maxDiscountPercent: string
  locations: Array<{ id: string }>
  permissions: EffectivePermission[]
}

/**
 * What the login page needs to know before anybody signs in.
 */
export interface AuthStatus {
  setupRequired: boolean
  demoMode: boolean
  businessName: string
}

/**
 * One demo account that can be signed in with one click.
 */
export interface DemoAccount {
  accountKey: string
  displayName: string
  role: string
  description: string
}

/**
 * One account of the Merqo system.
 */
export interface UserSummary {
  id: string
  username: string
  fullName: string
  email: string | null
  phone: string | null
  accountType: AccountType
  isActive: boolean
  mustChangePassword: boolean
  employeeCode: string | null
  maxDiscountPercent: string
  isDemo: boolean
  lastLoginAt: string | null
  createdAt: string
}

/**
 * One template of an account, as the account detail endpoint answers it.
 */
export interface UserTemplateLink {
  id: string
  code: string
  name: string
  description: string | null
  isActive: boolean
}

/**
 * One override of an account.
 *
 * An override sits on top of the templates: an `ALLOW` adds a permission and a
 * `DENY` takes one away. A deny always wins.
 */
export interface UserOverride {
  id: string
  permission: string
  effect: 'ALLOW' | 'DENY'
  /** The location the override is limited to, or `null` for every location. */
  locationId: string | null
}

/**
 * One effective permission of an account, with the reason for it.
 */
export interface ExplainedPermission {
  code: string
  module: string
  description: string
  scope: 'ALL' | 'LOCATION'
  locationIds: string[]
  reasons: string[]
}

/**
 * One account with everything that decides what it may do.
 */
export interface UserDetail extends UserSummary {
  templates: UserTemplateLink[]
  overrides: UserOverride[]
  locations: AssignmentSummary[]
  delegations: string[]
}

/**
 * One permission template.
 */
export interface PermissionTemplate {
  id: string
  code: string
  name: string
  description: string | null
  isSystem: boolean
  isActive: boolean
  permissions: string[]
}

/**
 * One entry of the permission catalogue.
 */
export interface PermissionCatalogueEntry {
  code: string
  label: string
  permissions: Array<{
    code: string
    description: string
    scope: 'GLOBAL' | 'LOCATION'
    ownerOnly: boolean
    delegable: boolean
  }>
}

/**
 * One row of the audit log.
 */
export interface AuditLogRow {
  id: string
  occurredAt: string
  actorUsername: string | null
  action: string
  entityType: string
  entityId: string | null
  locationId: string | null
  oldValues: Record<string, unknown> | null
  newValues: Record<string, unknown> | null
  correlationId: string | null
}

/**
 * The one page shape every list endpoint answers with.
 */
export interface PageResponse<T> {
  items: T[]
  page: number
  size: number
  totalItems: number
  totalPages: number
  hasNext: boolean
}

/**
 * The groups the settings screen shows.
 */
export type SettingGroup = 'BUSINESS' | 'RECEIPT' | 'TAX' | 'THRESHOLDS'

/**
 * The type of one setting value. The backend validates against the same names.
 */
export type SettingValueType = 'TEXT' | 'BOOLEAN' | 'INTEGER' | 'DECIMAL' | 'AMOUNT' | 'PAPER_WIDTH'

/**
 * One business setting.
 */
export interface SettingEntry {
  key: string
  label: string
  type: SettingValueType
  value: string
  defaultValue: string
  readOnly: boolean
}

/**
 * Every setting, grouped the way the settings screen shows them.
 */
export interface SettingsGroups {
  BUSINESS: SettingEntry[]
  RECEIPT: SettingEntry[]
  TAX: SettingEntry[]
  THRESHOLDS: SettingEntry[]
}
