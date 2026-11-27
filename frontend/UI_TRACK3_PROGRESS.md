# UI Track 3 Progress — Connect POS Screens to Live Backend

Branch: `ui/pos-live` (branched from `ui/pos-screens` and merged cleanly with `main`).

## Part 1: Make the Contract Exact (DONE — committed)

### Backend vs Frontend Contract Mismatches Fixed
1. **Barcode Lookup (`posLookupBarcode`)**:
   - **Before**: `GET /api/v1/pos/lookup` returning flat mock-only `PosLookupResponse` (`variantId`, `productName`, `variantLabel`, `barcode`, `availableStock`).
   - **Live Backend**: `GET /api/v1/catalog/lookup?barcode=...&scope=POS&locationId=...` returning canonical `LookupResult` (`variant`, `barcodes`, `promotion`, `listPrice`, `promotionDiscount`, `netUnitPrice`, `taxRatePercent`, `taxInclusive`, `belowCost`, optional `cost`, `stock`). Removed `PosLookupResponse`.
2. **Removed Non-existent Price Check Endpoint**:
   - **Before**: `checkSalePrices` calling `POST /pos/price-check`.
   - **Live Backend**: No `/pos/price-check` endpoint exists; `belowCost` / `cost` is returned on lookup and `belowCostWarnings` is returned on `POST /pos/sales` when `COST_VIEW` is held.
3. **Cash Sessions (`CashSessionSummary` & Open Session)**:
   - **Before**: Open switched to `/pos/sessions/open` under `MODE === 'test'` or `VITE_POS_MOCKS=1`; `CashSessionSummary` omitted `sessionNumber`, `cashierId`, and `closeNote`; EOD close dialog computed expected variance in the browser.
   - **Live Backend**: `POST /api/v1/pos/sessions`, `GET /api/v1/pos/sessions/current`, `GET /api/v1/pos/sessions/{id}/summary`, `POST /api/v1/pos/sessions/{id}/close` (`countedCash`, optional `countedBkash`/`countedNagad`/`countedCard`/`note`); `CashSessionSummary` includes `sessionNumber`, `cashierId`, `closeNote`.
4. **Discount Approvals & Approval PIN Setup**:
   - **Before**: Sent client `managerPin` directly inside `CreateSaleRequest`.
   - **Live Backend**: `PUT /api/v1/auth/approval-pin` (`{ currentPassword, pin }` -> `204`) and `POST /api/v1/pos/approvals` (`{ locationId, pin, maxDiscountPercent }` -> `201 ApprovalTokenResponse { approvalToken, expiresAt, maxDiscountPercent }`, never returning manager identity). `POST /api/v1/pos/sales` accepts single-use `approvalToken`.
5. **Create Sale (`CreateSaleRequest`)**:
   - **Before**: Sent `cashSessionId`, `managerPin`, and client alias payment fields `amountGiven`, `trxId`, `cardReference` to `/sales` in test/mock mode; no `Idempotency-Key` header.
   - **Live Backend**: Always `POST /api/v1/pos/sales` with `Idempotency-Key` header and canonical payload (`locationId`, `lines`, `billDiscountType`, `billDiscountValue`, `approvalToken`, `payment: { method, tendered?, reference? }`, `idempotencyKey`, `note`).
6. **Sales List, Detail, Receipt & Reprint (`SaleSummary`, `SaleDetail`, `SaleLineSummary`, `ReceiptData`)**:
   - **Before**: Hit `/sales*` in test/mock mode; `SaleSummary` included non-existent `cashSessionId`, `cashierName`, `soldAt`, `status`, `subtotal`, `discountTotal`, `taxTotal`, `paymentReference`; `SaleDetail` embedded `returns` and `returnedQuantity`; `fetchSaleByReceipt` used `/sales/by-receipt/{receiptNumber}`.
   - **Live Backend**: Always hits `/pos/sales`, `/pos/sales/{id}`, `/pos/sales/lookup?receiptNumber=...`, `GET /pos/sales/{id}/receipt` (`fetchSaleReceipt`), `POST /pos/sales/{id}/reprint` (`reprintSaleReceipt`), and `GET /pos/sales/{id}/returns` (`listSaleReturns`). `SaleSummary` has `createdAt` (not `soldAt`), `cashierUsername` (not `cashierName`), `itemCount`, `total`, `paymentMethod`. `SaleDetail` carries `promotionDiscount`, `manualDiscount`, `changeGiven`, `payment`, `note`, `lines` (with `color`, `size`, `billDiscountAllocated`, `netUnitPrice`, `taxAmount`, `lineSubtotal`), and optional `belowCostWarnings`. Returns are fetched via `GET /pos/sales/{id}/returns`.
