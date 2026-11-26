# ASSUMPTIONS.md

**Every decision taken where the brief left room, and why.**

The rule from the brief: choose the simplest reasonable option, record it here
and report it. If two requirements conflicted, the work stopped and the
student was asked.

---

## A1 - Spring Boot 4.1.3 instead of 3.x

**Decision:** the backend uses Spring Boot **4.1.3** (Spring Framework 7.0.11).

**Reason:** the brief says "latest stable Spring Boot that supports Java 21".
Spring Boot 4.2.0 is the newest release, but 4.1.3 is the newest patch of the
current stable line and is what `springdoc-openapi` 3.2.0 is built and tested
against. Spring Boot 4 requires Java 17 or newer and supports up to Java 27, so
Java 21 is fully supported.

**Effect:** the starter names changed in Spring Boot 4. The pom uses
`spring-boot-starter-webmvc` (the new name for the MVC starter),
`spring-boot-starter-jackson` for JSON, and the test starters
`spring-boot-webmvc-test`, `spring-boot-data-jpa-test` and
`spring-boot-security-test`.

---

## A2 - The frontend talks to its own origin through a Cloudflare Pages Function

**Decision:** the browser calls `/api/v1/...` on the Pages domain. A Pages
Function forwards the request to the Render backend.

**Reason:** the frontend and the backend are on different hosts. A direct call
would make the session cookie a third party cookie, which Chrome, Edge, Firefox
and Safari block by default. The brief requires login to work in all four
browsers, including with third party cookies blocked.

**Alternative that was not taken:** calling the backend directly with a bearer
token in `localStorage`. The brief forbids tokens in `localStorage`, and it is
also weaker: `localStorage` is readable by any script on the page.

**Fallback if it cannot work:** serve the frontend from Render as a static site
and put the backend behind the same origin there. Documented in
[docs/TROUBLESHOOTING.md](TROUBLESHOOTING.md).

---

## A3 - Sessions are stored in PostgreSQL

**Decision:** the session lives in the `SPRING_SESSION` tables, created by
Flyway.

**Reason:** a free Render instance restarts often. With an in-memory session,
every restart would log the user out and the instructor would notice. With a
database session, a login survives a restart and a sleep.

**Detail:** the tables are created by the Flyway migration, not by Spring
Session's own schema initialiser, because the brief says Flyway owns the
schema. The Spring Session property `spring.session.jdbc.initialize-schema` is
therefore left at its default (`embedded`), which means it does nothing for
PostgreSQL, and the tables come from `V1__baseline_schema.sql` instead.

---

## A4 - The correlation id filter is registered explicitly

**Decision:** `CorrelationIdFilter` is a plain class and a separate
`CorrelationIdFilterConfiguration` registers it as a servlet filter.

**Reason:** as a Spring `@Component` the filter would be registered twice, once
by Spring Boot and once by the servlet container, and it would run at an
unpredictable point in the filter chain. The explicit registration makes the
order and the URL pattern obvious.

---

## A5 - The health endpoint reports the database separately

**Decision:** `GET /api/v1/health` returns the application status, the service
name, the UTC time, the Asia/Dhaka business time and a separate database
object with its own status.

**Reason:** the dashboard honesty rule says sales, profit and cash are always
separate labelled numbers. The same rule is applied to health: "the application
is up" and "the database is up" are two different facts and are never merged
into one green light.

---

## A6 - `CORS` is kept even though the proxy makes it unnecessary in production

**Decision:** the CORS filter stays in every profile.

**Reason:** it costs nothing, it keeps direct API access usable for debugging
with `curl` or the browser, and it is driven entirely by the `CORS_ORIGINS`
environment variable, so it can be emptied in production if desired.

---

## A7 - The money rounding happens when the amount is created

**Decision:** `Money.of(33.335)` produces `33.34`. The unit price is rounded
before it is multiplied by a quantity.

**Reason:** the brief says one shared, tested money utility with scale 2 and
HALF_UP. A price on the shelf is a two decimal amount. Rounding it once when it
enters the system means every later calculation is exact and there is only one
place where a rounding decision can be made.

**Consequence:** `3 x 33.335` gives `100.02`, not `100.01`. This is the correct
behaviour for a shop and it is proven by the test
`MoneyTest.roundsUnitPriceBeforeMultiplying`.

