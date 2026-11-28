# Phase 5 Progress

Started: 2026-11-28
Branch: `main`

## Step 0: Housekeeping
- [x] `git status` clean & Phase 4 pushed to `origin/main` (`4d0e48f`, plus student fix `9fbc173` on `scripts/check-live.ps1`).
- [x] PostgreSQL running on `5432` (`merqo_test` available).
- [x] Replaced remaining `merqo-api` references with `merqo` (`https://merqo.onrender.com`) in `DEPLOY.md`, `docs/DEPLOY-CHECKLIST.md`, `docs/ARCHITECTURE.md`, `docs/TROUBLESHOOTING.md`, `render.yaml`, and `frontend/functions/api/proxy.test.ts` so they agree with `frontend/functions/api/[[path]].ts` (`API_BASE_URL`) and `scripts/check-live.ps1`.
- [x] Baseline `scripts\run-tests.bat` GREEN (`434` backend unit+integration tests, `358` Spotless files clean, `90` frontend Vitest tests across `19` files, ESLint clean).

## Slice Checklist
- [ ] **A.** V8 migration and pure Java domain classes (`com.merqo.payments.domain`) + unit tests
- [ ] **B.** Order payments: record, claim prefill, verify, check, pending queue, `PaymentVerifierRegistry`, `MockPaymentVerifier`, `PaymentReferenceGuard` (+ `SaleService` integration), order detail payment position & `readyForShipment` + concurrency/integration tests
- [ ] **C.** Refunds: request, approve, reject, pay, refund list & due list + integration tests
- [ ] **D.** COD tracking: collection recording, courier settlements (exact/OVER/SHORT), outstanding + gap flag + integration tests
- [ ] **E.** Read-only Payments & History (`GET /payments/history`, `GET /payments/summary`) with scoped union SQL query + integration tests
- [ ] **F.** Hardening test suite: scope & tampering, cost privacy, card-number/secrets exclusion, 7 append-only DB triggers & repo reflection checks, all 9 audit actions, concurrency races, and financial invariants
- [ ] **G.** Demo payments seed (`DemoOrdersPaymentsContributor` after Phase 4 seed) + Demo Console `POST /demo/simulate-payment` + `DEMO_MODE=false` verification + full suite
- [ ] **H.** Frontend: Payments sidebar section (History, To verify, Refunds, COD & settlements), Order detail Payments card, Settings payment settings, Demo Console simulate-payment, Overview pending count card, Vitest tests, lint, typecheck, build
- [ ] **I.** Extend `scripts/smoke-local.ps1` & `scripts/check-live.ps1` (read-only), update docs (`JAVA_GUIDE.md`, `ARCHITECTURE.md`, `ASSUMPTIONS.md`, `PERMISSIONS.md`, `DEPLOY.md`, `.env.example`, `TROUBLESHOOTING.md`, `SETUP.md`), full clean-DB `run-tests.bat` + Spotless, local smoke run, commit, push & live check

## Completed Classes & Tests
_(to be populated per slice)_

## Open Problems
- None.

**NEXT STEP:** Implement Slice A (`V8__payments_refunds_and_cod.sql`, pure Java domain classes under `com.merqo.payments.domain`, `SettingKey` / `ErrorCodes` / `AuditAction` additions, and comprehensive unit tests).
