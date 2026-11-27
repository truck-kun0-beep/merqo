# Phase 3 Progress

Baseline: 353 backend tests passing, Flyway migrations V1–V5 applied.

## Slices

- [x] **Slice A** (`done`): Migration `V6__pos_sales_sessions_and_returns.sql` (sequences, `app_users.approval_pin_hash`, `pin_attempts`, `cash_sessions` + immutability trigger + one-OPEN-per-cashier index, `approval_tokens`, `sales`, `sale_lines`, `sale_payments` + global unique TrxID index, `sale_returns`, `sale_return_lines`, `receipt_reprints`, append-only triggers on 6 tables); `SettingKey.POS_RETURN_WINDOW_DAYS`; `ErrorCodes`; `AuditAction`; pure Java domain classes (`PosRuleException`, `PaymentMethod`, `PaymentRules`, `DiscountAllocator`, `DiscountLimitPolicy`, `SaleCalculator`, `SaleTotals`, `ApprovalPinPolicy`, `ApprovalTokenRules`, `CashSessionReconciler`, `ReturnRules`, `ReceiptNumber`, `ReceiptData`, `ReceiptLineItem`, `ReceiptRenderer`, `JsonReceiptRenderer`, `PlainTextReceiptRenderer`).
  - Tests passing (44 tests): `PaymentRulesTest`, `SalePricingAndDiscountDomainTest`, `ApprovalAndSessionDomainTest`, `ReturnAndReceiptDomainTest`, `SettingKeyTest`, `SettingsIntegrationTest`.
- [ ] **Slice B** (`not started`): Cash sessions (entities, repositories, service, controller, close immutability trigger, integration + concurrency tests).
- [ ] **Slice C** (`not started`): Approval PIN and tokens (`PUT /auth/approval-pin`, `POST /pos/approvals`, 128-bit single-use token hash, 5-min expiry, throttling, audit, tests).
- [ ] **Slice D** (`not started`): Sales, idempotency, receipts (`POST /pos/sales`, `GET /pos/sales/{id}/receipt`, `POST /pos/sales/{id}/reprint`, server pricing, stock removal, tests).
- [ ] **Slice E** (`not started`): History and lookup (`GET /pos/sales`, `GET /pos/sales/{id}`, `GET /pos/sales/lookup?receiptNumber=`, scope & tampering tests).
- [ ] **Slice F** (`not started`): Returns and inspection (`POST /pos/sales/{id}/returns`, `GET /pos/sales/{id}/returns`, `GET /inventory/inspections`, `POST /inventory/inspections/resolve`, tests).
- [ ] **Slice G** (`not started`): Hardening tests (append-only triggers on 6 tables, Jackson cost-privacy checks, audit rows, 5 concurrency scenarios, stock invariant test).
- [ ] **Slice H** (`not started`): Demo data (`DemoDataContributor` for POS sessions/sales/returns/PINs, idempotent, `DEMO_MODE=false` check, full suite green).
- [ ] **Slice I** (`not started`): Docs and finish (`JAVA_GUIDE.md`, `ARCHITECTURE.md`, `ASSUMPTIONS.md`, `PERMISSIONS.md`, `POS_CONTRACT.md`, `SETUP.md`, `TROUBLESHOOTING.md`, Supabase check, full suite + Spotless, final commit).

## NEXT STEP
Implement Slice B: Cash sessions (`CashSession` entity, `CashSessionRepository`, `CashSessionService`, `CashSessionController`, integration & concurrency tests).
