# TROUBLESHOOTING.md

**What to do when something does not work.**

---

## 1. Local setup

### `psql` is not recognized

PostgreSQL is installed but not on the PATH.

```powershell
& "C:\Program Files\PostgreSQL\17\bin\psql.exe" --version
```

If that works, either add `C:\Program Files\PostgreSQL\17\bin` to the PATH
(*System Properties → Environment Variables → Path → Edit*), or set `PGBIN`
before running the scripts:

```powershell
$env:PGBIN = "C:\Program Files\PostgreSQL\17\bin"
scripts\setup-db.bat
```

### `scripts\setup-db.bat` says "Could not create the database"

PostgreSQL is not running. Start the service:

```powershell
Get-Service postgresql* | Start-Service
```

Or check whether the password in `.env` matches the one you chose during the
PostgreSQL installation.

### `scripts\setup-db.bat` reports "role postgres does not exist"

The PostgreSQL superuser has a different name. Find it:

```powershell
& "C:\Program Files\PostgreSQL\17\bin\psql.exe" -U postgres -d postgres -c "\du"
```

Then put that name in `DB_USER` in `.env`.

### The backend stops with "Connection refused"

The database is not running, or `DATABASE_URL` points somewhere else. Test the
connection directly:

```powershell
& "C:\Program Files\PostgreSQL\17\bin\psql.exe" -U postgres -d merqo -c "SELECT 1"
```

### The backend stops with "FATAL: database merqo does not exist"

Run `scripts\setup-db.bat`.

### The backend stops with "password authentication failed"

The `DB_PASSWORD` in `.env` does not match. Reset it:

```powershell
& "C:\Program Files\PostgreSQL\17\bin\psql.exe" -U postgres -d postgres -c "ALTER ROLE postgres WITH LOGIN PASSWORD 'yourpassword';"
```

### Port 8080 is already in use

```powershell
Get-NetTCPConnection -LocalPort 8080 | Select-Object OwningProcess
Stop-Process -Id <the number>
```

Or change `PORT` in `.env`.

### The frontend cannot reach the backend

The Vite dev server proxies `/api` to `VITE_API_PROXY_TARGET`, which defaults to
`http://localhost:8080`. If the backend runs elsewhere, set it in `.env`:

```dotenv
VITE_API_PROXY_TARGET=http://localhost:9090
```

Then restart the frontend.

---

## 2. Tests

### The backend integration test fails with "connection refused"

The `merqo_test` database does not exist or PostgreSQL is not running.

```powershell
scripts\setup-db.bat
```

### "Flyway cannot validate the migration"

Some migration file was changed after it was applied. Flyway checksums the
file. During development the safe fix is to rebuild the test database:

```powershell
& "C:\Program Files\PostgreSQL\17\bin\psql.exe" -U postgres -d postgres -c "DROP DATABASE merqo_test;"
& "C:\Program Files\PostgreSQL\17\bin\psql.exe" -U postgres -d postgres -c "CREATE DATABASE merqo_test;"
```

> **Never do this to the hosted database.** It would delete the data.

### The tests pass locally but fail on the free instance

The hosted database is reached through the **session mode pooler**. The
transaction mode pooler would break row locking. See A17 in
[ASSUMPTIONS.md](ASSUMPTIONS.md).

### The frontend test says "Failed to resolve import vitest"

```powershell
cd frontend
npm install
```

---

## 3. Deployment

### The first thing to run when the site does not work

```powershell
scripts\check-live.bat
```

The script checks the frontend, the backend, the database, the proxy, demo mode
and the demo login, and it prints one `[PASS]` or `[FAIL]` line for each. The
line under a `[FAIL]` says what to do. Start there instead of guessing.

If the project was created under another name, pass the real URLs:

```powershell
scripts\check-live.bat https://<your-project>.pages.dev https://<your-api>.onrender.com
```

### The Render build fails

Open **Render → your service → Logs** and read the first error. The most
common causes:

- **"Could not resolve dependencies"** - the build machine could not reach
  Maven Central. Retry the deploy.
