# Cloudflare Pages Functions
#
# The function in /functions/api proxies every /api/* request to the backend so
# the browser only ever talks to its own origin. The backend URL comes from the
# API_BASE_URL environment variable in the Cloudflare Pages dashboard.
#
# Run `npx wrangler pages dev dist` to test the proxy locally.
#
# ## The proxy decides the address of the caller
#
# The backend reads the address of the caller from the `X-Forwarded-For` header
# and writes it into every audit row. It also counts it in the login throttle:
# twenty failures from one address give a `429`.
#
# A browser can send any `X-Forwarded-For` it likes. So the function drops
# every forwarded header the browser sent and writes the address Cloudflare saw
# (`CF-Connecting-IP`, which Cloudflare overwrites on every request) instead.
# The rule is proved by `api/proxy.test.ts`.
#
# The answers are sent with `Cache-Control: no-store`, because they carry the
# session of the person who asked.
#
# ## The free backend sleeps
#
# When the backend does not answer, the function answers with `503` and a
# problem detail. The frontend treats `502`, `503` and `504` as "the backend is
# waking up" and shows the waking screen, so those statuses never turn into a
# red error box.
