# PERMISSIONS.md

**The catalogue, the templates, the resolution rules, the delegation rules and how
the later prompts enforce them.**

Everything in this file exists in the code. The catalogue lives in
`com.merqo.access.Permission`, the templates are seeded by
`V3__seed_permission_templates.sql`, the rules live in
`com.merqo.access.PermissionResolver`, `com.merqo.access.DelegationPolicy` and
`com.merqo.access.AssignmentRules`.

---

## 1. The five ideas

| Idea | What it is | Where it lives |
| --- | --- | --- |
| **Permission** | One action from a closed catalogue. A fact about the system, never free text. | `Permission` (enum) |
| **Template** | A named set of permissions. The starting point for a role. | `permission_templates`, `template_permissions` |
| **Override** | One ALLOW or DENY of one permission for one account, optionally limited to one location. | `user_permission_overrides` |
| **Assignment** | A link between an account and a location, as MANAGER or STAFF. | `location_assignments` |
| **Delegation list** | The permissions one manager may hand out. Only the owner changes it. | `manager_delegations` |

A **user holds** a permission when the resolution rules say so. Nothing else
counts.

---

## 2. The catalogue

The four flags:

- **G** (global) — the permission is company wide. It needs no assignment.
- **L** (location) — the permission only applies at one location, and only where
  the account has an active assignment.
- **O** (owner only) — never grantable to anybody but the owner. A template or
  an override that lists it is rejected with `OWNER_ONLY_PERMISSION`.
- **D** (delegable) — a manager may hand it out, but only if the owner put it on
  that manager's delegation list.

### Users and access

| Permission | Scope | O | D | Meaning |
| --- | --- | :-: | :-: | --- |
| `USER_VIEW` | L | | | View users and accounts |
| `USER_CREATE` | L | | | Create user accounts |
| `USER_EDIT` | L | | | Edit user profiles |
| `USER_DEACTIVATE` | L | | | Deactivate and reactivate accounts |
| `USER_ACCESS_MANAGE` | L | | | Assign templates, overrides and locations |
| `MANAGER_MANAGE` | G | ✓ | | Create, edit and appoint manager accounts |
| `LOCATION_VIEW` | L | | | View locations |
| `LOCATION_MANAGE` | G | ✓ | | Create, edit and deactivate locations |
| `TEMPLATE_MANAGE` | G | ✓ | | Create and edit permission templates |
| `SETTINGS_VIEW` | G | | | View business settings |
| `SETTINGS_MANAGE` | G | ✓ | | Change business settings |
| `AUDIT_VIEW` | L | | | View the audit log |

### Catalog

| Permission | Scope | O | D | Meaning |
| --- | --- | :-: | :-: | --- |
| `PRODUCT_VIEW` | G | | ✓ | View products |
| `PRODUCT_CREATE` | G | | | Create products |
| `PRODUCT_EDIT` | G | | | Edit products |
| `PRICE_EDIT` | G | | | Change selling prices |
| `BARCODE_EDIT` | G | | | Change barcodes |
| `DISCOUNT_MANAGE` | L | | | Manage discounts |

### Inventory

| Permission | Scope | O | D | Meaning |
| --- | --- | :-: | :-: | --- |
| `INVENTORY_VIEW` | L | | ✓ | View stock |
| `INVENTORY_ADJUST` | L | | | Adjust stock |
| `STOCK_COUNT` | L | | ✓ | Count stock |
| `TRANSFER_VIEW` | L | | ✓ | View stock transfers |
| `TRANSFER_REQUEST` | L | | ✓ | Request a stock transfer |
| `TRANSFER_APPROVE` | L | | | Approve a stock transfer |
| `TRANSFER_DISPATCH` | L | | | Dispatch a stock transfer |
| `TRANSFER_RECEIVE` | L | | ✓ | Receive a stock transfer |

### Point of sale

| Permission | Scope | O | D | Meaning |
| --- | --- | :-: | :-: | --- |
| `POS_SELL` | L | | ✓ | Sell at the counter |
| `POS_DISCOUNT` | L | | ✓ | Apply a discount |
| `POS_DISCOUNT_APPROVE` | L | | | Approve a large discount |
| `POS_REFUND` | L | | | Refund a sale |
| `POS_CASH_SESSION` | L | | ✓ | Open and close a cash session |
| `POS_REPRINT` | L | | ✓ | Reprint a receipt |

