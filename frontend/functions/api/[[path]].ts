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
 * **The headers are the security boundary of this function.** The browser is
 * not allowed to say where the request came from. It may send any
 * `X-Forwarded-For` it likes, and the backend writes that value into the audit
 * log and counts it in the login throttle. So every forwarded header the
 * browser sent is dropped here, and the address Cloudflare saw is written
 * instead. Cloudflare overwrites `CF-Connecting-IP` on every request, so the
 * value that reaches the backend is the address of the real caller and never
 * one the caller chose.
 *
 * The answers are never cached. A cached answer would be handed to the next
 * caller, and the answers carry the session of the person who asked.
 *
 * The backend URL is read from the environment variable `API_BASE_URL`, which
 * is set in the Cloudflare Pages dashboard.
 */

interface Env {
  API_BASE_URL: string
}

/** How long the backend is given to answer before the function gives up. */
const BACKEND_TIMEOUT_MS = 30_000

/** How long the browser is told to wait before it tries again. */
const RETRY_AFTER_SECONDS = 10

/**
 * The headers the browser must never be able to pass on.
 *
 * The first group belongs to a single hop and is dropped by every proxy. The
 * second group tells the backend where the request came from, and the browser
 * is not allowed to decide that. The third group is for Cloudflare and means
 * nothing to the backend.
 */
const DROPPED_REQUEST_HEADERS = [
  'connection',
  'keep-alive',
  'proxy-authenticate',
  'proxy-authorization',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade',
  'host',
  'x-forwarded-for',
  'x-forwarded-host',
  'x-forwarded-port',
  'x-forwarded-proto',
  'x-real-ip',
  'forwarded',
  'true-client-ip',
  'client-ip',
  'cf-connecting-ip',
  'cf-ipcountry',
  'cf-visitor',
  'cf-worker',
]

/** The headers that must never come back from the backend to the browser. */
const DROPPED_RESPONSE_HEADERS = [
  'connection',
  'keep-alive',
  'proxy-authenticate',
  'proxy-authorization',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade',
]

/** The status codes that mean the backend is not able to answer right now. */
const GATEWAY_STATUSES = [502, 503, 504]

export async function onRequest(context: { request: Request; env: Env }): Promise<Response> {
  const backendUrl = context.env.API_BASE_URL

  if (!backendUrl) {
    return problemResponse(
      500,
      'PROXY_NOT_CONFIGURED',
      'The API_BASE_URL environment variable is not set on this Pages project.',
      new URL(context.request.url).pathname,
      'unknown',
    )
  }

  const incomingUrl = new URL(context.request.url)
  const targetUrl = buildTargetUrl(backendUrl, incomingUrl)
  const headers = buildUpstreamHeaders(context.request, incomingUrl)
  const correlationId = headers.get('X-Correlation-Id') ?? 'unknown'

  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), BACKEND_TIMEOUT_MS)

  try {
    const upstreamResponse = await fetch(targetUrl.toString(), {
      method: context.request.method,
      headers,
      body:
        context.request.method === 'GET' || context.request.method === 'HEAD'
          ? undefined
          : context.request.body,
      redirect: 'manual',
      signal: controller.signal,
    })

    if (GATEWAY_STATUSES.includes(upstreamResponse.status)) {
      // The backend is asleep or busy and answered with its own gateway error.
      // The answer is replaced with a problem detail, so the browser always
      // gets the same shape and can show the waking screen.
      await upstreamResponse.body?.cancel()
      return problemResponse(
        upstreamResponse.status,
        'BACKEND_UNAVAILABLE',
        'The backend is not available right now. It may still be waking up.',
        incomingUrl.pathname,
        correlationId,
      )
    }

    const responseHeaders = buildResponseHeaders(upstreamResponse.headers)

    return new Response(upstreamResponse.body, {
      status: upstreamResponse.status,
      statusText: upstreamResponse.statusText,
      headers: responseHeaders,
    })
  } catch (error) {
    const isTimeout = error instanceof Error && error.name === 'AbortError'
    return problemResponse(
      503,
      isTimeout ? 'BACKEND_TIMEOUT' : 'BACKEND_UNREACHABLE',
      isTimeout
        ? 'The backend did not answer in time. It may still be waking up.'
        : 'The backend could not be reached. It may still be waking up.',
      incomingUrl.pathname,
      correlationId,
    )
  } finally {
    clearTimeout(timeout)
  }
}

