# POS Frontend ↔ Phase 3 Backend Contract

This document defines the exact API contract between the Merqo POS frontend
and the Phase 3 Spring Boot backend.

Money fields are serialized as **2-decimal numeric strings** (e.g. `"1250.00"`),
dates as ISO-8601 UTC strings, and errors as RFC 7807 `ProblemDetail` payloads
with a stable `errorCode`.

All POS endpoints require an authenticated session cookie; every mutation
requires CSRF (`X-XSRF-TOKEN`) and server-enforced location + permission checks.

---

## 1. Barcode lookup (`Phase 2 / Phase 3 POS`)

- **Endpoint:** `GET /api/v1/catalog/lookup?barcode=<string>&scope=POS&locationId=<uuid>`
- **Permission:** `PRODUCT_VIEW` (+ `INVENTORY_VIEW` at `locationId` for stock quantities; `COST_VIEW` / `PRICE_EDIT` controls cost fields).
- **Response:** `PosLookupResponse` (plus optional `costPrice` / `margin` only when caller holds `COST_VIEW` or `PRICE_EDIT`). Absent cost fields are never `0.00`.

---

## 2. Cash drawer sessions

- **Open session:** `POST /api/v1/pos/sessions`
  - Permission: `POS_CASH_SESSION` at `locationId`.
  - Body: `{ locationId: string, openingCash: string }`
  - Rules: At most one `OPEN` session per cashier across all branches (`409 SESSION_ALREADY_OPEN` on duplicate or race). Returns `201 Created` with `CashSessionSummary`.
- **Current open session:** `GET /api/v1/pos/sessions/current?locationId=<uuid>` (optional `locationId`)
  - Response: `{ session: CashSessionSummary | null }`.
  - If `locationId` is omitted and the user holds `POS_CASH_SESSION` or `POS_SELL` at any assigned branch, returns their currently open session across branches (if any).
- **Session summary / reconciliation:** `GET /api/v1/pos/sessions/{id}/summary`
  - Returns live expected totals (`OPEN`) or saved reconciliation (`CLOSED`) including `byMethod[]` (`CASH`, `BKASH`, `NAGAD`, `CARD`).
- **Close session:** `POST /api/v1/pos/sessions/{id}/close`
  - Body:
    ```json
    {
      "countedCash": "5000.00",
      "countedBkash": "1200.00",
      "countedNagad": "800.00",
      "countedCard": "3000.00",
      "note": "Drawer balanced"
    }
    ```
  - Rules: `countedBkash`, `countedNagad`, and `countedCard` are optional and default to server-expected amounts when omitted. If any difference is non-zero, `note` is required (`422 VALIDATION_FAILED`). Cash refunds made during the session reduce `expectedCash`. Closed sessions are immutable in the DB (`merqo_protect_cash_session`).

---

## 3. Approval PIN and manager discount tokens

- **Set / update approval PIN:** `PUT /api/v1/auth/approval-pin`
  - Permission: caller must be `OWNER` or hold `POS_DISCOUNT_APPROVE` at an assigned location.
  - Body: `{ currentPassword: string, pin: string }` (PIN: 4–8 digits, validated by `ApprovalPinPolicy`). Returns `204 No Content`. Stored only as BCrypt hash.
- **Request single-use discount approval token:** `POST /api/v1/pos/approvals`
  - Body: `{ locationId: string, pin: string, maxDiscountPercent: string }`
  - Rules: Verifies an approver assigned to `locationId` (or owner) holding `POS_DISCOUNT_APPROVE`. Five wrong PIN attempts within 15 minutes lock for 15 minutes (`423 APPROVAL_LOCKED`). Invalid/missing PIN gives generic `422 APPROVAL_INVALID`.
  - Response (`201 Created`): `{ approvalToken: string, expiresAt: string, maxDiscountPercent: string }` (token is 128-bit random, single-use, 5-minute TTL, only SHA-256 hash stored).

---

## 4. Complete a POS sale

