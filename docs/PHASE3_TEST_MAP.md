# Phase 3 Test Map (Spec §8 & Phase 3B Verification)

Tracks every required unit, integration, hardening, concurrency, invariant, audit, demo, and default-deny test from `docs/PHASE3_SPEC.md` (§8).

## 1. Pure Java Unit Tests (No Spring)

| Requirement | Test class and method | Status |
| --- | --- | --- |
| `PaymentMethod` & `PaymentRules` for every method (CASH/BKASH/NAGAD/CARD) | `PaymentRulesTest.paymentMethodProperties`, `computesChange`, `rejectsInsufficientCash`, `normalizesTrxId`, `rejectsMissingOrMalformedTrxId`, `acceptsLast4OrApprovalCode` | covered |
| TrxID normalization (uppercase trim) | `PaymentRulesTest.normalizesTrxId` | covered |
| Card PAN rejection (13, 16, 19 digits, spaces, dashes, Luhn in all methods) | `PaymentRulesTest.rejectsCardNumbersAcrossAllMethods` | covered |
| `DiscountAllocator` largest-remainder & 3-way split of `100.00` + tie-break | `SalePricingAndDiscountDomainTest.threeWaySplitOfOneHundred`, `tieBreakFavorsLargerWeight`, `preservesSumAcrossUnequalWeights` | covered |
| `DiscountLimitPolicy` boundaries (at limit, +0.01, fixed >= price, permissions) | `SalePricingAndDiscountDomainTest.boundariesAroundLimit`, `rejectsFixedAtOrAbovePrice`, `enforcesPermissions` | covered |
| `SaleCalculator` inclusive/exclusive tax, stacked promo + manual + bill, `EMPTY_CART` | `SalePricingAndDiscountDomainTest.exclusiveTaxWithStackedDiscounts`, `inclusiveTaxAndBelowCost`, `rejectsEmptyCart` | covered |
| `ApprovalPinPolicy` strong/weak PINs & demo bypass flag | `ApprovalAndSessionDomainTest.acceptsStrongPins`, `rejectsWeakPins`, `demoBypass` | covered |
| `ApprovalTokenRules` unused, unexpired (`Clock`), cashier, location, ceiling | `ApprovalAndSessionDomainTest.tokenChecks` | covered |
| `CashSessionReconciler` with sales, cash & non-cash refunds, variances | `ApprovalAndSessionDomainTest.reconcilesWithRefunds` | covered |
| `ReturnRules` partial -> 2nd partial -> full with zero drift & invalid qty errors | `ReturnAndReceiptDomainTest.zeroRoundingDriftAcrossPartialReturns`, `rejectsInvalidReturnQuantities` | covered |
| `ReceiptNumber` across Dhaka year boundary (`31 Dec 17:59:59Z` vs `18:00:00Z`) | `ReturnAndReceiptDomainTest.dhakaYearBoundary`, `normalizesLookup` | covered |
| `ReceiptRenderer` (`JsonReceiptRenderer` & `PlainTextReceiptRenderer` 42/32 col + duplicate) | `ReturnAndReceiptDomainTest.jsonRendererMatchesContract`, `plainTextRendererWidthsAndDuplicate` | covered |

## 2. Integration Tests — Group 1: Sales & Idempotency

| Requirement | Test class and method | Status |
| --- | --- | --- |
| Happy path: branch stock drops, WH unchanged, 1 `SALE` movement, receipt format/data | `SaleIntegrationTest.happyPathReceiptAndReprint` | covered |
| Insufficient stock / reserved stock never sold, no partial sale | `SaleIntegrationTest.serverPricingPromotionsAndNoPartialSale` | covered |
| Tampered client price or total changes nothing | `SaleIntegrationTest.serverPricingPromotionsAndNoPartialSale` | covered |
| `POS`-scope promotion applies; `ONLINE`-only promotion does not | `SaleIntegrationTest.serverPricingPromotionsAndNoPartialSale` | covered |
| Warehouse staff cannot sell (`403 FORBIDDEN`) | `SaleHistoryAndScopeIntegrationTest.historyLookupScopeAndTampering` | covered |
| Sale with no open cash session returns `409 POS_SESSION_REQUIRED` | `SaleIntegrationTest.happyPathReceiptAndReprint` | covered |
| Same idempotency key twice gives one sale and one set of movements | `SaleIntegrationTest.discountsPaymentsAndIdempotency`, `PosGroup1SalesIntegrationTest.idempotentReplayWritesSingleSaleAndSingleMovement` | covered |
| Changed body with same idempotency key gives `409 IDEMPOTENCY_CONFLICT` | `SaleIntegrationTest.discountsPaymentsAndIdempotency`, `PosGroup1SalesIntegrationTest.idempotentReplayWritesSingleSaleAndSingleMovement` | covered |
| Empty cart (`lines: []`) on `POST /api/v1/pos/sales` returns `422 EMPTY_CART` | `PosGroup1SalesIntegrationTest.emptyCartReturnsEmptyCartError` | covered |

