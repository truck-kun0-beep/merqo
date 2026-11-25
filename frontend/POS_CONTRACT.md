# Merqo Point of Sale (POS) API Contract

All endpoints live under `/api/v1`. Money values are serialized as decimal strings with 2 decimal places (`"1250.00"`). Quantities are whole integers. Errors use RFC 7807 `ProblemDetail` JSON with `errorCode`, `correlationId`, and `fieldErrors`.

---

## 1. Barcode Lookup

### `GET /api/v1/pos/lookup?barcode={code}&locationId={uuid}`

Permissions: `POS_SELL` (or `PRODUCT_VIEW`) scoped to `locationId`.

Response `200`:
```json
{
  "variantId": "uuid",
  "productId": "uuid",
  "productName": "Cotton Crew T-Shirt",
  "sku": "TEE01-BLA-M",
  "variantLabel": "Black / M",
  "barcode": "2000000000001",
  "listPrice": "750.00",
  "promotionName": "Weekend 10% Off",
  "promotionDiscount": "75.00",
  "netUnitPrice": "675.00",
  "taxRatePercent": "5",
  "taxInclusive": false,
  "availableStock": 18,
  "belowCost": false
}
```

Errors:
- `404 BARCODE_NOT_FOUND` — unknown or deactivated barcode/variant.

---

## 2. Cash Sessions

### `GET /api/v1/pos/sessions/current?locationId={uuid}`

Permissions: `POS_CASH_SESSION` or `POS_SELL` at `locationId`.

Response `200`:
```json
{
  "session": {
    "id": "uuid",
    "locationId": "uuid",
    "cashierUsername": "cashier.one",
    "openedAt": "2026-11-25T09:00:00Z",
    "closedAt": null,
    "status": "OPEN",
    "openingCash": "5000.00",
    "expectedCash": "6417.50",
    "countedCash": null,
    "variance": null,
    "saleCount": 1,
    "refundTotal": "0.00",
    "byMethod": [
      { "method": "CASH", "expected": "6417.50", "counted": null, "difference": null },
      { "method": "BKASH", "expected": "1988.75", "counted": null, "difference": null },
      { "method": "NAGAD", "expected": "0.00", "counted": null, "difference": null },
      { "method": "CARD", "expected": "0.00", "counted": null, "difference": null }
    ]
  }
}
```
When no session is open: `{ "session": null }`.

### `POST /api/v1/pos/sessions/open`

Permissions: `POS_CASH_SESSION` at `locationId`.

Request:
```json
{
  "locationId": "uuid",
  "openingCash": "5000.00"
}
```
Response `201`: `CashSessionSummary`.
Errors: `409 CONFLICT` if an open session already exists at `locationId`.

### `POST /api/v1/pos/sessions/{id}/close`

Permissions: `POS_CASH_SESSION` at the session's location.

Request:
```json
{
  "countedCash": "6400.00",
  "note": "End of shift"
}
```
Response `200`: `CashSessionSummary` with `status: "CLOSED"`, `closedAt`, `countedCash`, `variance`, and updated `byMethod`.

---

## 3. Price & Below-Cost Check

### `POST /api/v1/pos/price-check`

Permissions: `POS_SELL` at `locationId`.

Request:
```json
{
  "locationId": "uuid",
  "lines": [
    {
      "variantId": "uuid",
      "quantity": 2,
      "manualDiscountType": "PERCENT",
      "manualDiscountValue": "20"
    }
  ],
  "billDiscountType": null,
  "billDiscountValue": null
}
```
Response `200`:
```json
{
  "belowCostWarnings": [
    {
      "variantId": "uuid",
      "sku": "TEE01-BLA-M",
      "netUnitPrice": "400.00",
      "costPrice": "420.00"
    }
  ]
}
```
`belowCostWarnings` is empty unless the user has cost/price visibility or server policy surfaces below-cost warnings.

---

## 4. Complete Sale

### `POST /api/v1/sales`

