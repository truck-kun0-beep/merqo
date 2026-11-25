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
  INVENTORY: SettingEntry[]
  THRESHOLDS: SettingEntry[]
}

// ---------------------------------------------------------------------------
// Phase 2: catalogue, pricing, promotions, inventory, counts and transfers.
// ---------------------------------------------------------------------------

/**
 * The kind of discount a promotion gives.
 */
export type DiscountType = 'PERCENT' | 'FIXED'

/**
 * The channel a promotion covers.
 */
export type PromotionScope = 'POS' | 'ONLINE' | 'BOTH'

/**
 * The state of a promotion as the promotions screen shows it.
 */
export type PromotionStatus = 'ACTIVE' | 'SCHEDULED' | 'EXPIRED' | 'DISABLED'

/**
 * The states a stock transfer goes through.
 */
export type TransferStatus =
  | 'REQUESTED'
  | 'APPROVED'
  | 'REJECTED'
  | 'DISPATCHED'
  | 'PARTIALLY_RECEIVED'
  | 'RECEIVED'
  | 'CLOSED_WITH_SHORTAGE'
  | 'CANCELLED'

/**
 * The reason of a stock adjustment.
 */
export type AdjustmentReason = 'DAMAGED' | 'LOST' | 'FOUND' | 'DATA_CORRECTION' | 'OTHER'

/**
 * The states of a stock count.
 */
export type CountStatus = 'IN_PROGRESS' | 'SUBMITTED' | 'APPLIED' | 'CANCELLED'

/**
 * One category of the catalogue.
 */
export interface CategorySummary {
  id: string
  name: string
  parentId: string | null
  isActive: boolean
}

/**
 * One brand of the catalogue.
 */
export interface BrandSummary {
  id: string
  name: string
  isActive: boolean
}

/**
 * One variant of a product.
 *
 * The cost is only part of the answer when the caller may see the costs, so
 * the field is absent instead of zero for everybody else.
 */
export interface VariantSummary {
  id: string
  productId: string
  sku: string
  color: string | null
  size: string | null
  otherAttribute: string | null
  sellingPrice: string
  isActive: boolean
  /** The cost price, only present for a caller who may see the costs. */
  costPrice?: string
  /** Whether the price is under the unit cost. Only present for a cost-visible caller. */
  belowCost?: boolean
  /** The sentence that explains the below-cost warning, when there is one. */
  belowCostWarning?: string
}

/**
 * One product with its variants.
 */
export interface ProductSummary {
  id: string
  productCode: string
  name: string
  categoryId: string | null
  brandId: string | null
  description: string | null
  taxRatePercent: string | null
  isActive: boolean
  variants: VariantSummary[]
}

/**
 * The barcode of a variant.
 */
export interface BarcodeSummary {
  id: string
  barcode: string
  kind: 'PRIMARY' | 'ALTERNATE'
}

/**
 * The full answer of the variant endpoint, with its barcodes.
 */
export interface VariantDetail extends VariantSummary {
  barcodes: BarcodeSummary[]
}

/**
 * The pricing of one line, exactly as the backend calculator builds it.
 */
export interface LinePricing {
  listPrice: string
  promotionDiscount: string
  manualDiscount: string
  netUnitPrice: string
  quantity: number
  lineSubtotal: string
  tax: string
  lineTotal: string
  belowCost: boolean
}

/**
 * The promotion that applies to a variant.
 */
export interface AppliedPromotion {
  id: string
  name: string
  discountType: DiscountType
  value: string
  /** The channel the promotion covers. */
  scope: PromotionScope
}

/**
 * The stock of a variant at one location.
 *
 * The stock is only part of the answer when the caller may see the location.
 */
export interface LookupStock {
  locationId: string
  onHand: number
  reserved: number
  available: number
  inInspection: number
}

/**
 * The answer of the scan lookup.
 *
 * The prices are flat at the top level: the backend puts them next to the
 * variant instead of inside a nested object.
 */
export interface LookupResult {
  variant: VariantSummary & { productName: string }
  barcodes: BarcodeSummary[]
  promotion: AppliedPromotion | null
  listPrice: string
  promotionDiscount: string
  manualDiscount: string
  netUnitPrice: string
  quantity: number
  subtotal: string
  taxRatePercent: string
  taxInclusive: boolean
  tax: string
  total: string
  belowCost: boolean
  /** The cost price, only present for a caller who may see the costs. */
  cost?: string
  stock: LookupStock | null
}

/**
 * The answer of the price preview.
 *
 * The prices are flat at the top level here too, exactly as the lookup answers
 * them.
 */
export interface PricePreviewResult {
  variant: VariantSummary & { productName: string }
  promotion: AppliedPromotion | null
  listPrice: string
  promotionDiscount: string
  manualDiscount: string
  netUnitPrice: string
  quantity: number
  subtotal: string
  taxRatePercent: string
  taxInclusive: boolean
  tax: string
  total: string
  belowCost: boolean
  /** The cost price, only present for a caller who may see the costs. */
  cost?: string
}

/**
 * One promotion of the catalogue.
 */
export interface PromotionSummary {
  id: string
  name: string
  discountType: DiscountType
  value: string
  scope: PromotionScope
  allLocations: boolean
  startsAt: string | null
  endsAt: string | null
  isEnabled: boolean
  status: PromotionStatus
  note: string | null
  variantIds: string[]
  locationIds: string[]
}

/**
 * One conflict that a new promotion would cause.
 */