## 3. Integration Tests — Group 2: Payments

| Requirement | Test class and method | Status |
| --- | --- | --- |
| `BKASH` and `NAGAD` require TrxID (`422 PAYMENT_REFERENCE_REQUIRED`) | `PosGroup2PaymentsIntegrationTest.trxIdRequirementsDuplicatesAndCashTender` | covered |
| Same TrxID twice gives `409 DUPLICATE_REFERENCE` even across locations (`BKASH` & `NAGAD`) | `SaleIntegrationTest.discountsPaymentsAndIdempotency`, `PosGroup2PaymentsIntegrationTest.trxIdRequirementsDuplicatesAndCashTender` | covered |
| Card numbers (13, 16, 19 digits, spaces, dashes, Luhn) rejected via HTTP for EVERY method (`CASH`, `BKASH`, `NAGAD`, `CARD`) | `PosGroup2PaymentsIntegrationTest.rejectsCardNumbersAcrossAllMethodsViaHttp` | covered |
| Cash tendered below total refused (`422 PAYMENT_INSUFFICIENT`) via HTTP and change is accurate | `SaleIntegrationTest.happyPathReceiptAndReprint`, `PosGroup2PaymentsIntegrationTest.trxIdRequirementsDuplicatesAndCashTender` | covered |

## 4. Integration Tests — Group 3: Discounts & Approvals

| Requirement | Test class and method | Status |
| --- | --- | --- |
| Cashier within `max_discount_percent` limit passes via HTTP without token | `PosGroup3DiscountsIntegrationTest.discountLimitsPermissionsAndTokenIsolation` | covered |
| Discount above limit without token gives `422 DISCOUNT_LIMIT_EXCEEDED` | `SaleIntegrationTest.discountsPaymentsAndIdempotency`, `PosGroup3DiscountsIntegrationTest` | covered |
| Valid token passes, and reused token fails (`422 APPROVAL_INVALID`) | `SaleIntegrationTest.discountsPaymentsAndIdempotency` | covered |
| Expired token fails using fixed `Clock` | `ApprovalIntegrationTest.approvalTokenLifecycleAndLockout` | covered |
| Token issued for another cashier or another location fails on `POST /pos/sales` HTTP | `ApprovalIntegrationTest`, `PosGroup3DiscountsIntegrationTest.discountLimitsPermissionsAndTokenIsolation` | covered |
| Cashier without `POS_DISCOUNT` cannot discount at all (`403 FORBIDDEN`) via HTTP | `PosGroup3DiscountsIntegrationTest.discountLimitsPermissionsAndTokenIsolation` | covered |
| Fixed manual discount `>=` post-promotion line price rejected via HTTP | `PosGroup3DiscountsIntegrationTest.discountLimitsPermissionsAndTokenIsolation` | covered |
| Wrong PIN gives generic message + audit; 6th attempt is `423 PIN_LOCKED` | `ApprovalIntegrationTest.approvalTokenLifecycleAndLockout` | covered |

## 5. Integration Tests — Group 4: Scope, Tampering & Cost Privacy

| Requirement | Test class and method | Status |
| --- | --- | --- |
| Dhanmondi cashier cannot sell at Mirpur or Uttara (`404`) | `SaleHistoryAndScopeIntegrationTest.historyLookupScopeAndTampering`, `PosGroup4ScopeIntegrationTest.scopeTamperingAndCostPrivacy` | covered |
| Dhanmondi cashier cannot read another cashier's sale (`404`) | `SaleHistoryAndScopeIntegrationTest`, `PosGroup4ScopeIntegrationTest` | covered |
| Dhanmondi cashier cannot open another location's session (`404`) | `CashSessionIntegrationTest.scopeAndRoleRules`, `PosGroup4ScopeIntegrationTest` | covered |
| `manager-dm` sees Dhanmondi and Mirpur sales, gets `404` for Uttara | `SaleHistoryAndScopeIntegrationTest`, `PosGroup4ScopeIntegrationTest` | covered |
| Swapped sale IDs and tampered body `locationId`s rejected across endpoints | `PosGroup4ScopeIntegrationTest.scopeTamperingAndCostPrivacy` | covered |
| Cashier sale, receipt, history & lookup responses contain no cost keys (Jackson recursive check); manager sees where allowed | `PosHardeningIntegrationTest.costPrivacyAndAuditRows`, `PosGroup4ScopeIntegrationTest` | covered |