/**
 * Builds the address of the backend.
 *
 * The host comes from the configured backend and never from the request, so
 * the function cannot be used to reach another host. The path of the request
 * is appended to the path of the backend, so a backend that lives under a
 * prefix keeps it.
 *
 * @param backendUrl the configured backend URL
 * @param incomingUrl the URL of the browser request
 * @returns the address of the backend
 */
function buildTargetUrl(backendUrl: string, incomingUrl: URL): URL {
  const backend = new URL(backendUrl)
  const basePath = backend.pathname.replace(/\/+$/, '')
  const target = new URL(backend.origin)
  target.pathname = `${basePath}${incomingUrl.pathname}`
  target.search = incomingUrl.search
  return target
}

/**
 * Builds the headers of the request to the backend.
 *
 * Every header the browser sent is kept except the ones that would let the
 * browser lie about the origin of the request. The address Cloudflare saw
 * replaces the one the browser sent.
 *
 * @param request the request of the browser
 * @param incomingUrl the URL of the browser request
 * @returns the headers of the request to the backend
 */
function buildUpstreamHeaders(request: Request, incomingUrl: URL): Headers {
  const headers = new Headers(request.headers)

  for (const name of DROPPED_REQUEST_HEADERS) {
    headers.delete(name)
  }

  headers.set('x-forwarded-host', incomingUrl.host)
  headers.set('x-forwarded-proto', incomingUrl.protocol.replace(':', ''))

  // Cloudflare overwrites this header on every request, so it holds the
  // address of the real caller. When it is missing the header is left out, so
  // the backend falls back to the address of the socket.
  const clientIp = request.headers.get('cf-connecting-ip')?.trim()
  if (clientIp) {
    headers.set('x-forwarded-for', clientIp)
  }

  return headers
}

/**
 * Builds the headers of the answer to the browser.
 *
 * The answer is never stored by the browser and never by the edge, because it
 * carries the session of the person who asked.
 *
 * @param upstreamHeaders the headers of the answer of the backend
 * @returns the headers of the answer to the browser
 */
function buildResponseHeaders(upstreamHeaders: Headers): Headers {
  const headers = new Headers()

  // Every Set-Cookie header is copied on its own. The answer of a sign in
  // carries two of them: the session cookie and the CSRF cookie. If they were
  // joined into one header, the browser would keep only the first one and
  // every request that changes something would be refused with a 403.
  const setCookies = upstreamHeaders.getSetCookie()
  for (const cookie of setCookies) {
    headers.append('Set-Cookie', cookie)
  }

  // Every other header is copied as it is.
  upstreamHeaders.forEach((value, name) => {
    const lower = name.toLowerCase()
    if (lower === 'set-cookie') {
      return
    }
    if (DROPPED_RESPONSE_HEADERS.includes(lower)) {
      return
    }
    headers.append(name, value)
  })

  headers.set('cache-control', 'no-store')
  // The browser must be able to read the correlation id for support requests.
  headers.set('Access-Control-Expose-Headers', 'X-Correlation-Id, Content-Type, Location')

  return headers
}

/**
 * Builds a problem detail.
 *
 * @param status the status of the answer
 * @param errorCode the stable code of the problem
 * @param detail the sentence the reader sees
 * @param instance the path of the request
 * @param correlationId the id that ties the answer to the logs
 * @returns the answer
 */
function problemResponse(
  status: number,
  errorCode: string,
  detail: string,
  instance: string,
  correlationId: string,
): Response {
  return new Response(
    JSON.stringify({
      type: 'about:blank',
      title: 'Backend Unavailable',
      status,
      detail,
      instance,
      errorCode,
      correlationId,
      timestamp: new Date().toISOString(),
      fieldErrors: {},
      errors: [],
    }),
    {
      status,
      headers: {
        'Content-Type': 'application/problem+json',
        'Cache-Control': 'no-store',
        'Retry-After': String(RETRY_AFTER_SECONDS),
      },
    },
  )
}