### Orders

| Permission | Scope | O | D | Meaning |
| --- | --- | :-: | :-: | --- |
| `ORDER_VIEW` | L | | ✓ | View orders |
| `ORDER_CALL` | L | | ✓ | Confirm an order by phone |
| `ORDER_CONFIRM` | L | | ✓ | Confirm an order |
| `ORDER_DECLINE` | L | | ✓ | Decline an order |
| `ORDER_CANCEL` | L | | | Cancel an order |
| `ORDER_CREATE_MANUAL` | L | | ✓ | Create an order by hand |

### Shipping

| Permission | Scope | O | D | Meaning |
| --- | --- | :-: | :-: | --- |
| `SHIPMENT_VIEW` | L | | ✓ | View shipments |
| `SHIPMENT_BOOK` | L | | ✓ | Book a shipment |
| `SHIPMENT_UPDATE` | L | | | Update a shipment |

### Payments

| Permission | Scope | O | D | Meaning |
| --- | --- | :-: | :-: | --- |
| `PAYMENT_VIEW` | L | | | View payments |
| `PAYMENT_RECORD` | L | | ✓ | Record a payment |
| `PAYMENT_VERIFY` | L | | | Verify a payment |
| `REFUND_APPROVE` | L | | | Approve a refund |
| `COD_SETTLEMENT_MANAGE` | G | | | Manage cash on delivery settlement |

### Suppliers

| Permission | Scope | O | D | Meaning |
| --- | --- | :-: | :-: | --- |
| `SUPPLIER_VIEW` | G | | | View suppliers |
| `SUPPLIER_MANAGE` | G | | | Manage suppliers |
| `PO_CREATE` | G | | | Create a purchase order |
| `PO_APPROVE` | G | | | Approve a purchase order |
| `PO_RECEIVE` | L | | | Receive a purchase order |
| `SUPPLIER_PAYMENT_RECORD` | G | | | Record a supplier payment |

### Finance

| Permission | Scope | O | D | Meaning |
| --- | --- | :-: | :-: | --- |
| `COST_VIEW` | L | | | View cost prices |
| `COST_EDIT_VARIABLE` | L | | | Change variable cost prices |
| `COST_EDIT_FIXED` | G | ✓ | | Change fixed cost prices |
| `FINANCE_REPORT_VIEW` | L | | | View finance reports |
| `DASHBOARD_VIEW` | L | | | View the branch dashboard |
| `DASHBOARD_COMPANY_VIEW` | G | ✓ | | View the company dashboard |

### AI assistant

| Permission | Scope | O | D | Meaning |
| --- | --- | :-: | :-: | --- |
| `AI_USE` | G | | | Use the AI assistant |

The catalogue is written out in full now, so the templates of the later prompts
stay stable. The later prompts enforce the permissions that belong to them.

---

## 3. The seed templates

Seeded by `V3__seed_permission_templates.sql`. The owner may edit any template,
create new ones and deactivate the ones nobody uses. Editing a template changes
what its holders get on their **next** request, because nothing is cached.