- **Endpoint:** `POST /api/v1/pos/sales`
- **Permission:** `POS_SELL` at `locationId` (+ `POS_DISCOUNT` when any manual line or bill discount is applied).
- **Header:** `Idempotency-Key: <string>` (or `idempotencyKey` field in body). Re-sending the exact key + payload returns `200 OK` with the existing `SaleDetail`; re-sending with a different payload returns `409 IDEMPOTENCY_CONFLICT`.
- **Request body:**
  ```json
  {
    "locationId": "<uuid>",
    "lines": [
      {
        "variantId": "<uuid>",
        "quantity": 2,
        "manualDiscountType": "PERCENT",
        "manualDiscountValue": "10"
      }
    ],
    "billDiscountType": "FIXED",
    "billDiscountValue": "50.00",
    "approvalToken": "<optional-single-use-token>",
    "payment": {
      "method": "CASH",
      "tendered": "2500.00",
      "reference": null
    },
    "note": "Optional customer note"
  }
  ```
  - Controller also accepts Phase 2 alias shapes (`manualDiscount: { type, value }`, `billDiscount: { type, value }`, `payment.amountGiven`, `payment.trxId`, `payment.cardReference`) for backward compatibility.
  - **Pricing & Discounts:** Server ignores all client prices and totals. Applies active `POS`-scope promotion, then line manual discount, then largest-remainder allocated bill discount. Fixed line discount `>=` post-promotion unit price is rejected (`422 INVALID_DISCOUNT`). Manual discount percent above `app_users.max_discount_percent` requires a valid `approvalToken`.
  - **Payments:** Single method per sale (`CASH` requires `tendered >= total`; `BKASH`/`NAGAD` require globally unique `reference` `^[A-Z0-9]{6,20}$` normalized uppercase, rejecting duplicates with `409 DUPLICATE_REFERENCE`; `CARD` requires approval code or last 4 digits; card PANs 13–19 digits or Luhn-valid are rejected with `422 CARD_NUMBER_REJECTED`).
  - **Stock:** Decremented via `InventoryService.remove` (`POS_SALE`), rejecting insufficient available stock with `409 INSUFFICIENT_STOCK` and zero partial changes.
- **Response (`201 Created`):** `SaleDetail`, including `receipt` (`ReceiptData` with `isDuplicate: false`), lines, payment, and optional `belowCostWarnings` / `unitCost` / `belowCost` (ONLY present when caller holds `COST_VIEW` at `locationId`).

---

## 5. Sales history, lookup & receipts

- **List sales (paginated):** `GET /api/v1/pos/sales?locationId=&fromDate=YYYY-MM-DD&toDate=YYYY-MM-DD&q=&page=0&size=20`
  - Permission: `POS_SELL` or `POS_REFUND`. Branches scoped via `visibleLocationIds`; date bounds interpreted in `Asia/Dhaka` business days.
  - Non-manager cashiers (users without `DISCOUNT_MANAGE` / `DASHBOARD_VIEW` / `FINANCE_REPORT_VIEW` / `AUDIT_VIEW` at that branch) see **only their own sales**; managers and owner see all sales in their permitted branches. Cost fields are omitted.
- **Sale detail:** `GET /api/v1/pos/sales/{id}`
  - Returns `SaleDetail`. Out-of-scope or another cashier's unpermitted sale returns `404 NOT_FOUND`. Cost fields included only with `COST_VIEW`.
- **Receipt lookup:** `GET /api/v1/pos/sales/lookup?receiptNumber=<string>` (case-insensitive exact match).
- **Receipt JSON (no reprint log):** `GET /api/v1/pos/sales/{id}/receipt` → `ReceiptData` (`isDuplicate: false`).
- **Duplicate reprint:** `POST /api/v1/pos/sales/{id}/reprint`
  - Requires `POS_REPRINT` at `locationId`. Writes append-only `receipt_reprints` row + `RECEIPT_REPRINTED` audit event. Returns `ReceiptData` with `isDuplicate: true`.

---

## 6. Sale returns & inspection