---

## A8 - The error format is RFC 7807 with extra fields

**Decision:** `ProblemDetail` follows RFC 7807 and adds `errorCode`,
`correlationId`, `timestamp`, `fieldErrors` and `errors`.

**Reason:** the brief asks for RFC 7807 problem details **with stable error
codes**. RFC 7807 itself has no field for a machine readable code, so
`errorCode` is added next to the standard fields. The standard fields keep the
format recognisable for other tools.

---

## A9 - The frontend uses Vitest with jsdom

**Decision:** the frontend tests run in jsdom with Vitest and Testing Library.

**Reason:** the brief asks for Vitest and React Testing Library. jsdom is the
standard environment for these tests and does not need a browser to be
installed.

---

## A10 - The frontend build output goes to `frontend/dist`

**Decision:** the Vite output directory is `dist` inside `frontend/`.

**Reason:** Cloudflare Pages needs the build output directory relative to the
project root. With the root directory set to `frontend`, the output directory
is simply `dist`.

---

## A11 - `render.yaml` declares only the backend, not the database

**Decision:** the Blueprint contains one web service and no database.

**Reason:** Render's free Postgres expires after 30 days, so the project uses
Supabase. A Blueprint that declared a database would create exactly the wrong
kind of database.

**Note:** the Blueprint still requires the database values to be entered in the
dashboard (`sync: false`), because a secret must never be in a file that goes
into git.

---

## A12 - The DEMO_MODE default differs per profile

**Decision:** `application.yml` has `demo-mode: false`, `application-dev.yml`
has `true` and `application-test.yml` has `true`.

**Reason:** the deployed instructor instance sets `DEMO_MODE=true` explicitly as
an environment variable, so the default only matters for a local run. The
default is `false` in the base configuration because a real shop must never
accidentally start in demo mode.

---

## A13 - The Dockerfile is at the repository root, not in `backend/`

**Decision:** the Dockerfile is at the root and copies `backend/` into the
build stage.

**Reason:** Render needs one Dockerfile with one build context. The root
context lets the image build the Maven project and still keep the repository
layout with `backend/` and `frontend/` side by side.

---

## A14 - Virtual threads are enabled

**Decision:** `spring.threads.virtual.enabled=true`.

**Reason:** request handling spends most of its time waiting for the
database. Virtual threads make that cheap, which matters on a small instance
with 512 MB of RAM.

---

## A15 - The demo data is written by the backend, not by a script

**Decision:** the demo locations, the demo accounts and the demo permission
templates are written by the `DemoSeeder` inside the backend, at every start
when `DEMO_MODE=true`. `scripts\seed-demo.bat` only starts the backend once so
the seed runs; it does not write rows itself.

**Reason:** the seed has to know the permission templates, the assignments and
the delegations, and those rules live in the access module. A script that wrote
the same rows by hand would be a second, weaker copy of those rules. Every
contributor looks the rows up by their stable demo key, so the seed is
idempotent: starting the backend twice gives the same rows as starting it once,
and a real shop that is not in demo mode is never touched.

---

## A16 - No card was needed for any step

**Decision:** Supabase, Cloudflare Pages and Render's free web services were
used, and no payment card was entered at any point.

**Reason:** the brief forbids working around a card requirement. Nothing in
the bootstrap needed one, so nothing was worked around. The deployment guide
still documents what to do if Render asks for a card, and the hand-back report
records that the live deployment has to be confirmed by the student.

---

## A17 - The PostgreSQL session pooler is mandatory on Supabase

**Decision:** the deployment guide only documents the **session mode** pooler.

**Reason:** the transaction mode pooler hands out a different backend
connection per transaction, which breaks prepared statements and row locking.
The stock locking requirement in the brief depends on `SELECT ... FOR UPDATE`
working. The session pooler also supports IPv4, which the direct connection may
not.

---

## A18 - No hardware or live API was called

**Decision:** nothing in this prompt touches a barcode scanner, a receipt
printer, Pathao, Steadfast, bKash or Nagad.

**Reason:** the brief says the student has no hardware and no live API access,
and that mocks and simulators are the proof. The live adapters for those
services are still "unimplemented" and will be marked as such.

---

## A19 - The public allow list is the only way to open an endpoint