| Template | Permissions |
| --- | --- |
| **CASHIER** | `PRODUCT_VIEW`, `INVENTORY_VIEW`, `POS_SELL`, `POS_DISCOUNT`, `POS_CASH_SESSION`, `POS_REPRINT` |
| **ONLINE_ORDER_STAFF** | `PRODUCT_VIEW`, `INVENTORY_VIEW`, all `ORDER_*`, all `SHIPMENT_*`, `PAYMENT_VIEW`, `PAYMENT_RECORD`, `PAYMENT_VERIFY` |
| **INVENTORY_STAFF** | `PRODUCT_VIEW`, `PRODUCT_CREATE`, `PRODUCT_EDIT`, `BARCODE_EDIT`, `INVENTORY_VIEW`, `INVENTORY_ADJUST`, `STOCK_COUNT`, `TRANSFER_VIEW`, `TRANSFER_REQUEST`, `TRANSFER_RECEIVE` |
| **WAREHOUSE_STAFF** | `PRODUCT_VIEW`, `INVENTORY_VIEW`, `INVENTORY_ADJUST`, `STOCK_COUNT`, all `TRANSFER_*`, `PO_RECEIVE`, `SUPPLIER_VIEW` |
| **FINANCE_STAFF** | `PAYMENT_VIEW`, `PAYMENT_VERIFY`, `COD_SETTLEMENT_MANAGE`, `SUPPLIER_VIEW`, `SUPPLIER_PAYMENT_RECORD`, `COST_VIEW`, `COST_EDIT_VARIABLE`, `FINANCE_REPORT_VIEW` |
| **MANAGER** | `PRODUCT_VIEW`, `DISCOUNT_MANAGE`, `INVENTORY_VIEW`, `TRANSFER_VIEW`, `TRANSFER_REQUEST`, `TRANSFER_RECEIVE`, all `POS_*`, `USER_VIEW`, `USER_CREATE`, `USER_EDIT`, `USER_DEACTIVATE`, `USER_ACCESS_MANAGE`, `LOCATION_VIEW`, `AUDIT_VIEW`, `PAYMENT_VIEW`, `COST_VIEW`, `COST_EDIT_VARIABLE`, `FINANCE_REPORT_VIEW`, `DASHBOARD_VIEW`, `AI_USE` |
| **CUSTOM** | empty, for the owner to fill |

**The MANAGER template has no warehouse stock access.** Warehouse access follows
the assignment rule: a manager who is assigned to branches only cannot see the
warehouse, whatever the template says.

### Which template grants which Phase 2 permission

This is the table the instructor asks about. Every Phase 2 permission of the
catalogue, the inventory and the transfers, and the templates that carry it:

| Permission | Module | Scope | CASHIER | ONLINE_ORDER_STAFF | INVENTORY_STAFF | WAREHOUSE_STAFF | FINANCE_STAFF | MANAGER | CUSTOM |
| --- | --- | :-: | :-: | :-: | :-: | :-: | :-: | :-: | :-: |
| `PRODUCT_VIEW` | Catalog | G | ✓ | ✓ | ✓ | ✓ | | ✓ | |
| `PRODUCT_CREATE` | Catalog | G | | | ✓ | | | | |
| `PRODUCT_EDIT` | Catalog | G | | | ✓ | | | | |
| `PRICE_EDIT` | Catalog | G | | | | | | | |
| `BARCODE_EDIT` | Catalog | G | | | ✓ | | | | |
| `DISCOUNT_MANAGE` | Catalog | L | | | | | | ✓ | |
| `INVENTORY_VIEW` | Inventory | L | ✓ | ✓ | ✓ | ✓ | | ✓ | |
| `INVENTORY_ADJUST` | Inventory | L | | | ✓ | ✓ | | | |
| `STOCK_COUNT` | Inventory | L | | | ✓ | ✓ | | | |
| `TRANSFER_VIEW` | Inventory | L | | | ✓ | ✓ | | ✓ | |
| `TRANSFER_REQUEST` | Inventory | L | | | ✓ | ✓ | | ✓ | |
| `TRANSFER_APPROVE` | Inventory | L | | | | ✓ | | | |
| `TRANSFER_DISPATCH` | Inventory | L | | | | ✓ | | | |
| `TRANSFER_RECEIVE` | Inventory | L | | | ✓ | ✓ | | ✓ | |
| `COST_VIEW` | Finance | L | | | | | ✓ | ✓ | |

Three things fall out of this table:

- **The warehouse does the transfers.** Only `WAREHOUSE_STAFF` holds
  `TRANSFER_APPROVE` and `TRANSFER_DISPATCH`, because the warehouse is the
  place the goods come from.
- **The branch receives.** `INVENTORY_STAFF` and `MANAGER` both hold
  `TRANSFER_RECEIVE`, because a branch receives the goods it asked for.
- **Nobody but the owner changes a price.** `PRICE_EDIT` is in no template at
  all, so it can only come from an override the owner writes by hand. The
  same is true of `PRODUCT_CREATE`, `PRODUCT_EDIT` and `BARCODE_EDIT` outside
  the inventory staff.

**`COST_VIEW` gates the cost fields.** It is a Finance permission, but it
decides what the catalogue, the inventory and the price preview show. A
caller without `COST_VIEW` and without `PRICE_EDIT` gets **no cost field at
all**, not a cost of zero. See
[ASSUMPTIONS.md](ASSUMPTIONS.md) A30.

