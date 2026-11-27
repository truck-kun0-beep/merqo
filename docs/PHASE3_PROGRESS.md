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
- [x] **Slice H** (`done`): Demo data (`DemoPosContributor` with `order = 40`, `PromotionRepository.findByDemoKey`, demo PIN `1234`, branch opening stock ensure, 2 POS promotions, 4 closed past sessions across DM/MP/UT + 1 open DM cashier session, 25 back-dated sales across 14 days and all 4 payment methods including approved overrides, 2 returns covering `RESTOCK` & `INSPECTION`, idempotent repeat runs, and `DEMO_MODE=false` guard).
  - Tests passing: `DemoPosIntegrationTest`, `DemoModeOffIntegrationTest` (7 tests) + full backend suite from clean `merqo_test` (`396` tests, `0` failures, `0` errors).
- [x] **Slice I** (`done`): Documentation, API contract, and final verification:
  - Updated `docs/JAVA_GUIDE.md` (every Phase 3 class & test mapped to its responsibility and OOP concept, Phase 3 viva questions, and Mermaid `classDiagram`), `docs/ARCHITECTURE.md`, `docs/ASSUMPTIONS.md` (`A37`–`A42`), `docs/PERMISSIONS.md`, `docs/TROUBLESHOOTING.md`, `SETUP.md` ("Testing the POS without hardware": keyboard scan + PDF print preview), `frontend/POS_CONTRACT.md`, and `frontend/src/api/pos.ts`.
  - Confirmed `V6__pos_sales_sessions_and_returns.sql` uses only Supabase-compatible PostgreSQL features (sequences, `TIMESTAMPTZ`, `NUMERIC(14,2)`, partial unique indexes, standard PL/pgSQL triggers without superuser extensions).
  - Verified full suite: Frontend Vitest (`16` files, `74` passed), ESLint (`0` errors), Backend `spotless:check` (`281` files clean), Backend full test suite from clean `merqo_test` (`396` tests, `0` failures, `0` errors, `0` skipped).

## Status
Phase 3 complete on branch `main`.