7. **Returns (`CreateReturnRequest`, `SaleReturnRecord`)**:
   - **Before**: Free-text `reason`, missing `note` and `disposition`, used `refundNumber` and `totalRefund`.
   - **Live Backend**: `POST /api/v1/pos/sales/{saleId}/returns` with `Idempotency-Key` header, `ReturnReason` enum (`CHANGED_MIND`, `WRONG_SIZE`, `DEFECTIVE`, `OTHER`), `note`, and per-line `disposition` (`RESTOCK` | `INSPECTION`). Response uses `returnNumber`, `saleId`, `locationId`, `refundMethod`, `refundAmount`, and line `id`/`variantId`/`disposition`.
8. **Inspections (`/api/v1/inventory/inspections`)**:
   - Added canonical `fetchInspections` (`GET /inventory/inspections`) and `resolveInspection` (`POST /inventory/inspections/resolve`) with `InspectionItem` and `ResolveInspectionResponse`.
9. **Mock Isolation & 17 Error Code Mappings**:
   - Removed `USE_MOCK_PATHS` from `src/api/pos.ts`; mocks run ONLY when `import.meta.env.DEV && import.meta.env.VITE_POS_MOCKS === '1'` or when a test explicitly calls `installPosMocks()`.
   - Mapped all 17 error codes (`POS_SESSION_REQUIRED`, `SESSION_ALREADY_OPEN`, `SESSION_NOT_OPEN`, `DISCOUNT_LIMIT_EXCEEDED`, `APPROVAL_INVALID`, `PIN_POLICY`, `PIN_LOCKED`, `PAYMENT_REFERENCE_REQUIRED`, `PAYMENT_INSUFFICIENT`, `CARD_NUMBER_REJECTED`, `DUPLICATE_REFERENCE`, `IDEMPOTENCY_CONFLICT`, `EMPTY_CART`, `ALREADY_RETURNED`, `RETURN_QUANTITY_EXCEEDED`, `RETURN_WINDOW_EXPIRED`, `INSUFFICIENT_STOCK`) in `src/lib/posErrors.ts` wired into `readErrorDetails`.

## Part 2: Missing Screens & Live POS Checks (DONE — verifying build/tests)
- **Approval PIN setup (`/approval-pin`)**: `src/pages/ApprovalPinPage.tsx`, linked in `AppShell` user dropdown ONLY for `OWNER` or `POS_DISCOUNT_APPROVE`, calling `PUT /auth/approval-pin` with `currentPassword` and surfacing server `PIN_POLICY` error detail.
- **Inspections (`/inventory/inspections`)**: `src/pages/InspectionsPage.tsx` + `Inventory -> Inspections` sidebar link, gated by `INVENTORY_ADJUST`, listing inspection items and resolving quantities via `RESTOCK` / `WRITE_OFF` with an explicit confirmation step (`ConfirmDialog`).
- **Live POS Wiring**: `Idempotency-Key` sent on sales & returns and reused identically across network-failure retries; original receipt fetched from `GET /pos/sales/{id}/receipt` while reprint calls `POST /pos/sales/{id}/reprint` with `DUPLICATE`; approval PIN exchanges via `POST /pos/approvals` without manager name; EOD close reads expected figures from `GET /pos/sessions/{id}/summary` without browser math.
- **Tests**: Added `src/pages/Track3Part2.test.tsx`.

## Part 3: Overview Dashboard Layout (PENDING)
- `HomePage.tsx` four separate KPI cards, branch comparison table skeleton, Action Center skeleton, and cashier `"Today at my branch"` strip without fake numbers in production