---

## 4. The resolution rules

Implemented in `PermissionResolver.resolve(ResolutionInput)`. The rules are
applied in this order:

1. **The owner gets everything, everywhere.** Nothing is stored for the owner,
   and nobody else may change the owner account. A deny override cannot take a
   permission away from the owner.
2. **Start with the union of the templates** the account holds.
3. **Add every ALLOW override.**
4. **Remove every DENY override.** A deny always beats an allow and always
   beats a template. A deny that names one location only affects that location.
5. **Owner-only permissions are never effective for a non-owner**, whatever a
   template or an override says.
6. **A location permission needs an assignment.** It is effective at a location
   only where the account has an active assignment. A global permission needs
   none.
7. **A deactivated account has no effective permissions at all.**

The result is an `EffectivePermissions` record:

```
can(permission)                -> global: everywhere or nowhere
can(permission, locationId)    -> the location answer, with the deny first
locationsFor(permission)        -> the location ids, for every list query
```

`locationsFor` is the method every list query uses to scope its search. It
returns an empty set when the caller may not do it anywhere, so the query
returns nothing instead of everything. Scoping is never done by filtering rows
in memory.

### Why the owner is a special case

The owner has no stored permissions at all. `EffectivePermissions.forOwner()`
builds the answer from the enum itself. That makes two things true at once:
the owner can never lose a permission by accident, and the owner rows in the
permission tables can never contradict the code.

### Why DENY beats ALLOW

A deny is a deliberate act ("this person may not do this here"), so it has to
win over a general rule. The same is true of a template: a template is a
starting point, a deny is an exception. The rule lives in one place, the
resolver, so no service can answer the question differently.

### Unknown permission codes

The database stores the codes as text, and the codes are validated against the
Java enum. A code that this build does not know is logged once and ignored. The
application never crashes because a row holds a permission from a newer build.

---

## 5. The delegation rules

Implemented in `DelegationPolicy.check(Request)`.

A manager may grant permission **P** at location **L** to a staff member only
when **all** of these hold:

1. **P is not owner only.**
2. **P is flagged delegable.**
3. **P is on the manager's own delegation list.** Only the owner changes that
   list.
4. **L is inside the manager's own scope.**
5. **The manager holds P at L themselves.** A manager can never give away more
   than they have.
6. **The target is not the owner.**
7. **The target is not another manager.**
8. **The target is not the manager themselves.**

A refusal always carries a reason, so the message names the rule that was
broken: `OWNER_ONLY_PERMISSION`, `NOT_DELEGABLE`, `NOT_IN_DELEGATION_LIST`,
`LOCATION_OUTSIDE_MANAGER_SCOPE`, `MANAGER_DOES_NOT_HOLD_PERMISSION`,
`TARGET_IS_OWNER`, `TARGET_IS_MANAGER`, `TARGET_IS_THE_MANAGER`.

### The delegation list of a new manager

When the owner creates a manager account, the list starts with the delegable
permissions found in `CASHIER`, `ONLINE_ORDER_STAFF` and `INVENTORY_STAFF`
(`DelegationDefaults`). The owner edits it afterwards.

### What a manager can never do

- Grant anything to themselves, to another manager or to the owner.
- Touch an account outside their own scope.
- Assign a location outside their own scope.
- Grant a permission that is not delegable, not on their list, or owner only.
- Change templates, settings, locations or manager appointments.

### Shared staff

A staff member who works in a branch the manager does not manage is a
**shared** account. The manager may only end that staff member's assignment at
their own location. They may not deactivate the account and may not edit its
permissions.

---

## 6. Managers and assignments

`AssignmentRules` answers which combinations may exist:

| Rule | Answer |
| --- | --- |
| A staff account as a manager | refused (`ACCOUNT_TYPE_CANNOT_BE_MANAGER`) |
| A manager at a warehouse | refused (`WAREHOUSE_ACCEPTS_STAFF_ONLY`) |
| A branch with an active manager | refused (`PRIMARY_MANAGER_EXISTS`) |
| The same manager appointed again | refused (`ALREADY_THE_ACTIVE_MANAGER`) |
| The same manager again with `replaceExisting` | refused |

