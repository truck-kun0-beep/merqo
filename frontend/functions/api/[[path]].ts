/**
 * The Cloudflare Pages Function that makes the API same-origin.
 *
 * The browser on Cloudflare Pages and the backend on Render live on two
 * different hosts. If the browser called the backend directly, the session
 * cookie would be a third party cookie and would be blocked by Chrome, Edge,
 * Firefox and Safari in their default settings.
 *
 * This function forwards every `/api/*` request to the backend and returns the
 * answer unchanged. Because the browser only ever talks to its own origin,
 * the session cookie stays a first party cookie and login works in every
 * browser, also with third party cookies blocked.
 *
 * The backend URL is read from the environment variable `API_BASE_URL`, which
 * is set in the Cloudflare Pages dashboard.
 */

interface Env {
  API_BASE_URL: string
}

/** How long the backend is given to answer before the function gives up. */
const BACKEND_TIMEOUT_MS = 30_000

export async function onRequest(context: { request: Request; env: Env }): Promise<Response> {
  const backendUrl = context.env.API_BASE_URL

  if (!backendUrl) {
    return new Response(
      JSON.stringify({
        type: 'about:blank',
        title: 'Service Misconfigured',
        status: 500,
        detail: 'The API_BASE_URL environment variable is not set on this Pages project.',
        instance: new URL(context.request.url).pathname,
        errorCode: 'PROXY_NOT_CONFIGURED',
        correlationId: 'unknown',
        timestamp: new Date().toISOString(),
        fieldErrors: {},
        errors: [],
      }),
      { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
    )
  }

  const incomingUrl = new URL(context.request.url)
  const targetUrl = new URL(incomingUrl.pathname + incomingUrl.search, backendUrl)

  const headers = new Headers(context.request.headers)
  headers.delete('host')
  headers.delete('cf-connecting-ip')
  headers.set('x-forwarded-host', incomingUrl.host)
  headers.set('x-forwarded-proto', incomingUrl.protocol.replace(':', ''))

  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), BACKEND_TIMEOUT_MS)

  try {
    const upstreamResponse = await fetch(targetUrl.toString(), {
      method: context.request.method,
      headers,
      body: context.request.method === 'GET' || context.request.method === 'HEAD' ? undefined : context.request.body,
      redirect: 'manual',
      signal: controller.signal,
    })

    const responseHeaders = new Headers(upstreamResponse.headers)
    // The browser must be able to read the correlation id for support requests.
    responseHeaders.set(
      'Access-Control-Expose-Headers',
      'X-Correlation-Id, Content-Type, Location',
    )

    return new Response(upstreamResponse.body, {
      status: upstreamResponse.status,
      statusText: upstreamResponse.statusText,
      headers: responseHeaders,
    })
  } catch (error) {
    const isTimeout = error instanceof Error && error.name === 'AbortError'
    return new Response(
      JSON.stringify({
        type: 'about:blank',
        title: 'Backend Unavailable',
        status: 503,
        detail: isTimeout
          ? 'The backend did not answer in time. It may still be waking up.'
          : 'The backend could not be reached. It may still be waking up.',
        instance: incomingUrl.pathname,
        errorCode: isTimeout ? 'BACKEND_TIMEOUT' : 'BACKEND_UNREACHABLE',
        correlationId: headers.get('X-Correlation-Id') ?? 'unknown',
        timestamp: new Date().toISOString(),
        fieldErrors: {},
        errors: [],
      }),
      {
        status: 503,
        headers: {
          'Content-Type': 'application/problem+json',
          'Retry-After': '10',
        },
      },
    )
  } finally {
    clearTimeout(timeout)
  }
}
