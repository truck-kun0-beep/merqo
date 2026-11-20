# INTEGRATION_API.md

**The website integration API.**

This document specifies how an existing website sends online orders into Merqo.
The implementation arrives in **Prompt 4**; this is the contract that prompt
will build against, written now so the design is fixed before any code.

> **Status:** specified, not implemented. The interface
> `WebsiteOrderSource` and the inbox table arrive in Prompt 4. The
> `WEBSITE_MODE=generic` adapter is marked *unimplemented* until then.

---

## 1. Overview

```
Website (WooCommerce, custom shop, anything)
        │
        │  HTTPS POST /api/v1/integrations/orders
        │  Headers: X-Merqo-Key, X-Merqo-Signature, X-Merqo-Timestamp,
        │           X-Merqo-Idempotency-Key
        ▼
Merqo integration inbox
        │
        │  the order becomes a soft hold on the warehouse stock
        ▼
Online order screen (staff calls the customer, then confirms)
```

Key properties:

| Property | How |
| --- | --- |
| **Authenticated** | API key plus an HMAC-SHA256 signature over the raw body |
| **Replay safe** | every request carries an idempotency key; a repeat returns the first answer |
| **Retry safe** | a failed request can be sent again with the same key without creating a second order |
| **Isolated** | the website can never see staff data, stock levels of a branch or any other order |

---

## 2. Authentication

Two headers are required.

**`X-Merqo-Key`** - the key that identifies the website. It is stored in the
`settings` table under `integration.website.key` and is never shown in the UI.

**`X-Merqo-Signature`** - `sha256=<hex>` where the hex is

```
HMAC_SHA256(secret, timestamp + "." + rawRequestBody)
```

- `secret` is the shared secret from `settings` under
  `integration.website.secret`.
- `timestamp` is the value of the `X-Merqo-Timestamp` header in **Unix
  seconds**.
- `rawRequestBody` is the exact bytes of the body, before any parsing. Reading
  the body as a string and re-serialising it would change the bytes and break
  the signature.

**`X-Merqo-Timestamp`** - Unix seconds. A request whose timestamp is more than
**300 seconds** away from the server time is rejected with
`401 SIGNATURE_EXPIRED`. This stops a captured request from being replayed
later.

**`X-Merqo-Idempotency-Key`** - a unique string per order, for example the
website's own order number. The same key must never be reused for a different
order.

---

## 3. Creating an order

```
POST /api/v1/integrations/orders
Content-Type: application/json
```

### Request

```json
{
  "externalOrderNumber": "WEB-2026-000451",
  "placedAt": "2026-11-20T09:14:22Z",
  "channel": "ONLINE",
  "customer": {
    "name": "Ayesha Rahman",
    "phone": "+8801700000000",
    "email": "customer@example.com"
  },
  "deliveryAddress": {
    "line1": "House 12, Road 7, Dhanmondi",
    "area": "Dhanmondi",
    "city": "Dhaka",
    "postalCode": "1209",
    "country": "BD"
  },
  "payment": {
    "method": "COD",
    "advanceAmount": 0,
    "codAmount": 2450.00
  },
  "lines": [
    {
      "sku": "TS-BLK-M",
      "name": "Cotton T-Shirt (Black, M)",
      "quantity": 2,
      "unitPrice": 850.00
    },
    {
      "sku": "JKT-DNM-L",
      "name": "Denim Jacket (L)",
      "quantity": 1,
      "unitPrice": 750.00
    }
  ],
  "notes": "Please call before delivery"
}
```

### Field rules

| Field | Rule |
| --- | --- |
| `externalOrderNumber` | required, unique per website, at most 60 characters |
| `placedAt` | optional, ISO-8601 UTC, defaults to the arrival time |
| `customer.phone` | required, digits only after removing spaces, `+` and dashes, 10 to 15 digits |
| `deliveryAddress.line1` | required, at most 240 characters |
| `deliveryAddress.area` | optional but strongly recommended; it is used to match a Pathao area id |
| `lines` | at least one line, at most 100 lines |
| `lines.sku` | required; unknown SKUs are rejected, they are not silently skipped |
| `lines.quantity` | from 1 to 999 |
| `lines.unitPrice` | a decimal with at most two decimals, not negative |
| `payment.method` | `COD`, `PREPAID` or `ADVANCE_COD` |
| `payment.advanceAmount` | required when the method is `ADVANCE_COD`, otherwise 0 |
| `payment.codAmount` | required when the method is `ADVANCE_COD` or `COD` |

### Successful answer

```
201 Created
```

```json
{
  "externalOrderNumber": "WEB-2026-000451",
  "merqoOrderNumber": "ORD-2026-000451",
  "status": "RECEIVED",
  "receivedAt": "2026-11-20T09:14:25Z",
  "softHeld": true
}
```

The order is now a **soft hold**: the stock is subtracted from what the website
sees, but it is not yet hard reserved. Hard reservation happens when staff
confirm the order after calling the customer.

### Errors

Every error uses the same RFC 7807 shape as the rest of the API.