## 6. Integration Tests — Group 5: Returns & Reprints

| Requirement | Test class and method | Status |
| --- | --- | --- |
| Partial return, 2nd partial return, then full return with zero rounding drift | `ReturnAndInspectionIntegrationTest.partialFullReturnErrorsAndCashImpact` | covered |
| Over-return gives `RETURN_QUANTITY_EXCEEDED`; fully returned gives `ALREADY_RETURNED` | `ReturnAndInspectionIntegrationTest.partialFullReturnErrorsAndCashImpact` | covered |
| Return window expiry gives `RETURN_WINDOW_EXPIRED`; `POS_DISCOUNT_APPROVE` overrides | `ReturnAndInspectionIntegrationTest.returnWindowAndInspectionFlow` | covered |
| Cash refund lowers expected cash; bKash refund does not | `ReturnAndInspectionIntegrationTest` (`partialFull...`, `returnWindow...`) | covered |
| `RESTOCK` makes stock sellable; `INSPECTION` does not, appears in `/inventory/inspections`, resolve moves it | `ReturnAndInspectionIntegrationTest`, `PosGroup5ReturnsAndReprintsIntegrationTest.restockVsInspectionSellabilityAndResolution` | covered |
| First print not logged; each reprint logged and marked `isDuplicate: true`; `reprintCount` accurate | `SaleIntegrationTest.happyPathReceiptAndReprint` | covered |
| Reprint enforces `POS_REPRINT` (`403 FORBIDDEN` when absent/denied) | `SaleIntegrationTest.happyPathReceiptAndReprint` | covered |

## 7. Integration Tests — Group 6: Sessions, Hardening, Audit, Demo & Default Deny

| Requirement | Test class and method | Status |
| --- | --- | --- |
| Second open session refused (`409 SESSION_ALREADY_OPEN`); closed session immutable (trigger) | `CashSessionIntegrationTest.openSummaryCloseAndImmutabilityTrigger` | covered |
| Closing computes expected figures from recorded sales & refunds; non-zero difference requires note | `CashSessionIntegrationTest`, `PosGroup6SessionAuditDenyIntegrationTest.sessionReconciliationAcrossMethodsAndAuditRows` | covered |
| Append-only DB triggers refuse `UPDATE`/`DELETE` on all 6 tables + repositories expose no mutation/delete paths | `PosHardeningIntegrationTest.appendOnlyTriggersOnSixTables`, `PosGroup6SessionAuditDenyIntegrationTest.repositoriesAndDefaultDeny` | covered |
| Audit rows exist for approvals, failed approvals, reprints, returns, below-cost sales, session close difference, PIN change, inspection resolve | `PosHardeningIntegrationTest.costPrivacyAndAuditRows`, `ReturnAndInspectionIntegrationTest`, `PosGroup6SessionAuditDenyIntegrationTest.sessionReconciliationAcrossMethodsAndAuditRows` | covered |
| Concurrency races (`ExecutorService` + `CountDownLatch`): last unit, idempotent submit, last returnable unit, simultaneous session opens, simultaneous TrxID | `PosHardeningIntegrationTest.concurrencyRaces`, `CashSessionIntegrationTest.concurrentSessionOpensYieldOneWinner` | covered |
| Stock invariant across sales, returns & inspection resolutions with exact formula | `PosHardeningIntegrationTest.stockMovementAndBalanceInvariant` | covered |
| Demo data contributor idempotent & `DEMO_MODE=false` seeds nothing | `DemoPosIntegrationTest.demoPosSeedingIsIdempotent`, `DemoModeOffIntegrationTest` | covered |
| Phase 1 & 2 suites pass; every new Phase 3 endpoint covered by default deny (`401` unauthenticated) | `PublicEndpointPolicyTest`, `PosGroup6SessionAuditDenyIntegrationTest.repositoriesAndDefaultDeny` | covered |

## Summary

- Every requirement row in this map is **covered** (`0` missing).
- Final `scripts\run-tests.bat` run from clean `merqo_test`: **405 backend tests run (0 failures, 0 errors, 0 skipped)**, **Spotless clean across 288 Java files**, **74 frontend Vitest tests passed**, and **ESLint exited 0**.

