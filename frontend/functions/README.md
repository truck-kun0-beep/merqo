# Cloudflare Pages Functions
#
# The function in /functions/api proxies every /api/* request to the backend so
# the browser only ever talks to its own origin. The backend URL comes from the
# API_BASE_URL environment variable in the Cloudflare Pages dashboard.
#
# Run `npx wrangler pages dev dist` to test the proxy locally.
