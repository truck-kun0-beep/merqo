# DEPLOY-CHECKLIST.md - the short deployment checklist

This is the condensed version of [DEPLOY.md](../DEPLOY.md). It is written to be
followed **alone**, one box at a time, without opening the long guide.

Every step has a "paste this output if stuck" line. Copy the output and send it
to the assistant with the step number.

**Rules for this checklist**

- Never enter a payment card. If any screen asks for a card, **stop** and
  report the screen and the wording. See "Card requested" at the bottom.
- Every step is a click in a web dashboard. No server administration.
- Mark a box only when you have **seen** the expected output.

**Your values** (fill this in once, so the later steps are copy-paste):

| What | Value |
| --- | --- |
| GitHub user | `________________` |
| GitHub repo | `merqo` |
| Supabase project ref | `________________` |
| Supabase region | `Singapore` |
| Supabase DB password | `________________` (keep this out of git) |
| Render service name | `merqo-api` |
| Render region | `Singapore` |
| Pages project | `merqo` |
| Pages URL | `https://merqo.pages.dev` |
| Backend URL | `https://merqo-api.onrender.com` |

---

## Step 1 - Push the code

- [ ] The repository exists on GitHub and the name is `merqo`.
- [ ] PowerShell is open **in** the `merqo` folder.

```powershell
git status
git push origin main
```

> Paste this output if stuck:
> `git status` and the full text of the push (include every red line).

- [ ] `git status` does **not** list `.env`. Only `.env.example` may be in the
      repository.

---

## Step 2 - Create the database (Supabase)

- [ ] <https://supabase.com> → **Start your project**.
- [ ] Project name `merqo`, region **Singapore**, plan **Free**.
- [ ] Database password chosen and written down.
- [ ] **Connect** → **Session Pooler** → copy the connection string.

> Paste this output if stuck: the exact connection string with the password
> replaced by `PASSWORD`.

- [ ] Build the JDBC string and write it in the table below:

| Key | Value |
| --- | --- |
| `DATABASE_URL` | `jdbc:postgresql://aws-0-REGION.pooler.supabase.com:5432/postgres` |
| `DB_USER` | `postgres.PROJECT_REF` |
| `DB_PASSWORD` | your Supabase database password |

> Paste this output if stuck: the three lines above with the password hidden.

---

## Step 3 - Create the backend (Render)

- [ ] <https://render.com> → **New → Web Service** → pick the `merqo` repo.
- [ ] Name `merqo-api`, region **Singapore**, branch `main`.
- [ ] Runtime **Docker**, Dockerfile `./Dockerfile`, context `.`.
- [ ] Plan **Free**.

- [ ] Environment Variables (Render → your service → **Environment**):

| Key | Value |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `DATABASE_URL` | `jdbc:postgresql://aws-0-REGION.pooler.supabase.com:5432/postgres` |
| `DB_USER` | `postgres.PROJECT_REF` |
| `DB_PASSWORD` | your Supabase database password |
| `CORS_ORIGINS` | `https://merqo.pages.dev` |
| `DEMO_MODE` | `true` |
| `INITIAL_OWNER_USERNAME` | `owner` |
| `INITIAL_OWNER_PASSWORD` | a strong password, 10+ characters |
| `SESSION_IDLE_TIMEOUT_MINUTES` | `480` |
| `SESSION_ABSOLUTE_TIMEOUT_MINUTES` | `720` |
| `COURIER_MODE` | `mock` |
| `PAYMENT_MODE` | `mock` |
| `WEBSITE_MODE` | `mock` |
| `LLM_MODE` | `mock` |

> `INITIAL_OWNER_*` is only used when `DEMO_MODE=false`. With demo mode on,
> the demo owner is created by the seed and needs no password.

- [ ] **Advanced → Health Check Path** = `/api/v1/health/ping`.
- [ ] Click **Create Web Service** and wait for the build (3-6 minutes).

> Paste this output if stuck: the last 40 lines of the **Build** log and the
> last 40 lines of the **Live** log.

- [ ] Render → **Logs** shows the green *Live* badge.
- [ ] Render → **Shell** → run:

