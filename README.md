# Merqo

**Omnichannel retail operations and profit intelligence system.**

Merqo is a web application for one retail business with a central warehouse and
several branches. It handles point of sale, online orders, stock, couriers,
payments, suppliers and profit reporting.

This repository is an academic project. The backend is a Java 21 Spring Boot
application, the frontend is a React + TypeScript single page application, and
both are hosted for free.

---

## What is in this repository right now

The foundation is in place and the application runs end to end. The **auth and
access** step is finished: people sign in, and what they may do and where they
may do it is decided by the backend, never by the browser.

| Area | Status |
| --- | --- |
| Backend skeleton, profiles, health and ping endpoints | Done |
| Common module: `Money`, `BusinessTime`, exception hierarchy, RFC 7807 errors | Done |
| Correlation id logging | Done |
| Flyway baseline schema on real PostgreSQL | Done |
| Frontend shell, routing, same-origin API client, waking screen, health home page | Done |
| Cloudflare Pages proxy for `/api` | Done |
| Dockerfile, `render.yaml`, `.bat` scripts, documentation | Done |
| Sign in, sign out, sessions in PostgreSQL, login throttling, password policy | Done |
| Accounts and access: roles, permission templates, overrides, delegations, branch assignments | Done |
| Locations: warehouse and branches, with the rules that protect them | Done |
| Business settings and the append only audit log | Done |
| Screens: people, one account, permission templates, locations, audit, settings | Done |
| Default deny: a test proves that no endpoint is open by accident | Done |
| Business features (catalog, POS, orders, couriers, finance, AI) | Not started, one prompt at a time |

---

## Documentation

| Document | What it covers |
| --- | --- |
| [SETUP.md](SETUP.md) | Windows 11 local setup, step by step |
| [DEPLOY.md](DEPLOY.md) | Click-by-click deployment to GitHub, Supabase, Render and Cloudflare Pages |
| [docs/DEPLOY-CHECKLIST.md](docs/DEPLOY-CHECKLIST.md) | The same deployment as a checklist, with the live login check of Prompt 1 |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Modules, packages, patterns, module boundaries |
| [docs/JAVA_GUIDE.md](docs/JAVA_GUIDE.md) | OOP concepts mapped to real classes, viva questions, class diagrams |
| [docs/ASSUMPTIONS.md](docs/ASSUMPTIONS.md) | Every decision taken where the brief left room |
| [docs/INTEGRATION_API.md](docs/INTEGRATION_API.md) | The website integration API, later prompts |
| [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | What to do when something does not work |

---

## Quick start on Windows

```bat
git clone <your-repo-url> merqo
cd merqo

copy .env.example .env
scripts\setup-db.bat
scripts\start-all.bat
```

Then open <http://localhost:5173>. The home page shows the backend health.

Run the tests with:

```bat
scripts\run-tests.bat
```

---

## Repository layout

```
merqo/
├── backend/                 Java 21 Spring Boot application
│   ├── pom.xml              Pinned dependencies
│   ├── mvnw.cmd             Maven Wrapper (no Maven install needed)
│   └── src/
│       ├── main/java/com/merqo/
│       │   ├── MerqoApplication.java
│       │   ├── common/      money, time, error, web, config
│       │   ├── auth/        login, sessions, first owner
│       │   ├── user/        accounts, passwords, throttling
│       │   ├── access/      permissions, templates, delegations
│       │   ├── location/    warehouse, branches
│       │   ├── settings/    the typed business settings
│       │   ├── audit/       the append only audit log
│       │   └── demo/        the demo seed
│       ├── main/resources/
│       │   ├── application*.yml
│       │   └── db/migration/  Flyway migrations (the schema owner)
│       └── test/java/com/merqo/
├── frontend/                React + TypeScript + Vite + Tailwind
│   ├── functions/api/       Cloudflare Pages Function that proxies /api
│   └── src/                 api, components, hooks, lib, pages, session
├── scripts/                 Windows .bat helper scripts
├── docs/                    Architecture, Java guide, assumptions, API spec
├── tools/                   Small developer utilities
├── Dockerfile               Backend image used by the hosting platform
├── render.yaml              Render Blueprint for the backend web service
├── .env.example             Every environment variable, documented
└── .gitattributes           Line endings for Windows and Linux
```

---

## Pinned versions

Every version is pinned so a rebuild always produces the same result.

### Backend

| Component | Version | Note |
| --- | --- | --- |
| Java | 21 (Temurin 21.0.13) | Language level `release 21` |
| Spring Boot | 4.1.3 | Latest stable line that supports Java 21 |
| Spring Framework | 7.0.11 | Managed by Spring Boot |
| Maven | 3.9.9 | Via the Maven Wrapper |
| PostgreSQL JDBC driver | 42.7.14 | Managed by Spring Boot |
| Flyway | 12.4.0 | Managed by Spring Boot |
| springdoc-openapi | 3.2.0 | The 3.x line for Spring Boot 4 |
| Spotless Maven plugin | 3.0.0 | Code style |
| google-java-format | 1.28.0 | Used by Spotless, AOSP style |
| JUnit | 6.0.3 | Managed by Spring Boot |
| Mockito | 5.23.0 | Managed by Spring Boot |

### Frontend

| Component | Version |
| --- | --- |
| Node.js | 24.18.0 LTS (any current LTS works) |
| React / React DOM | 19.3.1 |
| TypeScript | 5.9.3 |
| Vite | 8.3.8 |
| Tailwind CSS | 4.3.5 |
| React Router | 7.18.6 |
| TanStack Query | 5.108.2 |
| Vitest | 5.1.1 |
| Testing Library | React 16.3.3, jest-dom 8.0.0, user-event 14.6.13 |
| ESLint | 10.15.0 |
| Prettier | 3.10.1 |

### Database

| Component | Version |
| --- | --- |
| PostgreSQL | 17 (development and test) |
| Supabase PostgreSQL | current free tier (hosted) |

---

## Architecture in one paragraph

A **modular monolith**: one Spring Boot application, one PostgreSQL database.
Packages are grouped by business module (`auth`, `users`, `locations`, `catalog`,
`inventory`, `transfers`, `pos`, `orders`, `customers`, `payments`, `shipping`,
`suppliers`, `procurement`, `finance`, `analytics`, `integrations`,
`notifications`, `audit`, `ai`, `common`). Every module has `controller`,
`service`, `repository`, `domain`, `dto`, `mapper` and `exception` folders.
Modules never call another module's repository, only its service interface, and
cross-module reactions use Spring application events.

The frontend and the backend live on different hosts. A **Cloudflare Pages
Function** proxies `/api/*` so the browser only ever sees one origin. That keeps
the session cookie a first party cookie, so login works in Chrome, Edge,
Firefox and Safari even with third party cookies blocked.

---

## Non-negotiable rules that shaped the code

- **Money** is `BigDecimal` only, scale 2, `HALF_UP`. One shared, tested
  `Money` class: `com.merqo.common.money.Money`.
- **Time** is stored in UTC (`TIMESTAMPTZ`). Business days, reports and receipts
  use `Asia/Dhaka`: `com.merqo.common.time.BusinessTime`.
- **No in-memory database.** Tests run against a real PostgreSQL (`merqo_test`).
- **No Docker on the student machine.** The only Dockerfile is the one the
  hosting platform builds.
- **No Lombok, no secrets in git, no tokens in `localStorage`.**

---

## Licence

Academic project. Not for commercial use.