- **"Dockerfile not found"** - the Dockerfile path must be `./Dockerfile` and
  the context must be `.` (the repository root).
- **"Out of memory during the Maven build"** - the Docker build stage has no
  memory limit, but a very slow machine can still time out. Retry.

### Render deploys, but every request returns 503

The backend cannot reach the database. Check in this order:

1. `DATABASE_URL` uses the **session pooler** host, not `db.<ref>.supabase.co`.
2. `DB_USER` is `postgres.<ref>`, not just `postgres`.
3. `DB_PASSWORD` is the database password, not the Supabase account password.
4. The Supabase project is not paused. Open the Supabase dashboard: a paused
   project shows a "Restore" button.

### Flyway fails during the Render startup

```
org.flywaydb.core.internal.exception.FlywayException: Unsupported Database
```

The `flyway-database-postgresql` dependency is missing from the pom. It is
there in this repository; make sure the deployed commit includes it.

### The frontend shows "Server is waking up" forever

The Pages Function cannot reach the backend.

1. Check `API_BASE_URL` in **Cloudflare Pages → Settings → Environment
   variables**. It must be the full backend URL with `https://` and no trailing
   slash.
2. Redeploy after changing it: **Deployments → Trigger deploy**.
3. Open the Pages Function log: **Cloudflare Pages → your project → Functions
   → Logs**.

The answer of the proxy says what went wrong. Open the developer tools →
**Network** → the failing request → the body:

| `errorCode` | Meaning |
| --- | --- |
| `PROXY_NOT_CONFIGURED` | `API_BASE_URL` is not set on the Pages project. |
| `BACKEND_UNREACHABLE` | the backend refused the connection. It is asleep or the URL is wrong. |
| `BACKEND_TIMEOUT` | the backend did not answer in thirty seconds. |
| `BACKEND_UNAVAILABLE` | the backend answered with a gateway error of its own. |

The `correlationId` in the body ties the answer to the log lines of the
backend, so the same id can be searched in **Render → Logs**.

### The audit log shows the address of Cloudflare

That is expected. The proxy writes the address Cloudflare saw, not the header
the browser sent, so the browser cannot lie about where a request came from.
Somebody who calls the Render URL directly is therefore seen as Cloudflare.
See [ASSUMPTIONS.md](ASSUMPTIONS.md) A20.

### The frontend shows a CORS error

`CORS_ORIGINS` on Render must exactly match the Pages URL, including the
scheme, and must not have a trailing slash:

```
https://merqo.pages.dev
```

### The site works, then sleeps after 15 minutes

The free Render instance sleeps after fifteen idle minutes. Set up the uptime
monitor as described in [DEPLOY.md](../DEPLOY.md) step 6. The first request
after a sleep can take up to a minute.

### Render asks for a payment card

**Stop and report it.** Do not enter the card. The brief says: if any step
would need a card, stop and report it, do not work around it.

Note which screen asked and what it said. The likely fallback is to host the
frontend on Render as a static site together with the backend, or to use a
different free host for the backend. Both are documented in
[ASSUMPTIONS.md](ASSUMPTIONS.md) A2.

---

## 4. The browser

### Login does not work in Firefox or Safari but works in Chrome

Check that the login request goes to the **Pages** domain and not to the
Render domain. Open the developer tools → **Network** → click the login request
→ the URL must start with `https://merqo.pages.dev`.

If it points at `merqo-api.onrender.com`, then `API_BASE_URL` is wrong or the
proxy is not being used.

### The cookie is not sent

The session cookie must not be `SameSite=None`. With the Pages proxy the
cookie is a first party cookie, so `SameSite=Lax` is enough. A `None` cookie
is blocked by browsers that block third party cookies.

### The page is blank after a deploy

The browser cached the old `index.html`. Hard reload with `Ctrl+Shift+R`, or
open a private window.

---

## 5. Getting help

When reporting a problem, include:

1. the **correlation id** from the error message, for example
   `a1b2c3d4-...`,
2. what you were doing,
3. the output of the command that failed.

The correlation id is in the `X-Correlation-Id` response header and in every log
line of that request, which makes the exact cause findable with one search.