**Decision:** the security chain ends with `anyRequest().authenticated()`, and
the endpoints that may be called without signing in are the six names in
`SecurityConfiguration.PUBLIC_ENDPOINTS`. The list is data in one place, and
`PublicEndpointPolicyTest` proves against the real application context that no
business endpoint is on it and that every name on it maps to an endpoint that
really answers.

**Reason:** the brief asks for a default deny. A rule that is written as
"everything is closed except this list" cannot be widened by forgetting
something, and the test turns the rule into something the build checks instead
of something a reviewer has to remember.

**Alternative that was not taken:** annotating every public endpoint with
`@PermitAll` in the controllers. That would spread the rule over many files,
and a developer who forgets the annotation would open an endpoint silently.

---

## A20 - The proxy decides the address of the caller, not the browser

**Decision:** the Cloudflare Pages Function drops every forwarded header the
browser sent (`X-Forwarded-For`, `X-Real-IP`, `Forwarded`, `Client-IP`) and
writes the address Cloudflare saw (`CF-Connecting-IP`) instead. The backend
reads the address of the caller from `X-Forwarded-For` and never from the
socket, and it trusts that header only because the proxy is the only thing that
can reach it in production.

**Reason:** the address of the caller is written into every audit row and it is
counted in the login throttle. A browser may send any `X-Forwarded-For` it
likes, so a proxy that passed the header on would let anybody lock every
account of the shop with twenty failures and write a false address into the
audit log. Cloudflare overwrites `CF-Connecting-IP` on every request, so the
value that reaches the backend is the address of the real caller.

**Alternative that was not taken:** trusting the header as it came. It is the
usual shortcut and it is only safe when nothing else can reach the backend.
The Render URL is public, so it is not safe here.

**Known limitation:** somebody who calls the Render URL directly is seen as the
address of Cloudflare. That is written down in
[docs/ARCHITECTURE.md](ARCHITECTURE.md) and it is the price of the free tier.

---

## A21 - A gateway status is not a business error

**Decision:** the frontend treats `502`, `503` and `504` the same way as a
network failure. The user sees the waking screen and the request retries on
its own, and no red error box is shown.

**Reason:** those statuses only mean that the free backend is asleep or busy.
They carry no business meaning, so showing them as a failed request would
tell the user that something is wrong with their account when nothing is wrong
at all. The proxy answers with a problem detail in the same shape as the
backend, so the frontend has one rule and not three.

---

## A22 - Quantities are whole numbers

**Decision:** every quantity is a whole number of pieces, held as an `int` or a
`long`. There are no fractional pieces anywhere.

**Reason:** the brief says so, and it is the right model for a shop that sells
whole shirts and whole shoes. The cost of a piece may have decimals, because
money always has decimals, but the count of pieces never does.

**Effect:** the columns `on_hand`, `reserved`, `in_inspection`,
`quantity_delta`, `requested_qty`, `approved_qty`, `dispatched_qty`,
`received_qty`, `shortage_qty`, `system_qty_at_start`, `counted_qty` and
`reorder_point` are all `INT`. The CSV import refuses a row whose opening
quantity is not a whole number with "The opening quantity must be a whole
number."

---

## A23 - The cost method is latest cost

**Decision:** the current cost of a variant is the newest row of
`variant_cost_history`. The cost is never stored on the variant itself.

**Reason:** the cost table is append only, so the newest row is the current
cost and the cost is never in two places at once. It is also the simplest
method that can be explained in a viva and proved by a query.

**Known limitation:** this is a **latest cost** method, not a weighted average.
When the price of a purchase changes, the newest row wins, so the value of the
old stock changes with it. Phase 7 (suppliers and procurement) may replace
this with a weighted average costing. That change is written down here so the
decision is visible.

---

## A24 - A stock transfer never reserves stock

**Decision:** a transfer does not reserve anything. The request moves nothing,
the approval moves nothing and reserves nothing, and only the dispatch takes
the stock off the shelf of the source. Only the receive puts it on the shelf of
the destination.

**Reason:** a reservation would freeze the stock of the source while the
transfer waits, and the source may still sell the last piece in the meantime.
Because only the dispatch moves the stock, the source is checked at the moment
the goods really leave, and a dispatch that asks for more than there is
answers `409 INSUFFICIENT_STOCK`.

