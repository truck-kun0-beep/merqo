# DEPLOY.md - Putting Merqo online for free

**Goal:** one public URL that the instructor can open in a browser and use
immediately, with nothing to install.

**Time needed:** about 45 minutes the first time, most of it waiting for
builds.

**Cost:** free. No payment card is required for any step below. If any page
asks for a card, **stop and report it** (see "If a card is asked for" at the
bottom).

---

## What you are building

| Part | Host | Result |
| --- | --- | --- |
| Database | Supabase (free tier) | PostgreSQL, 500 MB, no automatic backups |
| Backend | Render (free web service) | `https://merqo-api.onrender.com` |
| Frontend | Cloudflare Pages (free tier) | `https://merqo.pages.dev` |
| Proxy | Cloudflare Pages Function | makes `/api` same-origin |

The frontend is the only URL the instructor needs. It proxies the API, so
there is one link to share.

---

## Step 1 - Push the code to GitHub

1. Open <https://github.com/new>.
2. **Repository name:** `merqo`. **Visibility:** private is fine.
3. Click **Create repository**.
4. In PowerShell, from the `merqo` folder:

```powershell
git remote add origin https://github.com/<your-username>/merqo.git
git push -u origin main
```

If Git asks for a password, use a **personal access token**, not your account
password: <https://github.com/settings/tokens> → *Fine-grained tokens* →
*Generate new token* → give it `repo` access.

> **Check before you push:** `git status` must not list `.env`. Only
> `.env.example` belongs in the repository.

---

## Step 2 - Create the Supabase database

1. Open <https://supabase.com> and click **Start your project**.
2. Fill in the form:
   - **Organization:** anything, this is a student project.
   - **Project name:** `merqo`.
   - **Database password:** choose a strong password and **write it down**.
     You need it twice below.
   - **Region:** pick the one closest to Bangladesh, for example
     `Mumbai (ap-south-1)` or `Singapore`.
   - **Plan:** Free.
3. Click **Create new project** and wait about two minutes.
4. When the dashboard opens, the **password** you typed is the database
   password. The **user** is `postgres`.
5. Go to **Connect** (the top right of the dashboard) → **Session Pooler**.

   Copy the connection string. It looks like this:

   ```
   postgresql://postgres.PROJECT_REF:PASSWORD@aws-0-REGION.pooler.supabase.com:5432/postgres
   ```

   > **Use the Session Pooler, not the Transaction Pooler and not the direct
   > connection.** The session pooler supports IPv4 and keeps one backend
   > connection for the whole session, which is what prepared statements and
   > row locking need. The transaction pooler would break the stock locking
   > that this project depends on.

6. Go to **Settings → Database → Connection string** and note down the same
   three values: host, user, password.

### The JDBC string

The JDBC driver wants a `jdbc:` prefix and the password URL-encoded. Build it
like this:

```
jdbc:postgresql://aws-0-REGION.pooler.supabase.com:5432/postgres
```

- **Host:** `aws-0-REGION.pooler.supabase.com`
- **Port:** `5432`
- **Database:** `postgres`
- **User:** `postgres.PROJECT_REF`
- **Password:** the password you chose

---

## Step 3 - Create the Render backend

1. Open <https://render.com> and click **Get Started**, then sign in with
   GitHub.
2. Click **New → Web Service**.
3. **Connect a repository:** pick your `merqo` repository.
4. Fill in the form:
   - **Name:** `merqo-api`
   - **Region:** the same region as the database, or the nearest to it.
   - **Branch:** `main`
   - **Language / Runtime:** **Docker**
   - **Root Directory:** *(leave empty)*
   - **Dockerfile Path:** `./Dockerfile`
   - **Docker Context:** `.`
   - **Instance Type / Plan:** **Free**
