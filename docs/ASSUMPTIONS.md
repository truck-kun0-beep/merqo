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

## A15 - The demo seeder does not exist yet

**Decision:** `scripts\seed-demo.bat` reports that the demo data has not been
written yet, and changes nothing in the database.

**Reason:** the brief says "build only what the current prompt asks" and the
demo seed belongs to the prompt that introduces demo mode. A script that
pretends to seed data would be a fake.

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