**Effect:** the goods in transit are **derived** from the lines
(`dispatched - received - shortage`) and are never stored as stock anywhere.

---

## A25 - An unusual adjustment is flagged, never approved

**Decision:** an adjustment is flagged as unusual when its value at cost is
above the setting `threshold.unusual_adjustment_value`. The flag is written on
the adjustment and nothing else happens.

**Reason:** there is no approval workflow for an adjustment in this version.
The brief of Phase 8 reads the flag in the Action Center, so the flag is
written now and the workflow comes with that phase. The threshold is a
business value, so it comes from the `settings` table and never from a
constant in the code.

**Known limitation:** until Phase 8 exists, an unusual adjustment is only a
mark on a document. Nobody is told about it and nothing waits for a decision.

---

## A26 - The bulk selector of a promotion is a snapshot

**Decision:** the bulk selector of a promotion (all the variants of a product,
a category or a brand) is expanded to concrete variant ids when the promotion
is created. A variant that is created later is **not** covered automatically.

**Reason:** the expansion is written once, in the `promotion_variants` table,
so the price of a line is a lookup and not a query over a whole category. The
price of a variant that did not exist when the promotion was created is
therefore the normal price.

**Effect:** the promotion screen shows the list of the variants it covers, so
the owner can see what is covered and extend it by hand.

---

## A27 - The prefix `MQ-` is reserved for the Merqo document numbers

**Decision:** the prefix `MQ-` is reserved for the document numbers of Merqo,
such as a receipt number or a transfer number, and it may never be used as a
barcode.

**Reason:** a barcode that looks like a document number would make a scan
ambiguous, and the document numbers are the ones a customer or a courier may
read out loud. The rule is applied twice: `BarcodeRules.isValid` refuses the
prefix in the code, and the check constraint
`ck_variant_barcodes_no_reserved_prefix` refuses it in the database, so even a
direct SQL write cannot take it.

**Effect:** the answer of a refused barcode says why in one sentence: "The
prefix MQ- is reserved for Merqo document numbers."

---

## A28 - A variant may not be active without a primary barcode

**Decision:** a variant that is created without a barcode is saved **inactive**.
Activating it is refused with `409 BARCODE_REQUIRED` until it has a primary
barcode. The primary barcode of an active variant may not be removed.

**Reason:** a sellable variant without a code cannot be scanned, so it cannot
be sold at the counter. Saving it inactive instead of refusing the creation
keeps the work of the user: the variant exists and can be given its barcode
later, and the screen shows that it is not sellable yet.

**Effect:** one table holds the primary code and the alternates, so a code is
unique across both kinds and a duplicate is refused with
`409 BARCODE_IN_USE`.

---

## A29 - The tax rate of a product wins over the global rate

**Decision:** the current tax rate of a product is the override on the
product (`products.tax_rate_percent`). A product without a rate of its own
falls back to the global setting `tax.rate_percent`.

**Reason:** some goods have another rate than the shop, and the rate belongs
to the product, not to the shop. The rule lives in `TaxPolicy.effectiveRate`,
so the service, the preview and the tests all apply the same one.

**Effect:** whether the prices carry the tax is decided by the global setting
`tax.inclusive`, and the price calculator is told which of the two applies.

---

## A30 - The cost fields are absent, not zero

**Decision:** the purchase cost, the margin and the value of the stock are
only part of an answer when the caller holds `COST_VIEW` or `PRICE_EDIT` at
that location. For everybody else the fields are simply not in the answer.

**Reason:** zero is a number, and a number is read as a fact. A screen could
not tell the difference between "this variant costs nothing" and "you are not
allowed to know the cost", and a margin computed from it would be wrong
instead of missing. The brief asks for the fields to be absent, and the rule
is in one place: `CatalogService.maySeeCost` and
`CatalogService.requireCostVisibility`.

---

## A31 - The goods in transit are derived, never stored

**Decision:** a transfer that is on the way is derived from the lines of the
transfer. It is never stored as stock in any table.

**Reason:** a stored number can fall out of step with the other numbers. When
a part of the goods arrives, the received quantity changes, and a stored "in
transit" column would have to be updated in the same transaction or it would
lie. `TransferLine.getInTransit` computes it from the three stored numbers
every time, so there is nothing to keep in step.