**The one-manager-per-branch rule is enforced twice.** In Java, so the caller
gets a clear message instead of a database error. In the database, by the
partial unique index `ux_location_assignments_active_manager`, which is the
guard that holds when two requests arrive at the same moment. The concurrency
test proves that two simultaneous appointments give exactly one winner.

**Replacing a manager** ends the old assignment and creates the new one in one
transaction. The old row stays with an `ended_at`, so the history is kept. The
replacement is audited as `MANAGER_REPLACED`.

**Account type is fixed.** A staff account cannot be turned into a manager in
this version. The rule is in `AccountType`, and the choice is written down in
[ASSUMPTIONS.md](ASSUMPTIONS.md).

---

## 7. The error rules of the access model

| Situation | Answer | Why |
| --- | --- | --- |
| The account lacks the permission at a location it can otherwise see | `403 FORBIDDEN` | The caller already knows the record is there. |
| The target location or record is outside the caller's scope | `404 NOT_FOUND` | A `403` would tell the caller that the record exists. |
| The account is not signed in | `401 UNAUTHENTICATED` | |
| The account must change its password first | `403 PASSWORD_CHANGE_REQUIRED` | |
| A location still has active assignments | `409 LOCATION_IN_USE` | |

A branch id sent by the client is never trusted. The backend always resolves
the scope from the server-side session and compares.

---

## 8. How a later prompt enforces permissions

Every service method follows the same four lines:

```java
AccessContext context = currentUserService.requireAccessContext();
context.requireGlobal(Permission.SOMETHING);        // or context.require(P, locationId)
```

and every list query scopes itself with the location ids:

```java
context.visibleLocationIds(Permission.SOMETHING)
```

The repositories take the location id set as a parameter, so the filtering
happens in SQL and never in memory.

### The scope test helper

`backend/src/test/java/com/merqo/access/ScopeTestSupport.java` is the base class
of every integration test that needs a signed in user. It gives the later
prompts two short helpers:

```java
extends ScopeTestSupport

Cookie session = signIn("manager-dm");            // one click, no password

mockMvc.perform(get("/api/v1/locations/" + otherBranchId).cookie(session))
        .andExpect(status().isNotFound());          // 404: outside the scope

mockMvc.perform(get("/api/v1/settings").cookie(session))
        .andExpect(status().isForbidden());         // 403: no permission
```

`assertOutOfScope(result)` and `assertForbidden(result)` wrap the two rules, so
the same rule is proved the same way in every prompt.

### The rule for a new endpoint group

1. The endpoint is behind the authentication unless it is on the explicit public
   allow list in `SecurityConfiguration.PUBLIC_ENDPOINTS`. A test walks every
   mapped endpoint and proves it.
2. The service asks the `AccessContext`, never the raw permission lists.
3. The repository receives the location ids and filters in SQL.
4. The integration test signs in as a manager of one branch and asks for the
   other branch, with the id in the URL **and** in the body.

---

## 9. The public allow list

Only these endpoints are open to a caller who is not signed in:

| Endpoint | Why |
| --- | --- |
| `GET /api/v1/health` | The health panel of the home page. |
| `GET /api/v1/health/ping` | The free uptime monitor. |
| `GET /api/v1/auth/status` | The login page needs to know whether the system still needs an owner. |
| `POST /api/v1/auth/login` | The sign in itself. |
| `GET /api/v1/demo/accounts` | The demo panel, in demo mode only. |
| `POST /api/v1/demo/login` | The one click demo sign in, in demo mode only. |

`PublicEndpointPolicyTest` walks every endpoint the handler mapping knows and
proves that no business endpoint is on the list and that every name on the list
really maps to an endpoint that answers. A new endpoint that nobody thought
about is refused by the security chain, and the test fails the moment somebody
adds an endpoint and forgets to think about it.

---

## 10. The demo accounts

Seeded by `DemoAccountsContributor`, only in demo mode. They carry a password
hash nobody knows, so they can only sign in through the one click login.

| Key | Account | Setup |
| --- | --- | --- |
| `owner` | Demo Owner | Owner of everything. |
| `manager-dm` | Demo Manager (two branches) | Manager of Dhanmondi and Mirpur, with a delegation list. |
| `manager-ut` | Demo Manager Uttara | Manager of Uttara only. Proves the isolation. |
| `cashier` | Demo Cashier | Cashier at Dhanmondi, `max_discount_percent` = 5. |
| `orders` | Demo Online Orders | Online order staff at the warehouse. |
| `warehouse` | Demo Warehouse | Warehouse staff at the warehouse. |