5. Open **Environment Variables** and add:

   | Key | Value |
   | --- | --- |
   | `SPRING_PROFILES_ACTIVE` | `prod` |
   | `DATABASE_URL` | `jdbc:postgresql://aws-0-REGION.pooler.supabase.com:5432/postgres` |
   | `DB_USER` | `postgres.PROJECT_REF` |
   | `DB_PASSWORD` | your Supabase database password |
   | `CORS_ORIGINS` | `https://merqo.pages.dev` |
   | `DEMO_MODE` | `true` |
   | `COURIER_MODE` | `mock` |
   | `PAYMENT_MODE` | `mock` |
   | `WEBSITE_MODE` | `mock` |
   | `LLM_MODE` | `mock` |

   > `CORS_ORIGINS` needs the final Pages URL. If you do not have it yet,
   > leave the value empty for the first deploy and add it in step 5. The
   > browser uses the Pages proxy, so CORS is only needed for direct API
   > debugging.

6. Open **Advanced** and set:
   - **Health Check Path:** `/api/v1/health/ping`
7. Click **Create Web Service**.

Render builds the Docker image. The first build takes three to six minutes.

### What to watch in the log

You should see something like:

```
Successfully applied 1 migration to schema "public", now at version v1
Started MerqoApplication in 6.2 seconds
Tomcat started on port 10000
```

### After the deploy

1. Open the Render **Logs** tab and wait for the green *Live* badge.
2. Open the **Shell** tab and run:

   ```
   curl -s https://merqo-api.onrender.com/api/v1/health/ping
   ```

   You should see `{"status":"UP","pong":true,...}`.

   > The **first** request after a sleep can take up to a minute while the
   > instance wakes up. The frontend handles this with the waking screen.

---

## Step 4 - Create the Cloudflare Pages frontend

1. Open <https://dash.cloudflare.com> and sign up for a free account.
2. In the left menu click **Workers & Pages** → **Create application** →
   **Pages** → **Connect to Git**.
3. Authorise Cloudflare to read your GitHub repositories and pick `merqo`.
4. Fill in the build settings:
   - **Project name:** `merqo` → the URL will be
     `https://merqo.pages.dev`
   - **Production branch:** `main`
   - **Framework preset:** `None`
   - **Build command:** `npm run build`
   - **Build output directory:** `frontend/dist`
   - **Root directory:** `frontend`
5. Click **Save and Deploy**.

   > **Important:** the build command runs from the **root directory** setting.
   > With `frontend` as the root directory, `npm run build` runs inside
   > `frontend/`, where `package.json` lives. If you set the root directory to
   > the repository root instead, use `cd frontend && npm run build` and the
   > output directory `frontend/dist`.

   The Cloudflare build image provides Node.js. The project needs Node 20 or
   newer; the current image is newer than that.

### Add the backend URL as an environment variable

1. Stay in the Cloudflare project → **Settings** → **Environment variables**.
2. Add for **Production** (and **Preview** if you want previews to work):

   | Key | Value |
   | --- | --- |
   | `API_BASE_URL` | `https://merqo-api.onrender.com` |

3. Click **Save**.
4. Go to **Deployments** → **Trigger deploy** (or push an empty commit) so the
   new variable is picked up.

> **This variable is what makes the same-origin proxy work.** Without it the
> Pages Function answers `500 PROXY_NOT_CONFIGURED`.

---

## Step 5 - Point the backend CORS at the frontend

1. Go back to **Render** → your `merqo-api` service → **Environment**.
2. Set `CORS_ORIGINS` to `https://merqo.pages.dev`.
3. Click **Save Changes**. Render restarts the service automatically.

---

## Step 6 - Set up the free uptime monitor

The free Render instance sleeps after fifteen idle minutes and needs about a
minute to wake up. A monitor that pings it every ten minutes keeps it awake,
which means the instructor never waits.

