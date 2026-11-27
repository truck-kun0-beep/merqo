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
- [ ] **Slice F** (`not started`): Returns and inspection (`POST /pos/sales/{id}/returns`, `GET /pos/sales/{id}/returns`, `GET /inventory/inspections`, `POST /inventory/inspections/resolve`, tests).
- [ ] **Slice G** (`not started`): Hardening tests (append-only triggers on 6 tables, Jackson cost-privacy checks, audit rows, 5 concurrency scenarios, stock invariant test).
- [ ] **Slice H** (`not started`): Demo data (`DemoDataContributor` for POS sessions/sales/returns/PINs, idempotent, `DEMO_MODE=false` check, full suite green).
- [ ] **Slice I** (`not started`): Docs and finish (`JAVA_GUIDE.md`, `ARCHITECTURE.md`, `ASSUMPTIONS.md`, `PERMISSIONS.md`, `POS_CONTRACT.md`, `SETUP.md`, `TROUBLESHOOTING.md`, Supabase check, full suite + Spotless, final commit).

## NEXT STEP
Implement Slice F: Returns and inspection (`SaleReturn`, `SaleReturnLine`, `ReturnReason`, `ReturnDisposition`, repositories, `ReturnService`, `ReturnController`, `InspectionController`/`InventoryController` inspection endpoints, and integration tests).