- **Create return:** `POST /api/v1/pos/sales/{saleId}/returns`
  - Permission: `POS_REFUND` at the sale's branch.
  - Header: `Idempotency-Key` (optional; same replay/conflict semantics as sales).
  - Body:
    ```json
    {
      "reason": "DEFECTIVE",
      "note": "Stitching tear",
      "lines": [
        { "saleLineId": "<uuid>", "quantity": 1, "disposition": "INSPECTION" }
      ]
    }
    ```
    (`reason`: `CHANGED_MIND` | `WRONG_SIZE` | `DEFECTIVE` | `OTHER`; `disposition` defaults to `RESTOCK` if omitted.)
  - Rules:
    - Enforces `pos.return_window_days` unless the actor (or an approving manager? — actor holding `POS_DISCOUNT_APPROVE` at the branch) overrides (`422 RETURN_WINDOW_EXPIRED` otherwise).
    - Rejects already-fully-returned lines (`409 ALREADY_RETURNED`) or over-quantity (`409 RETURN_QUANTITY_EXCEEDED`).
    - Refunds original payment method; `CASH` refund requires an open cash session and lowers `expectedCash`.
    - `RESTOCK` calls `InventoryService.receive`; `INSPECTION` calls `InventoryService.receiveToInspection`.
- **List sale returns:** `GET /api/v1/pos/sales/{saleId}/returns` → `SaleReturnRecord[]`.
- **List inspection queue:** `GET /api/v1/inventory/inspections?locationId=<uuid>` (requires `INVENTORY_VIEW`).
- **Resolve inspection:** `POST /api/v1/inventory/inspections/resolve`
  - Permission: `INVENTORY_ADJUST` at `locationId`.
  - Body: `{ locationId, variantId, quantity, outcome: "RESTOCK" | "WRITE_OFF", note? }`
  - `WRITE_OFF` flags unusual values using `threshold.unusual_adjustment_value` and omits `valueAtCost` if the caller lacks `COST_VIEW` / `PRICE_EDIT`.

---

## 7. Phase 3 Contract Alignment Changes (vs Phase 2 draft)

All changes made from the initial Phase 2 draft contract and `frontend/src/api/pos.ts`:

1. **Path prefixes moved under `/api/v1/pos`:**
   - `POST /pos/sessions/open` → `POST /pos/sessions`
   - `POST /sales` → `POST /pos/sales`
   - `GET /sales` → `GET /pos/sales`
   - `GET /sales/{id}` → `GET /pos/sales/{id}`
   - `GET /sales/by-receipt/{receiptNumber}` → `GET /pos/sales/lookup?receiptNumber=...`
   - `POST /sales/{id}/reprint` → `POST /pos/sales/{id}/reprint`
   - `POST /sales/{id}/returns` → `POST /pos/sales/{saleId}/returns`
   - Barcode lookup uses existing Phase 2 endpoint `GET /catalog/lookup?barcode=...&scope=POS&locationId=...`.
2. **Removed client price-trust fields & raw manager PIN on checkout:**
   - Added `PUT /auth/approval-pin` and `POST /pos/approvals` (single-use expiring `approvalToken`, replacing raw `managerPin` on `CreateSaleRequest`, though `pos.ts` keeps optional `managerPin` for frontend mock compatibility).
3. **Standardized payment & cash close request fields:**
   - Added `payment.tendered` and `payment.reference` (server still accepts Phase 2 aliases `amountGiven`, `trxId`, `cardReference`).
   - Added optional `countedBkash`, `countedNagad`, `countedCard` on `POST /pos/sessions/{id}/close`.
4. **Added new endpoints & types to `frontend/src/api/pos.ts`:**
   - `fetchCashSessionSummary`, `setApprovalPin`, `requestDiscountApproval`, `fetchSaleReceipt`, `listSaleReturns`, `listInspections`, `resolveInspection`.
5. **Added line-level return disposition & idempotency headers:**
   - `CreateReturnRequest.lines[].disposition` (`RESTOCK` | `INSPECTION`, default `RESTOCK`), plus `Idempotency-Key` header support on `createSale` and `createSaleReturn`.
6. **Removed standalone `POST /pos/price-check`:**
   - Below-cost checks happen during `POST /pos/sales` and Phase 2 `GET /catalog/variants/{id}/price-preview`; `checkSalePrices` remains in `pos.ts` for frontend mock compatibility.
