# SETUP.md - Local development on Windows 11

This guide takes a fresh Windows 11 machine to a running Merqo. Everything is a
click in a normal application or a double click on a `.bat` file. No Linux
server, no SSH, no Docker.

**Time needed:** about 30 minutes the first time, 5 minutes afterwards.

---

## 1. What you need to install

Install these four things, in this order. Each link is the official download
page.

| # | Software | Version | Download |
| --- | --- | --- | --- |
| 1 | **JDK 21** | 21 LTS | <https://adoptium.net/temurin/releases/?version=21> |
| 2 | **PostgreSQL** | 17 or newer | <https://www.postgresql.org/download/windows/> |
| 3 | **Node.js LTS** | 20 or newer | <https://nodejs.org/en/download> |
| 4 | **Git** | any current version | <https://git-scm.com/download/win> |

### 1.1 JDK 21

1. Open <https://adoptium.net/temurin/releases/?version=21>.
2. Download the **JDK 21** `.msi` file for Windows x64.
3. Double click it and accept the defaults. Tick "Set `JAVA_HOME` variable" if
   the installer offers it.
4. Open a **new** PowerShell window and check:

```powershell
java -version
```

You must see `21.x`. If you see another version, `JAVA_HOME` points at the
wrong JDK. Fix it in *System Properties → Environment Variables* and open a new
terminal.

### 1.2 PostgreSQL

1. Open <https://www.postgresql.org/download/windows/> and download the
   installer.
2. Run it. The defaults are fine, with two exceptions:
   - **Password**: choose one and write it down. You will need it in `.env`.
   - **Port**: leave `5432`.
3. Keep **pgAdmin** ticked if you want a graphical tool, it is useful for
   looking at the data.
4. When the installer finishes, PostgreSQL is already running as a Windows
   service. Check it:

```powershell
Get-Service -Name postgresql*
```

The status should be `Running`.

> **Note:** the installer adds `psql` to the PATH only for new terminals. If
> `psql` is not found, add `C:\Program Files\PostgreSQL\17\bin` to the PATH, or
> set the `PGBIN` variable in the scripts.

### 1.3 Node.js LTS and Git

Install both with the default options. Check them:

```powershell
node -v
npm -v
git --version
```

---

## 2. Get the code

```powershell
git clone <your-repo-url> merqo
cd merqo
```

If you do not have a GitHub repository yet, create one at
<https://github.com/new> (make it private or public, both work), then push:

```powershell
git remote add origin <your-repo-url>
git push -u origin main
```

---

## 3. Create the `.env` file

The `.env` file holds your local settings. It is **never** committed, because
`.gitignore` already excludes it.

```powershell
copy .env.example .env
```

Open `.env` in Notepad and set at least these two values:

```dotenv
DB_PASSWORD=the-password-you-chose-in-the-postgresql-installer
DATABASE_URL=jdbc:postgresql://localhost:5432/merqo
```

Everything else can stay as it is. `DEMO_MODE=true` shows the demo experience.

> **Never commit `.env`.** Only `.env.example` belongs in git. Check with
> `git status` before you commit.

---

## 4. Create the databases

Double click **`scripts\setup-db.bat`**, or run it from PowerShell:

```powershell
scripts\setup-db.bat
```

It creates the databases `merqo` (development) and `merqo_test` (tests) and
makes sure the `postgres` user can log in with the password from your `.env`.

Expected output:

```
[OK]   Created database merqo
[OK]   Created database merqo_test
[OK]   Role postgres can log in with the password from .env
```

Running it twice is harmless: it skips the databases that already exist.

---

## 5. Start Merqo

Double click **`scripts\start-all.bat`**.

It opens two windows:

| Window | What it is | Address |
| --- | --- | --- |
| `Merqo backend` | Spring Boot on port 8080 | <http://localhost:8080> |
| the current one | Vite dev server on port 5173 | <http://localhost:5173> |

The first start builds the backend jar and downloads the Maven dependencies, so
it takes a few minutes. Later starts take seconds.

Open **<http://localhost:5173>**. The home page shows the backend health:
application `UP`, database `UP`.

With `DEMO_MODE=true` the backend writes the demo locations and the demo
accounts into the database at every start. The login page then shows the demo
accounts, and one click signs you in. The seed is idempotent, so starting the
backend twice gives the same rows as starting it once.

### The frontend talks to its own origin

The Vite dev server proxies `/api/*` to `http://localhost:8080`, exactly like
the Cloudflare Pages Function does in production. The browser therefore only
ever calls `http://localhost:5173/api/...`, which is why the session cookie
behaves the same in development and in production.

To point the dev server at a different backend, set `VITE_API_PROXY_TARGET` in
`.env`.

---

## 6. Stop Merqo

Double click **`scripts\stop-all.bat`**, or press `Ctrl+C` in the two windows.

---

## 7. Run the tests

```powershell
scripts\run-tests.bat
```

This runs:

1. the backend unit tests (`Money`, `BusinessTime`), which need no database,
2. the backend integration tests, which use the real `merqo_test` database,
3. the frontend tests with Vitest,
4. the frontend lint.

### Running only the backend

```powershell
cd backend
mvnw.cmd clean verify
```

### Running only the frontend

```powershell
cd frontend
npm install
npm run test
npm run lint
npm run build
```

---

## 8. Useful URLs while developing

| URL | What it shows |
| --- | --- |
| <http://localhost:5173> | The frontend home page with the health report |
| <http://localhost:8080/api/v1/health> | The backend health report as JSON |
| <http://localhost:8080/api/v1/health/ping> | The trivial ping the uptime monitor calls |
| <http://localhost:8080/swagger-ui.html> | The interactive API documentation |
| <http://localhost:8080/v3/api-docs> | The OpenAPI description as JSON |
| <http://localhost:8080/actuator/health> | The Spring Boot health endpoint |

---

## 9. Common problems

See [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) for the full list. The
three most common ones:

**"psql not found"** - PostgreSQL is not on the PATH. Restart the terminal, or
add `C:\Program Files\PostgreSQL\17\bin` to the PATH, or set `PGBIN` in the
script.

**"Connection refused" on the health page** - the backend is not running or
PostgreSQL is not running. Check `Get-Service postgresql*` and look at the
`Merqo backend` window for the error.

**Port already in use** - another program uses 8080 or 5173. Run
`scripts\stop-all.bat`, or change `PORT` in `.env` and the port in
`frontend/vite.config.ts`.

---

## 10. Next step

Sign in on <http://localhost:5173>. With `DEMO_MODE=true` the login page
shows the demo accounts; one click signs you in. The demo locations and the
demo accounts are written by the backend itself at every start, so there is no
separate seed step.

Then follow [DEPLOY.md](DEPLOY.md) to put Merqo online.
