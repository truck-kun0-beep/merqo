Merqo, Phase 3: POS backend (sales, cash sessions, payments, receipts, returns)

The master brief (Phase 0), Phases 1 and 2 and all their rules remain in force. Do not build ahead: no online orders, customers, courier, supplier, costs or dashboard features. Do not edit frontend/ except frontend/POS_CONTRACT.md and frontend/src/api/pos.ts if a request or response shape changes.

Operating rules: read each file at most once, in full, never in line ranges. Write whole files with one create call. Decide, don't investigate. Commit after each numbered step on main. If something fails twice, stop and report the exact error. Do not push.

0. Step 0: confirm the base (one pass)

Run git status and the full backend suite once. All tests must be green before you start. Read frontend/POS_CONTRACT.md once. It was written by the UI engineer against a mock and is your starting contract: keep its paths, field names and error codes wherever they do not conflict with this prompt. Where you must change something, update the file and list the change in your report. Then send the short plan (tables, endpoints, risks) and build.

1. Scope

In scope: cash sessions; manager approval PIN and single-use approval tokens; POS sales with server-side pricing; discount stacking and limits; one payment per sale with reference rules; receipts and logged reprints; sale lookup by receipt number; sales history; returns and refunds with stock disposition; minimal inspection resolution; end-of-day reconciliation; demo data.

Out of scope: split billing, voiding or editing sales, customers and phone history (Phase 4), online orders, real payment APIs, dashboards, thermal-printer output.

2. Hard rules
Phase 1 and 2 rules all apply: default deny, AccessContext checks in services, client location ids never trusted, 404 for out-of-scope records and 403 for a missing permission in scope, repositories take location-id sets, owner-aware visibility (use the existing owner-aware helper pattern), RFC 7807 errors, never edit an applied migration.
The server computes every price. The request carries variant ids, quantities and discount intent only. Any price, total or tax sent by the client is ignored. A test proves tampering has no effect.
Business rules are plain Java classes testable with new and no Spring (section 3).
Money uses Money only. Quantities are whole numbers. Rounding follows PriceCalculator.
A sale is final. There is no void or edit. Corrections are returns. These tables get append-only database triggers like the Phase 2 ones: sales, sale_lines, sale_payments, sale_returns, sale_return_lines, receipt_reprints. Test the triggers and the absence of update and delete code paths.
Cost is sensitive. No cost, margin or profit field appears in any response for a user without COST_VIEW at that location. A cashier's responses contain none. Test with Jackson key checks, never with JSONPath filters (they pass vacuously on missing fields).
One payment method per sale. Methods: CASH, BKASH, NAGAD, CARD. Never store a full card number.
Branch stock is reduced by POS sales at the sale's own location only. A POS sale never touches warehouse stock. Every stock change goes through the Phase 2 InventoryService hooks, never directly on balances.
Sales never allow stock to go negative. Selling against reserved stock is not allowed: use available stock.
3. Pure Java domain classes (viva core)

Framework-free, full unit tests, Javadoc naming responsibility and the OOP concept.