The seed is **idempotent**: every account is looked up by its stable demo key,
so running the seeder twice leaves the same rows. With `DEMO_MODE=false` the
demo endpoints answer `404`, and the login endpoint refuses a demo account even
if one is still in the database.

The demo locations are seeded by `DemoLocationsContributor`:

| Code | Name | Kind | Fulfils the online orders |
| --- | --- | --- | --- |
| `WH` | Main Warehouse | warehouse | ✓ |
| `DM` | Dhanmondi | branch | |
| `MP` | Mirpur | branch | |
| `UT` | Uttara | branch | |

---

## 11. The Phase 2 scope tests

The demo accounts are the fixtures of the scope tests. The important one is
`manager-dm`: the manager of Dhanmondi and Mirpur, with the `MANAGER`
template and a delegation list, and **no assignment at the warehouse or at
Uttara**.

### What the Dhanmondi manager must NOT be able to do

| Attempt | Answer | Why |
| --- | --- | --- |
| Read the stock of Uttara | `404 NOT_FOUND` | Uttara is outside the scope. The manager must not even learn that the branch exists. |
| Read the stock of the warehouse | `404 NOT_FOUND`, or an empty list | The manager holds `INVENTORY_VIEW` but only where they are assigned. The warehouse stock is invisible. |
| Request a transfer into Uttara | `404 NOT_FOUND` | `TransferService.request` checks `TRANSFER_REQUEST` at the destination, and Uttara is outside the scope. |
| Read a transfer that does not touch DM or MP | `404 NOT_FOUND` | `TransferService.get` answers `404` when the transfer touches neither the source nor the destination the caller may see. |
| Act on such a transfer (approve, dispatch, receive) | `404 NOT_FOUND` | The transfer is fetched with the same check, so the step is never reached. |
| Create a promotion for every location | `403 FORBIDDEN` | Only the owner may create an all-locations promotion. It reaches the online fulfilment warehouse as well. |
| Create an ONLINE promotion | `403 FORBIDDEN` | Only the owner may create one for the online channel. |
| Create a POS promotion for another branch | `403 FORBIDDEN` | The manager holds `DISCOUNT_MANAGE` at DM and MP only. The promotion is refused because the caller does not hold it at that location. |

The last row is the one that is easy to get wrong. A branch manager **may**
create a POS promotion for their own branches, and the service checks the
permission at every location the promotion reaches.

### The two answers, and why they are different

| Situation | Answer | Why |
| --- | --- | --- |
| The record is **outside** the scope of the caller | `404 NOT_FOUND` | A `403` would tell the caller that the record exists. The caller may not learn anything about the Uttara branch or the warehouse. |
| The record is **inside** the scope but the caller lacks the permission | `403 FORBIDDEN` | The caller already knows the record is there, so there is nothing to hide. The answer names the permission that is missing. |

Both rules live in one place, `AccessContext`: `requireInScope` throws the
`404`, and `require` and `requireGlobal` throw the `403`.

### The test helper

`backend/src/test/java/com/merqo/access/ScopeTestSupport.java` is the base
class of every integration test that needs a signed in user:

```java
extends ScopeTestSupport

Cookie session = signIn("manager-dm");            // one click, no password

mockMvc.perform(get("/api/v1/inventory/stock?locationId=" + uttaraId)
                .cookie(session))
        .andExpect(status().isNotFound());         // 404: outside the scope

mockMvc.perform(get("/api/v1/settings").cookie(session))
        .andExpect(status().isForbidden());        // 403: no permission
```

`assertOutOfScope(result)` and `assertForbidden(result)` wrap the two rules,
so the same rule is proved the same way in every phase.

### The cost privacy test

The cost rule is proved the same way. Signed in as `cashier`, the answers of
the variants, of the barcode lookup, of the stock and of the price preview
carry **no** cost, margin or value field at all. Signed in as `manager-dm`,
the same answers carry the cost, because the `MANAGER` template holds
`COST_VIEW`.

### The promotion rules of a branch manager

