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
├── user                        accounts, passwords, throttling (Prompt 1)
├── access                      permissions, templates, delegations (Prompt 1)
├── location                    warehouse, branches (Prompt 1)
├── settings                    the typed business settings (Prompt 1)
├── audit                       audit log (Prompt 1)
├── demo                        the demo seed (Prompt 1)
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
├── ai                          the assistant (Prompt 9)
└── common                      shared foundation (built in Prompt 0)
```

The packages that exist in the code today are the ones in bold in the table in
the README. A package is a flat folder of classes in this project: the inner
`controller/`, `service/`, `repository/` layout below is the target for the
business modules that come later. The modules that are already built keep their
classes in one folder, because each of them is small enough that the extra
folders would only hide the flow of a request.

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

## 3. The common module (built in Prompt 0)

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
│   ├── UnauthenticatedException.java
│   ├── ErrorCodes.java         every stable error code in one place
│   └── ProblemDetail.java      the RFC 7807 error shape
├── web/
│   ├── HealthController.java   /health and /health/ping
│   ├── GlobalExceptionHandler.java
│   ├── CorrelationIdFilter.java
│   ├── CorrelationIdFilterConfiguration.java
│   ├── CsrfTokenCookieFilter.java    the XSRF-TOKEN cookie
│   ├── ProblemAuthenticationEntryPoint.java
│   ├── ProblemAccessDeniedHandler.java
│   ├── ProblemResponseWriter.java
│   ├── PageRequest.java
│   └── PageResponse.java
└── config/
    ├── CommonConfiguration.java      the Clock bean
    ├── CorsConfigurationSetup.java    CORS from the environment
    ├── MerqoCorsProperties.java
    ├── MerqoModeProperties.java       DEMO_MODE, COURIER_MODE, ...
    ├── MerqoSessionProperties.java    cookie name, timeouts
    ├── SessionCookieConfiguration.java
    ├── OpenApiConfiguration.java
    ├── SecurityConfiguration.java
    └── WebConfiguration.java
```

### Why the common module exists

Every later module needs money, time, errors and a correlation id. Putting them
in one place means there is exactly one rounding rule and exactly one day
boundary in the whole system. That is also the part the instructor will ask
about, so it is written to be read.

### The modules built in Prompt 1

```
auth/      AuthController, OwnerBootstrapService, DemoService
user/      AppUser, AuthService, UserService, PasswordPolicy,
           LoginThrottlePolicy, SessionSecurityContextRepository,
           PasswordChangeRequiredFilter, CurrentUserService
access/    Permission, PermissionResolver, PermissionService,
           AccessContext, EffectivePermissions, DelegationPolicy,
           AssignmentService, the templates and the overrides
location/  Location, LocationService, LocationController
settings/  SettingKey, SettingService, SettingController
audit/     AuditEvent, AuditLog, AuditService, AuditLogController
demo/      DemoSeeder and the demo contributors
```

`access` is the module that decides **who may do what, and where**. It holds
no data of its own except the templates, the overrides and the delegations;
the accounts and the locations live in `user` and `location`. The services
never reach into another module's repository: they ask the module's service.

### The screens built in Prompt 1

```
frontend/src/
├── api/
│   ├── client.ts        the fetch wrapper, the CSRF header, the error shape
│   ├── auth.ts          sign in, sign out, the session, the demo accounts
│   ├── business.ts      one thin function per business endpoint
│   └── models.ts        the shapes of the answers, no behaviour
├── components/
│   └── AppShell.tsx     the navigation, only the links the permissions allow
├── pages/
│   ├── LoginPage.tsx              sign in, demo accounts, first owner setup
│   ├── ChangePasswordPage.tsx
│   ├── HomePage.tsx               the backend health
│   ├── LocationsPage.tsx          the branches and the warehouse
│   ├── UsersPage.tsx              the accounts
│   ├── UserDetailPage.tsx         templates, overrides, delegations, assignments
│   ├── PermissionTemplatesPage.tsx  the named sets of permissions
│   ├── AuditPage.tsx              the append only log
│   └── SettingsPage.tsx           the typed business values
└── session/
    ├── SessionProvider.tsx  the one place that asks "who am I?"
    └── RequireSession.tsx   the guard in front of every business screen
```

The frontend never decides what the caller may see. It asks the session once
and hides the links the caller cannot use, and the backend checks the
permission and the scope again on every request.

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
- Sessions live in PostgreSQL (`spring_session` tables, created by Flyway), so
  a login survives the backend restarting. On a free Render instance the
  backend restarts often, so this matters.

The session tables are created by Flyway, not by Spring Session, so the schema
stays owned by one tool.

### The session holds only the user id

The session attribute is the id of the account and nothing else. The account,
its permission templates, its overrides and its location assignments are read
again on **every request**. That is what makes a change take effect at once:
ending an assignment, deactivating an account or changing a template is
visible on the very next request, not at the next sign in.