```bash
curl -s https://merqo-api.onrender.com/api/v1/health/ping
```

- [ ] The answer contains `"pong":true`.

> Paste this output if stuck: the whole JSON answer and the Render log tail.

---

## Step 4 - Create the frontend (Cloudflare Pages)

- [ ] <https://dash.cloudflare.com> → **Workers & Pages** → **Create
      application** → **Pages** → **Connect to Git**.
- [ ] Pick the `merqo` repository.
- [ ] Project name `merqo`, production branch `main`.
- [ ] Framework preset `None`.
- [ ] Build command `npm run build`.
- [ ] Build output directory `frontend/dist`.
- [ ] Root directory `frontend`.
- [ ] Click **Save and Deploy**.

> Paste this output if stuck: the whole build log from the Cloudflare
> dashboard.

- [ ] Pages → **Settings** → **Environment variables** → **Production** → add:

| Key | Value |
| --- | --- |
| `API_BASE_URL` | `https://merqo-api.onrender.com` |

- [ ] Click **Save**, then **Deployments → Trigger deploy**.

> Paste this output if stuck: a screenshot text of the environment variable
> table (values hidden is fine, but the **keys** must be exact).

---

## Step 5 - Check that the site is up

- [ ] Open `https://merqo.pages.dev` in a private window.
- [ ] The page shows the Merqo header and a health panel with **UP**.
- [ ] DevTools → **Network** → reload → the requests go to
      `merqo.pages.dev/api/...` and **not** to `merqo-api.onrender.com`.

> Paste this output if stuck: the list of request URLs from the Network tab.

---

## Step 6 - The live login check (Prompt 1)

Do this **after** the Prompt 1 backend is deployed.

1. Open `https://merqo.pages.dev`.
2. Click the **Demo Owner** button in the demo accounts panel.
3. You are logged in and see the application shell.
4. Press **F5**. You are still logged in.
5. DevTools → **Application → Cookies → merqo.pages.dev**. The `MERQO_SESSION`
   cookie has: **HttpOnly** ticked, **Secure** ticked, **SameSite = Lax**, and
   **no Domain** (host-only).
6. Click **Log out**. You are back on the login page.
7. Click **Demo Manager (two branches)**.
8. Open **Locations**. You see **Dhanmondi** and **Mirpur**. You do **not** see
   **Uttara**.
9. Open DevTools → **Network** and try to open
   `/api/v1/locations/<uttara-id>` directly. The answer is **404**.

> Paste this output if stuck: the browser console output, the Network tab
> entries for the login request (status + the `Set-Cookie` header), and the
> response body of the failed Uttara request.

---

## Step 7 - Free uptime monitor

- [ ] <https://uptimerobot.com> → add an HTTP monitor:
      `https://merqo-api.onrender.com/api/v1/health/ping`, interval 10
      minutes, timeout 60 seconds.

> Paste this output if stuck: the monitor page as text.

---

## Step 8 - Backups

- [ ] `scripts\backup-db.bat` runs from the repository root with the hosted
      database variables set.

> Paste this output if stuck: the whole console output of the script.

---

## Card requested

**Stop. Do not enter a card. Report it.** Send:

1. Which host (Render, Supabase, Cloudflare, UptimeRobot).
2. Which screen you were on.
3. The exact wording of the card prompt.

The fallback plan for a Render card prompt is in
[docs/TROUBLESHOOTING.md](TROUBLESHOOTING.md).

---

## Common symptoms

| Symptom | First thing to check |
| --- | --- |
| "Server is waking up" forever | `API_BASE_URL` in Cloudflare, then Trigger deploy |
| Login returns 404 | `DEMO_MODE=true` in Render, then redeploy |
| Login returns 403 | the CSRF cookie is missing; hard reload with Ctrl+F5 |
| CORS error in the console | `CORS_ORIGINS` must be exactly `https://merqo.pages.dev` |
| Backend 503 on first request | normal: the free instance is waking up, wait 60 s |
| Flyway error in the Render log | the database is paused, resume it in Supabase |
| Owner cannot log in | `INITIAL_OWNER_PASSWORD` missing or too short; see TROUBLESHOOTING.md |