1. Open <https://uptimerobot.com> and create a free account.
2. Click **Add New Monitor**.
3. Fill in:
   - **Monitor Type:** `HTTP(s)`
   - **Friendly Name:** `Merqo backend`
   - **URL (or IP):** `https://merqo-api.onrender.com/api/v1/health/ping`
   - **Monitoring Interval:** `10 minutes`
   - **Timeout:** `60 seconds` (the first request after a sleep is slow)
4. Click **Create Monitor**.

Any free uptime service works the same way. The only requirement is that it
calls the URL above at least every ten minutes.

---

## Step 7 - Check the whole thing

Open **https://merqo.pages.dev** in a private window.

You should see:

- the Merqo header,
- the **Overview** page,
- a health panel that says **Application: UP** and **Database: UP**.

Then:

1. Open the browser developer tools → **Network**.
2. Reload the page.
3. Confirm the requests go to `merqo.pages.dev/api/v1/health` and not to
   `merqo-api.onrender.com`. That proves the browser sees one origin.
4. Repeat the test in **Chrome**, **Edge**, **Firefox** and **Safari**.

### Optional: a custom domain

You can add your own domain in **Cloudflare Pages → Custom domains**. The
proxy keeps working, because the Pages Function belongs to the project.

---

## Step 8 - Backups

Supabase free has **no automatic backups**, so back up from your own machine.

1. Install PostgreSQL on your PC (it is already installed for local
   development) and find `pg_dump.exe`.
2. Set the environment variables for the hosted database:

   ```bat
   set DATABASE_URL=jdbc:postgresql://aws-0-REGION.pooler.supabase.com:5432/postgres
   set DB_USER=postgres.PROJECT_REF
   set DB_PASSWORD=your-supabase-password
   scripts\backup-db.bat
   ```

3. The dump lands in `backups\merqo-<timestamp>.dump`.

### Restoring

```bat
scripts\restore-db.bat backups\merqo-20261120-1200.dump
```

The script asks for a confirmation before it replaces anything.

---

## If a card is asked for

**Stop and report it.** Do not enter a card.

The brief says: if any step would need a card, stop and report it. Do not work
around it.

Where a card may appear:

| Host | Free tier | Where a card may be requested |
| --- | --- | --- |
| Render | free web services exist | the free *web service* creation flow sometimes shows a card prompt. The free *Postgres* always needs one, which is why this guide uses Supabase. |
| Supabase | free tier exists | the free tier does not need a card |
| Cloudflare Pages | free tier exists | does not need a card |

If Render asks for a card, note exactly which screen asked and what it said,
and report it. The fallback in that case is documented in
[docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md).

---

## Troubleshooting the deployment

See [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) for the full list. The
most common deployment problems:

| Symptom | Cause | Fix |
| --- | --- | --- |
| Render build fails at `mvn package` | the Docker build cannot reach Maven Central | check the Render build log, retry the deploy |
| Render deploys but the site returns 503 | the database is unreachable | check `DATABASE_URL`, `DB_USER`, `DB_PASSWORD`; make sure it is the **session pooler** host |
| The frontend shows "Server is waking up" forever | `API_BASE_URL` is missing or wrong in Cloudflare | set it to `https://merqo-api.onrender.com` and redeploy |
| The frontend shows a CORS error | `CORS_ORIGINS` does not match the Pages URL | set it to the exact Pages URL, without a trailing slash |
| Flyway fails on startup | the database is paused or the schema is wrong | open the Supabase dashboard, resume the project, redeploy |
| The site works, then sleeps after 15 minutes | the uptime monitor is not running | check the monitor in UptimeRobot |

---

## Updating the deployment later

Every later prompt ends with the same routine:

```powershell
git add -A
git commit -m "Prompt N: <what changed>"
git push origin main
```

- Render rebuilds the backend automatically.
- Cloudflare Pages rebuilds the frontend automatically.
- Flyway applies the new migrations during the backend startup.

Then check `https://merqo.pages.dev` again. This is part of the definition of
done for every prompt.