The session has two timeouts: the **idle timeout** of eight hours, which the
session itself enforces, and the **absolute timeout** of twelve hours, which
the repository enforces by refusing a session that was created longer ago
than that, no matter how often it was used.

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
| `SESSION_COOKIE_NAME` | the name of the session cookie | `MERQO_SESSION` |
| `SESSION_IDLE_TIMEOUT_MINUTES` | how long a session may sit unused | `480` |
| `SESSION_ABSOLUTE_TIMEOUT_MINUTES` | how long a session may live in total | `720` |
| `SESSION_SECURE_COOKIE` | the `Secure` flag on the cookie | `false`, `true` in `prod` |
| `INITIAL_OWNER_USERNAME` | first owner, used when `DEMO_MODE=false` | `owner` |
| `INITIAL_OWNER_EMAIL` | first owner, used when `DEMO_MODE=false` | empty |
| `INITIAL_OWNER_PASSWORD` | first owner, used when `DEMO_MODE=false` | empty |
| `RESET_OWNER_PASSWORD_ON_START` | resets the owner password on every start | `false` |

Profiles: `dev` (local), `test` (`merqo_test`), `prod` (Render).

The complete list with the comments lives in [`.env.example`](../.env.example).
The frontend reads `VITE_API_PROXY_TARGET`, `VITE_PORT` and
`VITE_PREVIEW_PORT`; the Cloudflare Pages Function reads `API_BASE_URL`.

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
| Encapsulation | `Money`, `BusinessTime`, `MerqoModeProperties`, `MerqoSessionProperties`, `AccessContext`, all domain objects |
| Abstraction | `BusinessException`, `SecurityFilterChain`, `WebMvcConfigurer`, `PermissionResolver`, `DelegationPolicy` |
| Inheritance | the `BusinessException` family |
| Polymorphism | `GlobalExceptionHandler`, the `DemoDataContributor` family, later the discount and payment strategies |
| Interfaces | `AppUserRepository`, `AuditLogRepository`, `DemoDataContributor`, later `CourierProvider`, `PaymentVerifier`, `DiscountRule`, `ReceiptRenderer`, `WebsiteOrderSource` |
| Strategy | `SessionSecurityContextRepository`, the permission resolver, later discount rules, payment verifiers, cost allocation |
| Adapter | `PathaoAdapter`, `SteadfastAdapter`, `MockCourierAdapter`, website connectors |
| Factory / registry | choosing an implementation from `COURIER_MODE` and friends |
| State | order and transfer status transitions in one transition table |
| Observer | Spring application events for stock and order changes; the `DemoSeeder` reacts to the application start |
| Composition | a sale contains lines; an order contains lines, payments and shipments; `EffectivePermissions` holds the global and the location scoped permissions |
| Value objects | `Money`, `Quantity` as immutable records, `ProblemDetail` and `PageResponse` as records |
| Builder | `AuditEvent.Builder`, for an event with many optional parts |
| Fail fast | `AccessContext.requireGlobal` and `require`, so a service cannot forget to check |

Only the ones that are real in the current code are described in
[JAVA_GUIDE.md](JAVA_GUIDE.md) with a concrete class path. The rest appear as
the prompts that need them arrive.

---

## 10. Security

- **Default deny.** The security chain ends with `anyRequest().authenticated()`.
  The only endpoints that may be called without signing in are the six names on
  the public allow list in `SecurityConfiguration.PUBLIC_ENDPOINTS`: the login,
  the status, the two demo endpoints and the two health endpoints.
  `PublicEndpointPolicyTest` walks every endpoint the application really maps
  and proves that the allow list holds no business endpoint and no name that
  answers nothing, so a new endpoint cannot be left open by accident.
- Individual accounts with a BCrypt hash. No shared logins.
- Login throttling: five failures lock the account, twenty failures from one
  address throttle the address. The counter lives in its own table, so a
  restart of the backend does not clear it.
- Every endpoint checks **both** the permission and the user's assigned
  locations. A branch id sent by the client is never trusted, and a location
  outside the scope is answered with `404`, not with `403`.
- A wrong password always gives the same answer as an account that does not
  exist, and the hash is computed either way, so neither the message nor the
  timing leaks that the account exists.
- CSRF is on. The token travels in a readable `XSRF-TOKEN` cookie and comes
  back in the `X-XSRF-TOKEN` header; a request that changes something without
  it is refused with `403 CSRF_TOKEN_INVALID`.
- The session is stored in PostgreSQL and the session holds **only the user
  id**. The permissions are read again on every request, so ending an
  assignment or deactivating an account takes effect on the very next request.
- Sensitive actions are audited: who, when, old value, new value. The audit
  log is append only, and a database trigger refuses any `UPDATE` or
  `DELETE`.
- Secrets only in environment variables. `.env` is in `.gitignore`.
- No personal data and no secrets in the logs.
- The session cookie is `HttpOnly`, `SameSite=Lax` and host only, so it never
  leaves the site of the frontend.
