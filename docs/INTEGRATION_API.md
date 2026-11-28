# INTEGRATION_API.md

**Merqo Website Integration API (`integration/v1`)**

Merqo exposes a dedicated, stateless, HMAC-SHA256 authenticated integration surface for external storefronts (WooCommerce, custom Next.js/React shops, Shopify middleware, or Merqo's local website simulator).

> **Isolation boundary:** Integration keys **cannot** call `/api/v1/**` staff endpoints, and browser session cookies (`MERQO_SESSION`) **cannot** call `/integration/v1/**`. Responses never include internal cost prices, customer risk scores, call notes, or branch-level retail stock.

---

## 1. Key Management (Owner Only)

The business owner creates and rotates external client keys in **Settings -> Integration Keys** or via API:

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/v1/integration/clients` | List registered integration keys (never returns secrets) |
| `POST` | `/api/v1/integration/clients` | Create key; returns `secret` **once** (stored AES-GCM encrypted via `INTEGRATION_SECRET_KEY`) |
| `POST` | `/api/v1/integration/clients/{id}/rotate` | Rotate secret; invalidates old secret immediately |
| `POST` | `/api/v1/integration/clients/{id}/disable` | Disable client (`403 INTEGRATION_DISABLED`) |
| `POST` | `/api/v1/integration/clients/{id}/enable` | Re-enable disabled client |

---

## 2. Authentication & Signing

Every call to `/integration/v1/**` must send these three headers:

| Header | Value |
| --- | --- |
| `X-Merqo-Key` | Public key ID (e.g. `mk_...`) |
| `X-Merqo-Timestamp` | Unix epoch seconds in UTC |
| `X-Merqo-Signature` | Lowercase hex `HMAC_SHA256(secret, timestamp + "." + rawBody)` (optional `sha256=` prefix accepted) |

- **Replay protection:** Timestamps outside `±300` seconds (configurable up to `60–900s` via setting `integration.signature_window_seconds`) fail with `401 SIGNATURE_EXPIRED`.
- **Rate limit:** `60 requests / minute` per key, backed by PostgreSQL (`429 RATE_LIMITED`).
- **GET requests:** Sign `${timestamp}.` (an empty raw body string).

### Signing Recipes

#### Node.js (18+, zero dependencies)

```js
import { createHmac } from "node:crypto";

const rawBody = JSON.stringify(orderPayload);
const ts = Math.floor(Date.now() / 1000).toString();
const sig = createHmac("sha256", SECRET).update(`${ts}.${rawBody}`, "utf8").digest("hex");

await fetch(`${BASE_URL}/integration/v1/orders`, {
  method: "POST",
  headers: {
    "Content-Type": "application/json",
    "X-Merqo-Key": KEY_ID,
    "X-Merqo-Timestamp": ts,
    "X-Merqo-Signature": sig,
  },
  body: rawBody,
});
```

#### PowerShell

```powershell
$ts = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString()
$hmac = [System.Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($Secret))
$hash = $hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes("$ts.$rawBody"))
$sig = ([BitConverter]::ToString($hash)).Replace("-", "").ToLowerInvariant()
```

#### curl + openssl

```bash
TS=$(date +%s)
SIG=$(printf '%s.%s' "$TS" "$RAW_BODY" | openssl dgst -sha256 -hmac "$SECRET" | awk '{print $2}')
curl -X POST "$BASE_URL/integration/v1/orders" \
  -H "Content-Type: application/json" \
  -H "X-Merqo-Key: $KEY_ID" \
  -H "X-Merqo-Timestamp: $TS" \
  -H "X-Merqo-Signature: $SIG" \
  -d "$RAW_BODY"
```

Ready-to-run simulator scripts live at `tools/website-simulator/send-order.mjs` and `tools/website-simulator/send-order.ps1`.

---

## 3. Endpoints

### Submit Online Order — `POST /integration/v1/orders`

`externalOrderId` serves as the idempotency key per `(client_id, externalOrderId)`.

#### Request Body

```json
{
  "externalOrderId": "WEB-10492",
  "customerName": "Rafiq Ahmed",
  "phone": "+880 1711-223344",
  "address": "House 12, Road 4, Dhanmondi, Dhaka",
  "district": "Dhaka",
  "lines": [
    {
      "sku": "TSHIRT-BLK-M",
      "quantity": 2,
      "unitPrice": "550.00"
    }
  ],
  "shippingFee": "60.00",
  "discountTotal": "0.00",
  "total": "1160.00",
  "paymentMode": "ADVANCE_COD",
  "advanceAmount": "200.00",
  "codAmount": "960.00",
  "claimedAdvanceMethod": "BKASH",
  "claimedAdvanceReference": "TRX889911",
  "note": "Deliver after 5 PM"
}
```

#### Rules Enforced on Intake

- **Phone normalization:** `+880`/`880`/spaces/dashes normalize to `01[3-9]XXXXXXXX`. Unnormalizable numbers are **accepted** and flagged (`invalidPhone: true`) for staff correction before confirmation.
- **Line & total reconciliation:** `lines` (`1..50` items), positive quantities, `total == sum(qty * unitPrice) - discountTotal + shippingFee` within `0.01`, else `422 ORDER_TOTAL_MISMATCH`.
- **Payment modes:**
  - `COD`: `advanceAmount == 0.00`, `codAmount == total`
  - `FULL_PREPAID`: `advanceAmount == total`, `codAmount == 0.00`
  - `ADVANCE_COD`: `0 < advanceAmount < total`, `advanceAmount + codAmount == total`
  - `claimedAdvanceMethod` (`BKASH | NAGAD | CARD | BANK`) & `claimedAdvanceReference` are recorded as unverified customer claims.
- **Price comparison:** Merqo compares incoming `unitPrice` against its live `ONLINE` catalog/promo price (`catalog_online_price`). Discrepancies flag `priceMismatch: true` for call-center review without dropping the customer's order.
- **Idempotency & retry:**
  - Identical `externalOrderId` + identical SHA-256 payload after success returns `200 OK` (`idempotentReplay: true`) with the existing order.
  - Same `externalOrderId` with a different payload returns `409 IDEMPOTENCY_CONFLICT`.
  - If a submission failed (e.g. `422 UNKNOWN_SKU`), `integration_inbox` stays `FAILED` (`attempts` tracked) and retrying after fixing the SKU/catalog succeeds with `201 Created`.

#### Response (`201 Created` or `200 OK` on replay)

```json
{
  "orderId": "d1a4c...",
  "orderNumber": "ORD-000042",
  "externalOrderId": "WEB-10492",
  "status": "PENDING_CONFIRMATION",
  "priceMismatch": false,
  "invalidPhone": false,
  "idempotentReplay": false,
  "total": 1160.00,
  "createdAt": "2026-11-28T06:00:00Z"
}
```

### Query Order Status — `GET /integration/v1/orders/{externalOrderId}`

Returns `{ orderId, orderNumber, externalOrderId, status, total, createdAt, confirmedAt, closedAt }` for orders submitted by the authenticated client (returns `404` for other clients' orders).

### Check Online Stock Availability — `GET /integration/v1/stock?sku=SKU1,SKU2`

Computes sellable warehouse stock as `max(0, onHand - reserved - softHolds - onlineSafetyStock)`:

```json
{
  "locationId": "7f3a...",
  "items": [
    {
      "sku": "TSHIRT-BLK-M",
      "found": true,
      "inStock": true,
      "available": 12
    }
  ]
}
```

---

## 4. Error Codes

All errors return RFC 7807 `application/problem+json` with `errorCode`:

| HTTP | `errorCode` | Meaning |
| --- | --- | --- |
| `400` | `BAD_REQUEST` | Malformed JSON or unreadable body |
| `401` | `INVALID_SIGNATURE` | Missing headers, unknown `X-Merqo-Key`, or HMAC mismatch |
| `401` | `SIGNATURE_EXPIRED` | `X-Merqo-Timestamp` outside replay window |
| `401` | `UNAUTHENTICATED` | Browser session cookie used on `/integration/v1/**` (or API headers on `/api/v1/**`) |
| `403` | `INTEGRATION_DISABLED` | Key was disabled by owner |
| `409` | `IDEMPOTENCY_CONFLICT` | Same `externalOrderId` reused with modified payload |
| `409` | `ONLINE_LOCATION_NOT_SET` | No active warehouse configured with `online_fulfillment = true` |
| `422` | `UNKNOWN_SKU` | SKU not found, inactive variant, or inactive product |
| `422` | `ORDER_TOTAL_MISMATCH` | Recomputed total differs from stated `total` by more than `0.01` |
| `422` | `VALIDATION_FAILED` | Missing/invalid required fields or invalid `PaymentTerms` |
| `429` | `RATE_LIMITED` | Client exceeded 60 requests per minute |
