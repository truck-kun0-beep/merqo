# Phase 4 Progress — Integration API, Website Simulator, Online Orders, Call Log, Customer History

Branch: `main` (`G:\Merqo`)

## Step 0: Housekeeping and Shipping Phase 3 (`DONE`)
- **Git & Merge check**: `ui/pos-live` (`c92d295`) is merged into `main`. Working tree clean.
- **PostgreSQL**: Started portable PostgreSQL 17.5 on `localhost:5432` (`G:\pgdata`).
- **CSRF login test**: Verified `CsrfAfterLoginIntegrationTest` and strict-jar `scripts/check-live.ps1` exist.
- **Full suite (`scripts\run-tests.bat`)**: GREEN — clean `merqo_test` recreate, backend `clean verify` + Spotless (288 files), frontend Vitest (18 files, 84 tests) + ESLint.
- **Database backup (`scripts\backup-db.bat`)**: Failed with `[ERROR] .env not found. Copy .env.example to .env first and fill in the values.` (no `.env` present in local repo; `pg_dump.exe` also absent in `G:\tools\pgtx2\bin`).
- **Push (`git push origin main`)**: `Everything up-to-date` (`main` already pushed through `c92d295`).
- **Live check (`scripts/check-live.ps1` -> `https://merqo.pages.dev` / `https://merqo-api.onrender.com`)**:
  - Step 1 (Frontend answers): **PASS** (host resolves, `200`, root element present)
  - Step 2 (Backend ping direct): **INFO** (`404` from `merqo-api.onrender.com`)
  - Step 3 (`/api` proxy): **FAIL** (`502` from proxy)
  - Step 4 (Demo mode / accounts): **FAIL** (`502`)
  - Step 5 (Demo login / cookies): **FAIL** (`502`, no CSRF cookie)
  - Step 6 (`GET /auth/me`): **FAIL** (skipped, not signed in)
  - Step 7 (`POST /users/create`): **FAIL** (skipped)
  - Step 8 (`POST /users/{id}/deactivate`): **FAIL** (skipped)
  - Step 9 (`POST /auth/logout`): **FAIL** (skipped)
  - Note for student: Cloudflare Pages frontend is live, while `merqo-api.onrender.com` returned 404/502 (service URL/deployment on Render may differ or be suspended). Continuing with Phase 4 as instructed.

## Slice Checklist
- [x] **Slice A**: Flyway V7 migration (with append-only triggers on `order_status_history`, `order_call_logs`) + pure Java domain classes (section 4) + unit tests
  - Built: `V7__online_orders_and_integrations.sql` (sequence, `integration_clients`, `integration_rate_attempts`, `customers`, `orders`, `order_lines`, `order_status_history` + trigger, `order_call_logs` + trigger, `integration_inbox`), `ErrorCodes`, `AuditAction`, `SettingKey` (`orders.follow_up_default_minutes`, `integration.signature_window_seconds`), `GlobalExceptionHandler` (`OrderRuleException`), and pure Java domain classes under `com.merqo.orders.domain` (`OrderRuleException`, `PhoneNumber`, `RequestSignature`, `OrderChannel`, `OrderStatus`, `OrderTransitions`, `PaymentTerms`, `IncomingOrder`, `IncomingOrderValidator`, `OnlineAvailabilityCalculator`, `PhoneHistory`, `PhoneRiskPolicy`, `CallResult`, `DeclineReason`, `CancelReason`, `WebsiteOrderSource`).
  - Tests: `OrderDomainTest` (8 tests), `RequestSignatureTest` (2 tests), `SettingKeyTest` (12 tests) — verifying green with Flyway V7 validation.
- [x] **Slice B**: Integration clients & signature security (`integration_clients`, AES-GCM secret storage, owner endpoints, API key/HMAC filter for `/integration/v1/**`, rate limit, public-endpoint policy update + tests)
  - Built: `IntegrationProperties`, `SecretCipher`, `IntegrationClient`, `IntegrationClientRepository`, `IntegrationRateAttempt`, `IntegrationRateAttemptRepository`, `IntegrationKeyStartupCheck`, `CachedBodyHttpServletRequest`, `IntegrationRateLimiter`, `IntegrationClientService`, `IntegrationClientController`, `IntegrationAuthFilter`, `IntegrationRejectionFilter`, `SoftHoldReader`, `IntegrationStockController`, `SecurityConfiguration` (`@Order(1)` stateless `/integration/v1/**` + `@Order(2)` `/api/v1/**`), `application*.yml`, `.env.example`, `DEPLOY.md`.
  - Tests: `IntegrationSecurityTest` (4 tests), `PublicEndpointPolicyTest` (5 tests), `SecretsNeverLeakIntegrationTest` (6 tests) — 15/15 passing + Spotless clean.