| Status | `errorCode` | Meaning |
| --- | --- | --- |
| 400 | `INVALID_SIGNATURE` | the HMAC does not match |
| 401 | `UNKNOWN_API_KEY` | the key is not known or is deactivated |
| 401 | `SIGNATURE_EXPIRED` | the timestamp is more than 300 seconds away |
| 409 | `DUPLICATE_ORDER` | the idempotency key was used with a different body |
| 422 | `UNKNOWN_SKU` | at least one line refers to a SKU that does not exist |
| 422 | `INVALID_PAYMENT_TERMS` | the amounts do not add up to the order total |
| 422 | `INSUFFICIENT_STOCK` | not enough warehouse stock for the online safety buffer |
| 422 | `VALIDATION_FAILED` | a field is missing or has the wrong format; `fieldErrors` says which |

---

## 4. Idempotency

The idempotency key is stored in the integration inbox table together with a
hash of the request body.

| Situation | Answer |
| --- | --- |
| First request with this key | `201 Created`, the order is created |
| The same key and the same body again | `200 OK` with the **same** order number, nothing is duplicated |
| The same key but a different body | `409 DUPLICATE_ORDER` |

This is what makes it safe for the website to retry a request that timed out.

---

## 5. Checking the status

```
GET /api/v1/integrations/orders/{externalOrderNumber}
```

Returns the same object as the create answer, plus the current status:

```
RECEIVED -> CALLED -> CONFIRMED | DECLINED | CANCELLED
CONFIRMED -> PACKED -> SHIPPED -> DELIVERED
```

Customers cannot cancel. A customer who wants to cancel tells the staff, and
the staff record the cancellation request and cancel the order themselves.

---

## 6. Status updates sent to the website (outgoing, optional)

Not required for the integration to work, but the website usually wants to know
the status. Merqo can call a webhook on the website:

```
POST {websiteCallbackUrl}
X-Merqo-Signature: sha256=<hex>   (HMAC over the body with the shared secret)
```

```json
{
  "externalOrderNumber": "WEB-2026-000451",
  "merqoOrderNumber": "ORD-2026-000451",
  "status": "SHIPPED",
  "courier": { "name": "Pathao", "consignmentId": "PT-778899001" },
  "changedAt": "2026-11-20T13:02:00Z"
}
```

The website answers `200 OK`. A non-2xx answer is retried three times with a
delay, and the failed deliveries are listed in the demo console.

---

## 7. The inbox table

The inbox is append-only. Every incoming request is written down before it is
processed, so a request that crashes the processing can be found and replayed.

| Column | Type | Meaning |
| --- | --- | --- |
| `id` | `UUID` | primary key |
| `api_key_id` | `UUID` | which website sent it |
| `idempotency_key` | `VARCHAR(120)` | the key from the header |
| `request_hash` | `VARCHAR(64)` | SHA-256 of the raw body |
| `payload` | `JSONB` | the body as received |
| `signature_valid` | `BOOLEAN` | whether the HMAC matched |
| `received_at` | `TIMESTAMPTZ` | when it arrived |
| `processed_at` | `TIMESTAMPTZ` | when it was handled |
| `order_id` | `UUID` | the order that was created |
| `status` | `VARCHAR(30)` | `RECEIVED`, `ACCEPTED` or `REJECTED` |
| `error_code` | `VARCHAR(60)` | why it was rejected |

---

## 8. The `WebsiteOrderSource` interface

The rest of Merqo never talks to the website directly. It talks to this
interface, so the implementation can be swapped without changing the orders
module.

```java
public interface WebsiteOrderSource {

  /** The name shown in the UI, for example "Website" or "WooCommerce". */
  String name();

  /**
   * Verifies that an incoming request really comes from the website.
   *
   * @param key the API key that was presented
   * @param signature the HMAC signature that was presented
   * @param timestamp the timestamp that was presented, in Unix seconds
   * @param rawBody the exact bytes of the request body
   * @return the result of the verification
   */
  VerificationResult verify(String key, String signature, Instant timestamp, byte[] rawBody);

  /**
   * Turns a verified payload into a new online order.
   *
   * @param payload the body as received
   * @param idempotencyKey the key that makes a retry safe
   * @return the created order
   */
  OrderReceipt accept(JsonNode payload, String idempotencyKey);
}
```

Two implementations:

| Implementation | `WEBSITE_MODE` | Purpose |
| --- | --- | --- |
| `MockWebsiteOrderSource` | `mock` | generates orders locally, used by the demo console |
| `GenericWebsiteOrderSource` | `generic` | the HTTP API described in this document |
| `WooCommerceWebsiteOrderSource` | `woocommerce` | later, converts the WooCommerce shape into the generic shape |

---

## 9. What is deliberately not in this API

- **No customer accounts.** A customer cannot log in, cannot cancel and cannot
  see their order history. Staff handle all of that on the phone.
- **No payment details.** The website never sends card numbers. It only states
  whether the order is prepaid, full COD or advance plus COD.
- **No stock numbers.** The website never asks "how many are left". It sends
  an order and Merqo answers.
- **No direct database access.** The website only uses this API.