export interface PromotionConflict {
  promotionId: string
  promotionName: string
  sharedVariantIds: string[]
}

/**
 * The answer of the conflict preview.
 */
export interface PromotionConflictPreview {
  conflicts: PromotionConflict[]
  conflictCount: number
}

/**
 * One row of the stock list.
 */
export interface StockRow {
  locationId: string
  variantId: string
  sku: string
  productName: string | null
  color: string | null
  size: string | null
  isActive: boolean
  onHand: number
  reserved: number
  available: number
  inInspection: number
  reorderPoint: number
  isLowStock: boolean
  /** The unit cost, only present for a caller who may see the costs. */
  unitCost?: string
  /** The value of the stock at cost, only present for a caller who may see the costs. */
  valueAtCost?: string
}

/**
 * The totals of one location.
 */
export interface LocationTotal {
  locationId: string
  units: number
  /** The value at cost, only present for a caller who may see the costs. */
  valueAtCost?: string
}

/**
 * One row of the movement history.
 */
export interface StockMovementRow {
  id: string
  locationId: string
  variantId: string
  movementType: string
  quantityDelta: number
  onHandAfter: number
  referenceType: string | null
  referenceId: string | null
  reason: string | null
  note: string | null
  unitCost: string | null
  createdBy: string | null
  createdAt: string
}

/**
 * One stock adjustment.
 */
export interface StockAdjustmentRow {
  id: string
  number: string
  locationId: string
  reason: AdjustmentReason
  note: string | null
  isFlagged: boolean
  totalValueAtCost: string
  createdBy: string | null
  createdAt: string
}

/**
 * One line of a stock adjustment.
 */
export interface AdjustmentLineRow {
  variantId: string
  /** The SKU of the goods, so the screen shows a name a shopkeeper recognises. */
  sku: string | null
  quantityDelta: number
  unitCost: string | null
  valueAtCost: string | null
}

/**
 * One stock adjustment with its lines.
 */
export interface AdjustmentDetail extends StockAdjustmentRow {
  lines: AdjustmentLineRow[]
}

/**
 * The policy of one variant at one location.
 */
export interface InventoryPolicyRow {
  id: string
  locationId: string
  variantId: string
  reorderPoint: number
  targetStock: number
  onlineSafetyStock: number
}

/**
 * One stock count.
 */
export interface StockCountRow {
  id: string
  number: string
  locationId: string
  status: CountStatus
  categoryId: string | null
  isFlagged: boolean
  createdBy: string | null
  createdAt: string
  submittedAt: string | null
  appliedAt: string | null
}

/**
 * One line of a stock count.
 *
 * The system quantity is only part of the answer once the count is submitted,
 * so a blind count never shows it.
 */
export interface StockCountLineRow {
  variantId: string
  sku: string | null
  countedQuantity: number | null
  systemQuantityAtStart?: number
  variance?: number
}

/**
 * One stock count with its lines.
 */
export interface StockCountDetail extends StockCountRow {
  lines: StockCountLineRow[]
}

/**
 * One transfer of stock.
 */
export interface TransferRow {
  id: string
  number: string
  sourceLocationId: string
  destinationLocationId: string
  status: TransferStatus
  note: string | null
  requestedAt: string
  approvedAt: string | null
  dispatchedAt: string | null
  receivedAt: string | null
  closedAt: string | null
}

/**
 * One line of a transfer.
 */
export interface TransferLineRow {
  id: string
  variantId: string
  /** The SKU of the goods, so the screen shows a name a shopkeeper recognises. */
  sku: string | null
  /** The name of the product with its colour and size, when the variant is still there. */
  productName: string | null
  requestedQuantity: number
  approvedQuantity: number | null
  dispatchedQuantity: number
  receivedQuantity: number
  shortageQuantity: number
  inTransit: number
}

/**
 * One step of the history of a transfer.
 */
export interface TransferHistoryRow {
  fromStatus: string | null
  toStatus: string
  actorUsername: string | null
  note: string | null
  createdAt: string
}

/**
 * One transfer with its lines and its history.
 */
export interface TransferDetail extends TransferRow {
  lines: TransferLineRow[]
  history: TransferHistoryRow[]
}

/**
 * One problem with one row of an import file.
 */
export interface ImportRowError {
  row: number
  message: string
}

/**
 * The answer of the import validation.
 */
export interface ImportValidation {
  isValid: boolean
  errors: ImportRowError[]
  toCreate: number
  toUpdate: number
  openingStockLines: number
  totalRows: number
}

/**
 * The answer of the import commit.
 */
export interface ImportCommitResult {
  created: number
  updated: number
  openingStockLines: number
  /** The SKUs of the rows that were left alone. */
  skipped: string[]
  totalRows: number
}

// ---------------------------------------------------------------------------
// Receipt contract (shared shape for POS & print preview).
// ---------------------------------------------------------------------------

/**
 * One line item printed on a POS receipt.
 */
export interface ReceiptLineItem {
  name: string
  variant: string | null
  quantity: number
  unitPrice: string
  discount: string
  lineTotal: string
}

/**
 * Full payload rendered by the thermal Receipt component.
 */
export interface ReceiptData {
  businessName: string
  branchName: string
  branchAddress: string | null
  branchPhone: string | null
  receiptNumber: string
  issuedAt: string
  cashierName: string
  lines: ReceiptLineItem[]
  subtotal: string
  discount: string
  tax: string
  total: string
  paymentMethod: string
  paymentReference: string | null
  footerText: string | null
  isDuplicate?: boolean
}