- [x] **Slice C**: Order intake pipeline (`integration_inbox`, `GenericApiOrderSource`, order/line creation, idempotency, price mismatch, unknown SKU failure & retry, invalid phone, stock availability & order status endpoints + concurrency tests)
  - Built: `OrderNumber`, `Customer`, `CustomerRepository`, `CustomerService`, `Order`, `OrderRepository`, `OrderLine`, `OrderLineRepository`, `OrderStatusHistory`, `OrderStatusHistoryRepository`, `OrderCallLog`, `OrderCallLogRepository`, `IntegrationInbox`, `IntegrationInboxRepository`, `GenericApiOrderSource`, `WebsiteOrderSourceResolver`, `OrderSoftHoldReader`, `OnlinePricingService`, `OrderIntakeService`, `IntegrationOrderController`.
  - Tests: `OrderIntakeIntegrationTest` (5 tests incl. `ExecutorService` + `CountDownLatch` concurrent duplicate intake, `FAILED` inbox retry, `IDEMPOTENCY_CONFLICT`, price mismatch, invalid phone, `ONLINE_LOCATION_NOT_SET`, soft holds on `GET /integration/v1/stock`) + `PublicEndpointPolicyTest` (5 tests) — 10/10 passing + Spotless clean.
- [x] **Slice D**: Staff order handling (list, detail, call log, customer edit, confirm hard reserve, decline, cancel release, cancellation request, availability view + concurrency & trigger tests)
  - Built: `PhoneRiskService`, `OrderDtos`, `OrderStaffService`, `OrderController`, `OrderRepository` (`JpaSpecificationExecutor`).
  - Tests: `OrderStaffIntegrationTest` (2 tests covering Dhaka date boundaries, call logs + `CALL_NOTE_REQUIRED` + auto `FOLLOW_UP`, customer phone edit, atomic multi-line reservation + rollback on shortage, concurrent confirm race on last unit, cancel release + claimed advance notice, decline reasons, cancellation request, PostgreSQL append-only triggers on `order_status_history` & `order_call_logs`, and `reserved <= on_hand` balance invariants) — 2/2 passing + Spotless clean.
- [x] **Slice E**: Manual orders & customer history (`POST /orders/manual`, history counts, risk badge, `GET /customers/history` + tier/threshold tests)
  - Built: `ManualOrderService`, `CustomerController` (`/api/v1/customers/history`), `POST /api/v1/orders/manual` on `OrderController`.
  - Tests: `OrderManualAndCustomerIntegrationTest` (2 tests verifying server catalog + ONLINE promo pricing ignoring POS-only promos, `confirmNow` atomic reserve, customer history counts and `NEW`/`OK`/`WATCH`/`HIGH` risk progression driven by live `threshold.refusal_risk_count`) — 2/2 passing + Spotless clean.
- [x] **Slice F**: Scope, privacy & hardening tests (warehouse isolation, 404/403, tampered IDs, Jackson cost-key checks, audit rows, default-deny enumeration)
  - Hardened: `OrderStaffService.requireWarehouseOrDeny`, `ManualOrderService`, `CustomerController` so managers outside WH scope always receive `404 NOT_FOUND` while unpermitted staff receive `403 FORBIDDEN`.
  - Tests: `OrderHardeningIntegrationTest` (2 tests verifying manager 404 / cashier 403 / WH staff missing `ORDER_CANCEL` 403 / tampered ID 404, Jackson recursive key-absence across all endpoints, all 11 Phase 4 audit actions, and append-only repository guarantees) + `PublicEndpointPolicyTest` — passing + Spotless clean.
- [x] **Slice G**: Website simulator (`tools/website-simulator/send-order.mjs` & `.ps1`), Demo Console endpoints, and `docs/INTEGRATION_API.md`
  - Built: `MockWebsiteOrderSource`, `DemoSimulatorController` (`GET /api/v1/demo/simulator-info`, `POST /api/v1/demo/simulate-order`, owner + `DEMO_MODE=true` only), `tools/website-simulator/send-order.mjs` & `send-order.ps1`, complete rewrite of `docs/INTEGRATION_API.md`.
  - Tests: `DemoSimulatorIntegrationTest` (1 test), `PublicEndpointPolicyTest` (5 tests), `node --check send-order.mjs` — passing + Spotless clean.
- [ ] **Slice H**: Phase 4 demo data (`DemoOrdersContributor` through real services, idempotent, ~20 orders across states & risk tiers, `DEMO_MODE=false` empty)
- [ ] **Slice I**: Frontend (Online Orders list & detail, Manual order form, Integration clients settings page, Demo Console page, Overview pending-orders card, Vitest tests, lint/typecheck/build)
- [ ] **Slice J**: Local smoke script (`scripts/smoke-local.ps1` + live run), docs updates (`JAVA_GUIDE.md`, `ARCHITECTURE.md`, `ASSUMPTIONS.md`, `PERMISSIONS.md`, `DEPLOY.md`, `.env.example`, `TROUBLESHOOTING.md`, `SETUP.md`), full suite via `scripts\run-tests.bat`, Spotless, final commit/push/live-check

## Test Classes
- Baseline clean (`scripts\run-tests.bat` passed prior to Phase 4).

## Open Problems
- Live Render URL `https://merqo-api.onrender.com` returned 404/502 in `check-live.ps1`.
- Local `.env` and `pg_dump.exe` not present for `scripts\backup-db.bat`.

NEXT STEP: Slice H — Create `DemoOrdersContributor` (idempotent by `demo_key`, order after `DemoPosContributor`, seeding demo webshop client and ~20 orders across statuses/channels/risk tiers + `DEMO_MODE=false` empty test), then run full suite via `scripts\run-tests.bat`.