`PromotionService.requirePermissionEverywhere` is the place where the rules
live:

```java
if (context.can(Permission.DISCOUNT_MANAGE)) {
    return;                                    // the owner
}
if (request.allLocations() || request.scope() != Scope.POS) {
    throw new ForbiddenException(...);         // every location or ONLINE
}
for (UUID locationId : locations) {
    if (!context.can(Permission.DISCOUNT_MANAGE, locationId)) {
        throw new ForbiddenException(...);      // another branch
    }
}
```

So a branch manager may create a POS promotion for their own branches only.

---

## 12. Phase 3 POS, approval & inspection enforcement

### POS, Approval & Inspection Permissions

| Action / Endpoint | Permission required | Scope & extra rules |
| --- | --- | --- |
| `POST /api/v1/pos/sessions`, `POST /api/v1/pos/sessions/{id}/close` | `POS_CASH_SESSION` | Branch assignment required (`404` outside scope). At most one `OPEN` session per cashier. |
| `GET /api/v1/pos/sessions/current`, `GET /api/v1/pos/sessions/{id}/summary` | `POS_CASH_SESSION` or `POS_SELL` (or owner) | Managers/owners may inspect sessions at their assigned branches; cashiers only their own. |
| `PUT /api/v1/auth/approval-pin` | `OWNER` or `POS_DISCOUNT_APPROVE` at any assigned branch | Sets/updates own BCrypt-hashed 4–8 digit approval PIN after verifying current password. |
| `POST /api/v1/pos/approvals` | Cashier assigned at `locationId` + PIN of approver holding `POS_DISCOUNT_APPROVE` at `locationId` | Wrong PIN locks after 5 failures in 15 min (`423 APPROVAL_LOCKED`). Token is single-use, 5-min TTL, bound to `(cashier, location)`. |
| `POST /api/v1/pos/sales` | `POS_SELL` (+ `POS_DISCOUNT` when any manual discount is sent) | Open cash session required (`409 POS_SESSION_REQUIRED`). Over-limit percent discount requires `approvalToken` (`422 DISCOUNT_LIMIT_EXCEEDED`). |
| `GET /api/v1/pos/sales`, `GET /api/v1/pos/sales/{id}`, `GET /api/v1/pos/sales/lookup`, `GET /api/v1/pos/sales/{id}/receipt` | `POS_SELL` or `POS_REFUND` | Owner-aware history visibility: cashiers without `DISCOUNT_MANAGE`, `DASHBOARD_VIEW`, `FINANCE_REPORT_VIEW`, or `AUDIT_VIEW` at that branch see only their own sales; managers see all branch sales; other branches return `404`. |
| `POST /api/v1/pos/sales/{id}/reprint` | `POS_REPRINT` at sale's `locationId` | Logs append-only `receipt_reprints` row and `RECEIPT_REPRINTED` audit event. |
| `POST /api/v1/pos/sales/{id}/returns`, `GET /api/v1/pos/sales/{id}/returns` | `POS_REFUND` at sale's `locationId` | Expired `pos.return_window_days` may only be overridden when caller holds `POS_DISCOUNT_APPROVE` at that branch. |
| `GET /api/v1/inventory/inspections` | `INVENTORY_VIEW` | Filtered to `context.visibleLocationIds(INVENTORY_VIEW)`. |
| `POST /api/v1/inventory/inspections/resolve` | `INVENTORY_ADJUST` at `locationId` | Moves inspected stock back to `on_hand` (`RESTOCK`) or removes it (`WRITE_OFF`). |

### Cost privacy across Phase 3

Every Phase 3 response (`POST /pos/sales`, `GET /pos/sales`, `GET /pos/sales/{id}`, `GET /pos/sales/lookup`, `GET /pos/sales/{id}/receipt`, `POST /pos/sales/{id}/reprint`, `POST /pos/sales/{id}/returns`, `POST /inventory/inspections/resolve`) checks `COST_VIEW` (or `COST_VIEW` / `PRICE_EDIT` on inspection resolve) at the sale/location before adding `unitCost`, `belowCost`, `belowCostWarnings`, or `valueAtCost`. Cashiers without `COST_VIEW` never receive those JSON keys at all (verified recursively with Jackson in `PosHardeningIntegrationTest`).