**Effect:** the same reasoning applies to the available stock of a balance:
it is `onHand - reserved`, not a column of its own.

---

## A32 - A location may have more than one open stock count

**Decision:** starting a stock count never looks at the counts that are already
open. A location may have several counts in progress at the same time, and
every one of them is its own document with its own number.

**Reason:** the Phase 2 rules do not forbid it, and the rule that was in the
code broke four required flows: a count that was never applied blocked the
next count of the same branch forever. Two counters may also count two
categories of the same branch at the same time, which is a normal thing to do
in a shop with more than one stock taker.

**Effect:** the check "there is already a count in progress at this location"
is gone from `StockService.startCount`. The count number comes from the
sequence `count_number_sequence`, so every count still has its own number.

---

## A33 - The movement history has a stable order

**Decision:** the movement history is ordered by the creation moment and then
by the sequence number of the row. The sequence number is written by the
database (`stock_movements.seq`), never by the application.

**Reason:** the clock of Windows has a resolution of about fifteen
milliseconds, so two movements of the same request can carry the same moment.
Ordering by the moment alone left the order between them undefined, and a
test that reads the newest movement first then read the wrong row.

**Effect:** the column was added in the Flyway version `V5`, which never edits
an applied migration. The append only trigger of the table is still in place
and still blocks every UPDATE and every DELETE.

---

## A34 - The refused answer names the records that are in the way

**Decision:** the answer of a refused request always has the same shape. It
carries `fieldErrors`, `errors` and `conflicts`. The first two are empty for
every failure but a validation failure, and the third is empty for every
failure but a promotion conflict. A promotion conflict names every promotion
that is in the way, the variants the two promotions share and the SKUs of those
variants.

**Reason:** the brief asks for the conflicting promotions and the variants on a
`409 PROMOTION_CONFLICT`, and a shopkeeper needs the SKU of the goods, not the
id of a variant. The extra field keeps the one error format of the project: the
frontend has one `ProblemDetail` type and one `ApiError`, and the field is
empty instead of absent for every other failure.

**Effect:** the new class `com.merqo.common.error.PromotionConflictException`
extends `ConflictException`, so the status stays 409 and the code stays
`PROMOTION_CONFLICT`. The frontend field is optional, so an answer from an
older backend is still read correctly. The list of the conflicts is copied when
the exception is created, so a reported conflict cannot be changed afterwards.

---

## A35 - The sign out is a flag, not an emptied cache

**Decision:** the session provider carries a `signedOut` flag. The flag is
raised when the user signs out and when the server answers "who am I?" with a
401. The signed in user is read from the cache only while the flag is down.

**Reason:** `setQueryData` with `undefined` writes nothing at all, and
`queryClient.clear()` removes the cached answer without telling the observer
that the session ended. The shell therefore kept showing the user of the
cached answer after the sign out, and the user was sent to the login page
while the signed in shell was still on the screen. The flag is the one thing
both the sign out and the 401 can set, and the screens read it, so the sign
out takes effect on the very next render.

**Effect:** `isLoading` is also false once the flag is raised, so the screen
does not show "checking the session" for a moment after every sign out. The
flag is lowered again by a sign in, so a second sign in in the same tab works.

**Known limitation:** the flag lives in memory. A reload of the page starts a
new session provider with the flag down, and the session call decides again.
That is correct, because a reload asks the server who the user is.

---

## A36 - The live check reads every Set-Cookie header on its own

**Decision:** the live check reads the `Set-Cookie` headers with
`GetValues('Set-Cookie')` and not with the indexer of the header collection.

**Reason:** the indexer of a repeated header joins the values into one string
with a comma. The answer of the sign in carries two `Set-Cookie` headers, so
the indexer handed back one string that holds both cookies. The strict jar
built from that string held neither of them, and the check reported "no
session cookie was written" on a deployment that was completely fine. The
check now reads the headers one by one, the way the browser reads them.

**Effect:** the same fix was applied to `tools\local-signout-check.ps1`, the
script that walks the sign out through the local proxy. That script builds its
strict jar from the `Set-Cookie` headers of the sign in answer and not from the
jar of the session, so it proves the same thing the browser proves. The two
scripts now report the same thing about the same deployment.
