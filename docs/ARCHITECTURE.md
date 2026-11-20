# ARCHITECTURE.md

**How Merqo is put together, and why.**

---

## 1. The big picture

Merqo is a **modular monolith**:

- one Spring Boot application,
- one PostgreSQL database,
- one React single page application,
- one Cloudflare Pages proxy so the browser sees a single origin.

There are no microservices, no message brokers and no caches. That is a
deliberate choice for a university project that has to be understandable in a
viva and deployable on free hosting.

```
┌─────────────────────────┐
│  Browser                │
│  (same origin only)     │
└───────────┬─────────────┘
            │ https://merqo.pages.dev/api/v1/...
┌───────────▼─────────────┐
│  Cloudflare Pages       │
│  functions/api/[[path]] │  ← the proxy
└───────────┬─────────────┘
            │ https://merqo-api.onrender.com/api/v1/...
┌───────────▼─────────────┐
│  Render free web svc    │
│  Spring Boot 4.1.3      │
│  (512 MB, sleeps        │
│   after 15 min idle)    │
└───────────┬─────────────┘
            │ JDBC (session mode pooler)
┌───────────▼─────────────┐
│  Supabase PostgreSQL    │
│  Flyway owns the schema │
└─────────────────────────┘
```

---

## 2. Package structure

```
com.merqo
├── MerqoApplication            entry point
├── auth                        login, sessions, first owner (Prompt 1)
├── users                       accounts, roles, permissions (Prompt 1)
├── locations                   warehouse, branches (Prompt 1)
├── catalog                     products, variants, barcodes (Prompt 2)
├── inventory                   stock, movements, adjustments (Prompt 2)
├── transfers                   warehouse to branch transfers (Prompt 2)
├── pos                         point of sale, scan to cart (Prompt 3)
├── orders                      online orders, calls, confirmations (Prompt 4)
├── customers                   customer history and risk (Prompt 4)
├── payments                    payments, refunds, COD (Prompt 5)
├── shipping                    Pathao and Steadfast (Prompt 6)
├── suppliers                   supplier ledger (Prompt 7)
├── procurement                 purchase orders, receiving (Prompt 7)
├── finance                     costs, allocation (Prompt 8)
├── analytics                   dashboards, action centre (Prompt 8)
├── integrations                website, courier and payment adapters
├── notifications               messages and links
├── audit                       audit log
├── ai                          the assistant (Prompt 9)
└── common                      shared foundation (built in this prompt)
```

Every business module uses the same inner layout:

```
<module>/
├── controller/   HTTP endpoints, no business logic
├── service/      business rules, transaction boundaries
├── repository/   the only place with SQL
├── domain/       entities, value objects, domain services
├── dto/          request and response records
├── mapper/       entity to DTO mapping
└── exception/    module specific failures
```

### The rules that keep it a modular monolith

1. **Controllers hold no business logic.** A controller reads the request, calls
   one service method and returns a DTO.
2. **Entities are never exposed.** The API answers with records from `dto`.
3. **Modules never touch another module's repository.** They call that module's
   service interface.
4. **Cross-module reactions use Spring application events.** For example, when
   a sale is confirmed, the inventory module listens for the event instead of
   the POS module calling the inventory repository.
5. **No raw SQL outside repositories.**
6. **Business values come from the `settings` table.** No name, address, phone,
   tax rate or threshold is a Java constant.

---

## 3. The common module (built in this prompt)

```
common/
├── money/
│   └── Money.java              value object, the only money arithmetic
├── time/
│   └── BusinessTime.java       UTC storage, Asia/Dhaka business days
├── error/
│   ├── BusinessException.java  abstract parent of every expected failure
│   ├── NotFoundException.java
│   ├── ConflictException.java
│   ├── ValidationException.java
│   ├── ForbiddenException.java
│   └── ProblemDetail.java      the RFC 7807 error shape
├── web/
│   ├── HealthController.java   /health and /health/ping
│   ├── GlobalExceptionHandler.java
│   ├── CorrelationIdFilter.java
│   └── CorrelationIdFilterConfiguration.java
└── config/
    ├── CommonConfiguration.java      the Clock bean
    ├── CorsConfigurationSetup.java    CORS from the environment
    ├── MerqoCorsProperties.java
    ├── MerqoModeProperties.java       DEMO_MODE, COURIER_MODE, ...
    ├── OpenApiConfiguration.java
    ├── SecurityConfiguration.java
    └── WebConfiguration.java
```

