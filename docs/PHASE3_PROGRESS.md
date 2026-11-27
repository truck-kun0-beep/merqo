# Phase 3 Progress

Baseline: 353 backend tests passing, Flyway migrations V1–V5 applied.

## Slices

- [x] **Slice A** (`done`): Migration `V6__pos_sales_sessions_and_returns.sql` and pure Java domain classes (`PosRuleException`, `PaymentMethod`, `PaymentRules`, `DiscountAllocator`, `DiscountLimitPolicy`, `SaleCalculator`, `SaleTotals`, `ApprovalPinPolicy`, `ApprovalTokenRules`, `CashSessionReconciler`, `ReturnRules`, `ReceiptNumber`, `ReceiptData`, `ReceiptLineItem`, `ReceiptRenderer`, `JsonReceiptRenderer`, `PlainTextReceiptRenderer`).
  - Tests passing (44 tests): `PaymentRulesTest`, `SalePricingAndDiscountDomainTest`, `ApprovalAndSessionDomainTest`, `ReturnAndReceiptDomainTest`, `SettingKeyTest`, `SettingsIntegrationTest`.
- [x] **Slice B** (`done`): Cash sessions (`CashSessionStatus`, `CashSession`, `CashSessionRepository`, `CashSessionService`, `CashSessionController`), closed-session immutability trigger, race-safe open, live summary, server-computed close reconciliation & variance note requirement.
  - Tests passing (8 tests): `CashSessionIntegrationTest`, `PublicEndpointPolicyTest`.
- [x] **Slice C** (`done`): Approval PIN and tokens (`AppUser.approvalPinHash`, `PUT /auth/approval-pin`, `POST /pos/approvals`, `PinAttempt`, `PinAttemptRepository`, `PinAttemptRecorder`, `ApprovalToken`, `ApprovalTokenRepository`, `ApprovalService`, `ApprovalController`).
  - Tests passing (7 tests): `ApprovalIntegrationTest`, `SecretsNeverLeakIntegrationTest`.
- [x] **Slice D** (`done`): Sales, idempotency, receipts (`Sale`, `SaleLine`, `SalePayment`, `ReceiptReprint`, repositories, `SaleService`, `SaleController`, `PromotionService.resolveAt`).
  - Tests passing (3 tests): `SaleIntegrationTest`.
- [x] **Slice E** (`done`): History and lookup (`GET /pos/sales`, `GET /pos/sales/{id}`, `GET /pos/sales/lookup?receiptNumber=`, Dhaka-day date filters, pagination, owner-aware cashier visibility, branch scope & catalog scan).
  - Tests passing (1 test): `SaleHistoryAndScopeIntegrationTest`.
- [x] **Slice F** (`done`): Returns and inspection (`InventoryBalance.receiveToInspection`, `InventoryService.receiveToInspection`, `SaleReturn`, `SaleReturnLine`, `ReturnReason`, `ReturnDisposition`, `ReturnService`, `ReturnController`, `InspectionService`, `InspectionController`).
  - Tests passing (2 tests): `ReturnAndInspectionIntegrationTest`.
- [x] **Slice G** (`done`): Hardening tests (`PosHardeningIntegrationTest` — append-only triggers on all 6 tables, Jackson recursive cost-privacy checks, audit rows across all POS actions, concurrency races for last unit / idempotent submit / last returnable unit / duplicate TrxID, and exact stock balance/movement invariant).
  - Tests passing (4 tests): `PosHardeningIntegrationTest`.
- [ ] **Slice H** (`not started`): Demo data (`DemoDataContributor` for POS sessions/sales/returns/PINs, idempotent, `DEMO_MODE=false` check, full suite green).
- [ ] **Slice I** (`not started`): Docs and finish (`JAVA_GUIDE.md`, `ARCHITECTURE.md`, `ASSUMPTIONS.md`, `PERMISSIONS.md`, `POS_CONTRACT.md`, `SETUP.md`, `TROUBLESHOOTING.md`, Supabase check, full suite + Spotless, final commit).

## NEXT STEP
Implement Slice H: `DemoPosContributor` (`order = 40`), setting demo PIN `1234`, closed past sessions, open DM session for demo `cashier`, ~25 back-dated sales across DM/MP/UT with all 4 payment methods, promotions, discounts, and returns (restock + inspection), idempotency + `DEMO_MODE=false` tests, and run the full backend suite.