Class	Responsibility
PaymentMethod (enum)	The four methods. Each knows whether it needs a reference, what kind, and whether it affects the cash drawer
PaymentRules	Validates a payment against the bill total: cash needs tendered at least total and computes change; BKASH and NAGAD need a TrxID (6 to 20 letters and digits, case-insensitive, normalized to upper case); CARD needs an approval code (4 to 12 letters and digits) or last 4 digits. Rejects any value that looks like a card number (13 to 19 digits, with or without spaces or dashes, or one that passes the Luhn check) with CARD_NUMBER_REJECTED, for every method
SaleCalculator	Takes resolved list prices, promotions, manual discount intent and tax policy, and returns a SaleTotals record: per-line LinePricing, subtotal, promotion discount, manual discount, tax, total. Composes PriceCalculator. Whole-bill discount is spread over lines by DiscountAllocator
DiscountAllocator	Spreads a whole-bill discount over lines proportionally using the largest-remainder method so allocated parts sum exactly to the discount, to the cent. Document and test the tie-break rule
DiscountLimitPolicy	Manual discount percent is measured against the price after promotion (per line, or against the bill subtotal after promotions for a whole-bill discount). Returns WITHIN_LIMIT or NEEDS_APPROVAL against the cashier's max_discount_percent. A user holding POS_DISCOUNT_APPROVE at the location has no limit. POS_DISCOUNT is required for any manual discount. A fixed discount at or above the line price is rejected
ApprovalPinPolicy	PIN is 4 to 8 digits. In non-demo mode it rejects all-same digits and straight sequences (1234, 4321). Demo seeding may bypass this
ApprovalTokenRules	A token is valid if unused, unexpired (5 minutes, from a Clock), for the same cashier and location, and its approved ceiling covers the requested discount. Single use
CashSessionReconciler	Given opening cash, completed sales and refunds, returns expected cash (opening + cash sales - cash refunds) and expected totals for each non-cash method. Given counted amounts, returns the difference per method
ReturnRules	Per sale line, returnable = sold - alreadyReturned. Rejects zero, negative and over-quantity. Refund amount per unit is the line's net unit price plus its tax share, rounded with Money; a full return of a line refunds exactly what was paid for that line, with no rounding drift (the last unit takes the remainder)
ReceiptNumber	Formats MQ-<year>-<6 digits> with the year taken in Asia/Dhaka. Document and test it. It must never collide with BarcodeRules (the MQ- prefix is reserved for it)
ReceiptRenderer (interface)	Takes ReceiptData and returns output. Implement JsonReceiptRenderer (the API response) and PlainTextReceiptRenderer (fixed-width 42 columns, 32 for 58 mm). This is the Strategy and Adapter idea for a later printer driver
4. Data model (next Flyway version)

UUID keys, TIMESTAMPTZ, sequences for human numbers (MQ- receipts through a sequence with the Dhaka-year prefix, RET-000001, CS-000001).

app_users: add approval_pin_hash (nullable) and PIN attempt tracking in a new table pin_attempts (user id or location, attempted_at, success flag), throttled like login (5 failures in 15 minutes locks PIN approval for that cashier for 15 minutes). Operational data, cleaned like login_attempts.
cash_sessions: session_number, location_id, cashier_id, opening_cash, opened_at, status (OPEN, CLOSED), closed_at, closed_by, counted_cash, counted_bkash, counted_nagad, counted_card, expected_* snapshots, difference_*, close_note. A partial unique index allows one OPEN session per cashier. A trigger rejects DELETE and rejects any UPDATE once the row is CLOSED.
sales: receipt_number unique, location_id, cash_session_id, cashier_id, subtotal, promotion_discount, manual_discount, tax_total, total, payment_method, discount_approved_by (nullable), idempotency_key (unique per cashier), created_at.
sale_lines: variant id, snapshots (name, SKU, barcode, color, size), quantity, list price, promotion id and discount, manual discount and who applied it, tax rate and tax, net unit price, line total, unit_cost_snapshot (internal, never serialized to users without cost permission), below_cost flag.
sale_payments: one row per sale (unique sale_id), method, amount, tendered, change_given, reference (normalized), unique index on (method, reference) where reference is not null for BKASH and NAGAD, unique on (method, reference, location) is not enough: the TrxID rule is global.
approval_tokens: token hash, cashier, location, max_discount_percent, approver id, expires_at, used_at. Tokens are random 128-bit values, only the hash is stored.
sale_returns and sale_return_lines: return_number, sale id, location, reason enum (CHANGED_MIND, WRONG_SIZE, DEFECTIVE, OTHER, note required for OTHER), refund method (always the original payment method), refund amount, cash_session_id, per line quantity, refund amount, disposition (RESTOCK or INSPECTION), created_by.
receipt_reprints: sale id, printed_by, printed_at, count by query.
Settings: add pos.return_window_days (integer, default 30) to SettingKey.
Audit actions to add: POS_SALE_COMPLETED, POS_DISCOUNT_APPROVED, POS_APPROVAL_FAILED, POS_RETURN_CREATED, RECEIPT_REPRINTED, CASH_SESSION_OPENED, CASH_SESSION_CLOSED, APPROVAL_PIN_CHANGED, INSPECTION_RESOLVED. Sales are not individually audited by old/new values, but discount overrides, returns, reprints, below-cost sales, session close differences and PIN changes are.
5. Endpoints (/api/v1, align with POS_CONTRACT.md)