### Why the common module exists

Every later module needs money, time, errors and a correlation id. Putting them
in one place means there is exactly one rounding rule and exactly one day
boundary in the whole system. That is also the part the instructor will ask
about, so it is written to be read.

---

## 4. Request flow

```
Browser
  │
  ▼
Cloudflare Pages Function  (frontend/functions/api/[[path]].ts)
  │   forwards /api/* to the backend, returns the answer unchanged
  ▼
CorsFilter (development only)
  ▼
CorrelationIdFilter         ← puts the id into the MDC, every log line gets it
  ▼
SecurityFilterChain
  ▼
Controller                  ← reads the request, calls one service
  ▼
Service                     ← business rules, @Transactional
  ▼
Repository                  ← the only place with SQL
  ▼
PostgreSQL
```

If anything throws:

```
Exception
  ▼
GlobalExceptionHandler      ← turns it into one RFC 7807 problem detail
  ▼
Browser                     ← {errorCode, detail, correlationId, fieldErrors, ...}
```

---

## 5. The same-origin solution

### The problem

The frontend is on `merqo.pages.dev`, the backend is on
`merqo-api.onrender.com`. If the browser called the backend directly, the
session cookie would be a **third party cookie**. Chrome, Edge, Firefox and
Safari all block or limit those by default now, so the login would work on the
developer's machine and break in front of the instructor.

### The solution

A **Cloudflare Pages Function** in `frontend/functions/api/[[path]].ts`
forwards every `/api/*` request to the backend and returns the response
unchanged. The browser therefore only ever talks to
`https://merqo.pages.dev`, and the session cookie is a **first party cookie**.

```
Browser  ──►  merqo.pages.dev/api/v1/...          (same origin, cookie works)
                    │
                    ▼
             Pages Function
                    │
                    ▼
             merqo-api.onrender.com/api/v1/...
```

### Why the session cookie and not a token

- **Tokens in `localStorage` are forbidden by the brief** and are also worse
  for security: `localStorage` is readable by any script on the page, so one
  cross site scripting bug hands the token to the attacker.
- A cookie with `HttpOnly` and `SameSite=Lax` is not readable by JavaScript at
  all.
- Sessions live in PostgreSQL (`SPRING_SESSION` tables, created by Flyway), so
  a login survives the backend restarting. On a free Render instance the
  backend restarts often, so this matters.

The session tables are created by Flyway, not by Spring Session, so the schema
stays owned by one tool.

### The same code locally

The Vite dev server proxies `/api` to `http://localhost:8080` with the same
shape. The frontend code is identical in development and production: it always
calls a relative `/api/v1/...` path.

### Fallback

If the Pages Function cannot be used (for example the account cannot create
Pages projects), the frontend can fall back to calling the backend directly and
using a bearer token. That fallback is **not implemented** in this prompt
because it would weaken the cookie design, and it is only needed if the
Cloudflare route fails. It is listed in the known gaps.

---

## 6. Database access

### Schema ownership

Flyway owns the schema. Hibernate is set to `ddl-auto: validate`, so a missing
or wrong column fails the startup instead of silently changing the database.

### The migration files

```
backend/src/main/resources/db/migration/
└── V1__baseline_schema.sql
```

Every later prompt adds `V2__...`, `V3__...` and so on. A migration is never
edited after it has been applied; a mistake is fixed with a new migration.

### Why PostgreSQL and never H2

The hardest requirement in this project is that two cashiers cannot sell the
same last item. That behaviour depends on how PostgreSQL locks rows
(`SELECT ... FOR UPDATE` or an atomic conditional update). H2 does not lock the
same way, so a test against H2 would pass while production is broken. The
integration tests therefore run against a real `merqo_test` database.

### Connection string

```
jdbc:postgresql://HOST:5432/DBNAME?user=USER&password=PASSWORD
```