Permissions: `POS_SELL` at `locationId` (plus `POS_DISCOUNT` if manual discounts are present; `POS_DISCOUNT_APPROVE` / valid `managerPin` if manual discount exceeds cashier's `maxDiscountPercent`).

Request:
```json
{
  "locationId": "uuid",
  "cashSessionId": "uuid",
  "lines": [
    {
      "variantId": "uuid",
      "quantity": 2,
      "manualDiscountType": "PERCENT",
      "manualDiscountValue": "5"
    }
  ],
  "billDiscountType": "FIXED",
  "billDiscountValue": "50.00",
  "payment": {
    "method": "CASH",
    "amountGiven": "2000.00",
    "trxId": null,
    "cardReference": null
  },
  "managerPin": "1234",
  "note": null
}
```
One payment method per sale (`CASH` | `BKASH` | `NAGAD` | `CARD`).
- `CASH`: `amountGiven` must be `>= total`.
- `BKASH` / `NAGAD`: `trxId` is required.
- `CARD`: `cardReference` accepts approval code or last 4 digits only (never full PAN).

Response `201`: `SaleDetail` (includes `receipt: ReceiptData` and `belowCostWarnings?`).

Errors:
- `409 POS_SESSION_REQUIRED` — no open cash session at `locationId`.
- `409 INSUFFICIENT_STOCK` — requested quantity exceeds available stock.
- `422 DISCOUNT_LIMIT_EXCEEDED` — manual discount exceeds cashier's `maxDiscountPercent` without valid `managerPin`.
- `422 VALIDATION_FAILED` — invalid payment fields, negative amounts, or full card number entered.

---

## 5. Sales History, Receipt Lookup, Reprint & Returns

### `GET /api/v1/sales?locationId={uuid}&fromDate=YYYY-MM-DD&toDate=YYYY-MM-DD&q={receipt}&page=0&size=20`

Response `200`: `PageResponse<SaleSummary>`.

### `GET /api/v1/sales/{id}`

Response `200`: `SaleDetail`.

### `GET /api/v1/sales/by-receipt/{receiptNumber}`

Response `200`: `SaleDetail`. Error: `404 NOT_FOUND`.

### `POST /api/v1/sales/{id}/reprint`

Permissions: `POS_REPRINT` at sale's location.
Response `200`: `ReceiptData` with `isDuplicate: true`.

### `POST /api/v1/sales/{id}/returns`

Permissions: `POS_REFUND` at sale's location.

Request:
```json
{
  "reason": "Wrong size",
  "lines": [
    { "saleLineId": "uuid", "quantity": 1 }
  ]
}
```
Response `201`: `SaleReturnRecord`:
```json
{
  "id": "uuid",
  "refundNumber": "RFN-2026-000001",
  "returnedAt": "2026-11-25T14:00:00Z",
  "actorUsername": "manager.one",
  "reason": "Wrong size",
  "totalRefund": "708.75",
  "lines": [
    {
      "saleLineId": "uuid",
      "sku": "TEE01-BLA-M",
      "quantity": 1,
      "refundAmount": "708.75"
    }
  ]
}
```
Errors:
- `409 ALREADY_RETURNED` — sale or selected line has no remaining returnable quantity.
- `422 VALIDATION_FAILED` — missing reason or quantity exceeds remaining returnable quantity.

---

## 6. Dev Mock Test Barcodes (`VITE_POS_MOCKS=1`)

- Variants 1–40 use 200 sequential 13-digit barcodes `2000000000001` through `2000000000200` (5 barcodes per variant).
- Variant 40 (`2000000000196`–`2000000000200`) starts with `0` stock to test `409 INSUFFICIENT_STOCK`.
- `9999999999991` simulates a slow response (1 s delay) followed by `404 BARCODE_NOT_FOUND`.
- Seeded receipts: `MQ-2026-000101` (`CASH`) and `MQ-2026-000102` (`BKASH`).
- Manager discount approval PIN in mock mode: `1234`.