Cash sessions (POS_CASH_SESSION at the location): POST /pos/sessions (open; 409 SESSION_ALREADY_OPEN if the cashier already has one), GET /pos/sessions/current, POST /pos/sessions/{id}/close (counted amounts per method; returns expected, counted and difference per method; a note is mandatory if any difference is not zero). The cashier closes their own session. A user holding POS_DISCOUNT_APPROVE at the location may close a stale session of someone else. Expected figures are computed server-side, never taken from the client. Provide GET /pos/sessions/{id}/summary (live expected figures while open, for the end-of-day screen).

Approval PIN: PUT /auth/approval-pin (a user holding POS_DISCOUNT_APPROVE anywhere, or the owner; needs the current password; audited). POST /pos/approvals takes { locationId, pin, maxDiscountPercent } and succeeds if the PIN matches any user who holds POS_DISCOUNT_APPROVE at that location (owner included; compare against each candidate's hash; the approver count is tiny). It returns a single-use token, its expiry and the approved ceiling. Failures are throttled and audited, with a generic message. The response never names the approver.

Sales (POS_SELL at the location):

POST /pos/sales with Idempotency-Key header or body field. Body: locationId, lines[{ variantId, quantity, manualDiscount? }], optional whole-bill discount, optional approvalToken, payment{ method, tendered?, reference? }. Requires an open session for this cashier at this location (409 POS_SESSION_REQUIRED). In one transaction: resolve promotions for scope POS at the location, compute totals, enforce discount limits (422 DISCOUNT_LIMIT_EXCEEDED when approval is needed and none or an insufficient token was given), validate payment, remove stock per line (409 INSUFFICIENT_STOCK listing the lines, with no partial sale), write the sale, lines and payment, consume the token, write movements with reference type SALE. The response is the receipt data plus change.
Idempotency: the same key from the same cashier returns the original sale unchanged (200 with the same receipt, no second movement). A different body with the same key returns 409 IDEMPOTENCY_CONFLICT.
GET /pos/sales (history: location, date range in Asia/Dhaka days, receipt search, pagination). A user who holds none of POS_REFUND, POS_DISCOUNT_APPROVE sees only their own sales. Others see the sales of the locations they may view.
GET /pos/sales/{id} and GET /pos/sales/lookup?receiptNumber= (exact match, normalized to upper case).
GET /pos/sales/{id}/receipt returns ReceiptData matching the frontend ReceiptData type in frontend/src/api/models.ts. Include isDuplicate: false. POST /pos/sales/{id}/reprint (POS_REPRINT) logs a receipt_reprints row and returns the same data with isDuplicate: true and the reprint count. The first print is not logged.

Returns (POS_REFUND at the location): POST /pos/sales/{id}/returns with lines (sale line id, quantity, disposition), reason, note, idempotency key. Refunds the original payment method. A cash refund needs an open session for the returner at that location (409 POS_SESSION_REQUIRED) and reduces the expected cash. Errors: 409 ALREADY_RETURNED, 422 RETURN_QUANTITY_EXCEEDED, 409 RETURN_WINDOW_EXPIRED (older than pos.return_window_days; a user holding POS_DISCOUNT_APPROVE may override). RESTOCK puts units back as POS_RETURN; INSPECTION moves them to in_inspection (not sellable). GET /pos/sales/{id}/returns lists returns.

Inspection: GET /inventory/inspections (items in inspection, scoped) and POST /inventory/inspections/resolve (INVENTORY_ADJUST at the location): per variant quantity, outcome RESTOCK (becomes sellable, INSPECTION_RESTOCK) or WRITE_OFF (INSPECTION_WRITE_OFF, flagged by value like adjustments). Audited.

Permissions used: POS_SELL, POS_DISCOUNT, POS_DISCOUNT_APPROVE, POS_REFUND, POS_CASH_SESSION, POS_REPRINT. Update PERMISSIONS.md with what Phase 3 now enforces.

6. Catalog scan for POS

GET /catalog/lookup from Phase 2 stays the scan endpoint. Verify it returns price and resolved POS promotion for the cashier's location, with no cost data for a cashier. Fix only if it does not.

7. Demo data (DEMO_MODE=true, idempotent, through real services)

A new DemoDataContributor after the Phase 2 seeder:

Demo PIN 1234 set for the owner, manager-dm and manager-ut through the service (demo mode only; the policy bypass is explicit and commented).
Closed cash sessions on earlier days at each branch, and an open session for the demo cashier at Dhanmondi.
About 25 sales over the last 14 days across the three branches, with all four methods, a few promotions applied, a few manual discounts, two approved discounts, and a few returns (one restocked, one sent to inspection).
Seeding needs back-dated timestamps. Give the internal service method an explicit Instant parameter that only the seeder uses (or inject a settable Clock for the seeder). The public API never accepts a client timestamp.
Make sure demo stock covers the seeded sales. Running the seeder twice leaves identical row counts. Nothing is seeded when DEMO_MODE=false.
The shared test database is demo-seeded, so run the full suite and fix any test that assumed no sales or sessions.
8. Tests (all required)

Unit tests, no Spring: every class in section 3. Include: PaymentRules for every method, TrxID normalization, card-number rejection (13, 16, 19 digits, spaces, dashes, a Luhn-valid number in a TrxID field), DiscountAllocator tables where remainders matter (including a 3-way split of 100.00), DiscountLimitPolicy boundaries (exactly at the limit, one cent over, fixed at or above price), SaleCalculator with inclusive and exclusive tax and a promotion plus a manual discount, CashSessionReconciler with refunds, ReturnRules including full return after partial returns with no rounding drift, ReceiptNumber across the Dhaka year boundary (31 Dec 17:59:59 UTC versus 18:00:00 UTC), both renderers' output.

Integration tests on real PostgreSQL (use the shared fixtures; every test creates its own category, product, variant, barcode and stock; never create locations; scope queries by own SKU or receipt number):

Happy path: stock at the branch drops, warehouse stock is unchanged, one movement per line with the sale reference, receipt data complete, receipt number format right.
Server pricing: a tampered client price or total changes nothing; promotion for scope POS applies; an ONLINE-only promotion does not.
Session rules: no session means POS_SESSION_REQUIRED; a second open session is refused; closing computes expected figures from records; differences require a note; a closed session cannot be reopened or changed (trigger).
Discounts: cashier within limit passes; above limit without a token returns 422; with a valid token passes; a used token fails; an expired token fails (fixed Clock); a token for another location or cashier fails; wrong PIN is generic and audited; the 6th PIN attempt is locked; a cashier without POS_DISCOUNT cannot discount at all.
Payments: TrxID required for bKash and Nagad; the same TrxID twice is a 409 DUPLICATE_REFERENCE even across locations; card numbers are rejected for every method; cash tendered below total is refused; change is right.
Scope and tampering: the Dhanmondi cashier cannot sell at Mirpur or Uttara (404), cannot read another cashier's sale, cannot open another location's session; manager-dm sees Dhanmondi and Mirpur sales but not Uttara (404); body location ids and swapped sale ids are rejected as defined; warehouse staff cannot sell.
Cost privacy: a cashier's sale, receipt, history and lookup responses contain none of the cost fields (Jackson key check); the manager's can show them where the spec allows.
Returns: partial return, a second partial, full return, over-return refused, ALREADY_RETURNED, window expiry and override, a cash refund lowers expected cash, a bKash refund does not, RESTOCK makes stock sellable, INSPECTION does not and shows in the inspections list, then resolve moves it.
Reprint: first print not logged, each reprint logged and marked duplicate, count right, permission enforced.
Idempotency: same key twice gives one sale and one set of movements; a changed body with the same key gives 409.
Append-only: triggers refuse UPDATE and DELETE on all six tables; no repository exposes them.
Concurrency (ExecutorService and CountDownLatch, deterministic final-state assertions): two cashiers selling the last unit give one sale and one INSUFFICIENT_STOCK; one cashier double-submitting with the same key gives one sale; two returns racing for the last returnable unit give one success and one RETURN_QUANTITY_EXCEEDED; two sessions opened at once for one cashier give one winner; two sales with the same TrxID at once give one winner.
Invariant: after sales, returns and inspections on a fresh variant, sum(movement deltas) == on_hand + in_inspection movements accounted per your movement rules, and reserved <= on_hand holds. State the exact formula you test.
Audit rows exist for approvals, failed approvals, reprints, returns, below-cost sales, session close with a difference, PIN change and inspection resolve.
Demo seeding idempotent; nothing seeded when DEMO_MODE=false.
Phase 1 and 2 suites, including the default-deny enumeration, still pass. Every new endpoint is covered by default deny.

run-tests.bat passes from an empty merqo_test. Spotless clean. No assertions that cannot fail.

9. Error codes to add

POS_SESSION_REQUIRED, SESSION_ALREADY_OPEN, SESSION_NOT_OPEN, DISCOUNT_LIMIT_EXCEEDED, APPROVAL_INVALID, PIN_POLICY, PIN_LOCKED, PAYMENT_REFERENCE_REQUIRED, PAYMENT_INSUFFICIENT, CARD_NUMBER_REJECTED, DUPLICATE_REFERENCE, IDEMPOTENCY_CONFLICT, EMPTY_CART, ALREADY_RETURNED, RETURN_QUANTITY_EXCEEDED, RETURN_WINDOW_EXPIRED.

10. Documentation and deployment
JAVA_GUIDE.md: every new class mapped to its concept, a class diagram for POS, and new viva questions (for example "Why does the server recompute prices?", "How do you stop two cashiers selling the last unit?", "Why is a sale never edited?", "How does the largest-remainder allocation avoid a one-cent error?", "Why is ReceiptRenderer an interface?", "How is idempotency enforced?").
ARCHITECTURE.md, ASSUMPTIONS.md (receipt number format and Dhaka-year prefix, global TrxID uniqueness, one open session per cashier, approval PIN design, discount percent measured after promotion, return disposition choice, no void), PERMISSIONS.md, POS_CONTRACT.md updated to what you actually built, SETUP.md (a short "testing the POS without hardware" section: scan by typing, print to PDF), TROUBLESHOOTING.md.
The migration must run on Supabase (session-mode connection). Say in the report that you checked this.
Do not push.
11. Acceptance checklist
[ ] Step 0 done: base suite green, contract read, plan sent.
[ ] Sales are priced on the server, with a tamper test.
[ ] One payment per sale, with reference rules, card-number rejection and global TrxID uniqueness, all tested.
[ ] Cash sessions: one open per cashier, server-side reconciliation, and a closed session is immutable.
[ ] Discounts: promotion plus manual stacking, personal limits, PIN approval with single-use expiring tokens, throttling and audit.
[ ] Returns: partial and full, window, refund method, stock disposition, inspection resolve.
[ ] Receipts: data matches the frontend type, logged DUPLICATE reprints, both renderers.
[ ] Idempotent sales and returns.
[ ] Six append-only tables have triggers and no update or delete path.
[ ] Cost data never reaches users without cost permission.
[ ] Concurrency tests for the five scenarios pass.
[ ] Branch scope and tampering tests pass for every endpoint group.
[ ] Demo data seeded through services and idempotent.
[ ] POS_CONTRACT.md matches the real API, with changes listed.
[ ] run-tests.bat green from empty, Spotless clean, docs updated, migration checked for Supabase.
12. Hand-back

Use the exact 8-section report from Phase 0 section 14. Section 3 shows the final test count. Section 4 maps each acceptance item to test names. Section 6 lists every change to POS_CONTRACT.md. In section 8, ask the student anything you need. Do not start Phase 4.