On Supabase the **session mode pooler** must be used, not the transaction mode
pooler. Transaction mode hands out a different backend connection per
statement, which breaks prepared statements and row locking. The session mode
pooler also supports IPv4, which the direct connection may not.

The pool is deliberately small (`maximum-pool-size=5`), because the free
Supabase tier limits the number of client connections.

---

## 7. Configuration

Everything is an environment variable. Nothing secret is in the code and
nothing secret is in git.

| Variable | Meaning | Default |
| --- | --- | --- |
| `DATABASE_URL` | JDBC connection string | `jdbc:postgresql://localhost:5432/merqo` |
| `DB_USER` | database user | `postgres` |
| `DB_PASSWORD` | database password | `postgres` |
| `DB_SCHEMA` | schema name | `public` |
| `PORT` | HTTP port | `8080` |
| `CORS_ORIGINS` | allowed browser origins | `http://localhost:5173` |
| `DEMO_MODE` | demo experience on or off | `false` |
| `COURIER_MODE` | `mock`, `pathao`, `steadfast` | `mock` |
| `PAYMENT_MODE` | `mock`, `manual` | `mock` |
| `WEBSITE_MODE` | `mock`, `generic` | `mock` |
| `LLM_MODE` | `mock` or a provider | `mock` |
| `INITIAL_OWNER_EMAIL` | first owner, used when `DEMO_MODE=false` | empty |
| `INITIAL_OWNER_PASSWORD` | first owner, used when `DEMO_MODE=false` | empty |

Profiles: `dev` (local), `test` (`merqo_test`), `prod` (Render).

---

## 8. Performance for 512 MB

The free Render instance has 512 MB of RAM, so the backend is tuned for it:

| Measure | Value | Why |
| --- | --- | --- |
| `-XX:MaxRAMPercentage=70` | the heap never grows past 70 % of the container | leaves room for the JVM itself |
| `-XX:+UseSerialGC` | the serial garbage collector | a small heap does not need a parallel collector, and it uses less memory |
| `-Xss512k` | smaller thread stacks | many request threads, little recursion |
| `maximum-pool-size=5` | five database connections | the free Supabase tier limits connections |
| `spring.threads.virtual.enabled=true` | virtual threads for request handling | cheap threads for waiting on the database |

Measured startup time and memory use are in the hand-back report of Prompt 0.

---

## 9. Patterns used, and where they will appear

| Concept | Where |
| --- | --- |
| Encapsulation | `Money`, `BusinessTime`, `MerqoModeProperties`, all domain objects |
| Abstraction | `BusinessException`, `SecurityFilterChain`, `WebMvcConfigurer` |
| Inheritance | the `BusinessException` family |
| Polymorphism | `GlobalExceptionHandler`, later the discount and payment strategies |
| Interfaces | `CourierProvider`, `PaymentVerifier`, `DiscountRule`, `ReceiptRenderer`, `WebsiteOrderSource` |
| Strategy | discount rules, payment verifiers, cost allocation |
| Adapter | `PathaoAdapter`, `SteadfastAdapter`, `MockCourierAdapter`, website connectors |
| Factory / registry | choosing an implementation from `COURIER_MODE` and friends |
| State | order and transfer status transitions in one transition table |
| Observer | Spring application events for stock and order changes |
| Composition | a sale contains lines; an order contains lines, payments and shipments |
| Value objects | `Money`, `Quantity` as immutable records |

Only the ones that are real in the current code are described in
[JAVA_GUIDE.md](JAVA_GUIDE.md) with a concrete class path. The rest appear as
the prompts that need them arrive.

---

## 10. Security

- Individual accounts with a BCrypt hash. No shared logins.
- Login throttling (Prompt 1).
- Every endpoint checks **both** the permission and the user's assigned
  locations. A branch id sent by the client is never trusted.
- Sensitive actions are audited: who, when, old value, new value.
- Secrets only in environment variables. `.env` is in `.gitignore`.
- No personal data and no secrets in the logs.
- The session cookie is `HttpOnly` and `SameSite=Lax`, and the session is stored
  in PostgreSQL.
