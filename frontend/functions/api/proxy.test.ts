// @vitest-environment node
import { describe, expect, it, vi } from 'vitest'
import { onRequest } from './[[path]]'

/**
 * The environment the function runs in.
 */
const ENV = { API_BASE_URL: 'https://merqo-api.onrender.com' }

/**
 * Builds the request of the browser.
 *
 * @param headers the headers of the request
 * @param method the method of the request
 * @returns the request
 */
function browserRequest(headers: Record<string, string> = {}, method = 'GET'): Request {
  return new Request('https://merqo.pages.dev/api/v1/auth/login?x=1', {
    method,
    headers,
  })
}

/**
 * Runs the function with a backend double.
 *
 * @param upstream the answer of the backend
 * @param request the request of the browser
 * @returns the answer of the function and the request it sent to the backend
 */
async function run(
  upstream: { status?: number; headers?: Record<string, string> } | Error,
  request: Request = browserRequest(),
): Promise<{ response: Response; upstreamRequest: Request | undefined }> {
  const upstreamRequest = { value: undefined as Request | undefined }

  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    upstreamRequest.value = new Request(String(input), init)
    if (upstream instanceof Error) {
      throw upstream
    }
    return new Response(JSON.stringify({ status: 'UP' }), {
      status: upstream.status ?? 200,
      headers: { 'Content-Type': 'application/json', ...(upstream.headers ?? {}) },
    })
  })

  vi.stubGlobal('fetch', fetchMock)

  try {
    const response = await onRequest({ request, env: ENV })
    return { response, upstreamRequest: upstreamRequest.value }
  } finally {
    vi.unstubAllGlobals()
  }
}

describe('the Cloudflare proxy', () => {
  it('drops the forwarded address the browser sent and writes the real one', async () => {
    // The browser must not be able to say where the request came from. The
    // backend writes that address into the audit log and counts it in the
    // login throttle, so a caller could otherwise lock every account.
    const { upstreamRequest } = await run(
      { status: 200 },
      browserRequest({
        'x-forwarded-for': '1.2.3.4',
        'x-real-ip': '5.6.7.8',
        forwarded: 'for=9.10.11.12',
        'cf-connecting-ip': '203.0.113.7',
      }),
    )

    expect(upstreamRequest?.headers.get('x-forwarded-for')).toBe('203.0.113.7')
    expect(upstreamRequest?.headers.get('x-real-ip')).toBeNull()
    expect(upstreamRequest?.headers.get('forwarded')).toBeNull()
  })

  it('leaves the forwarded address out when Cloudflare did not send one', async () => {
    // Without the header the backend falls back to the address of the socket,
    // which is better than a value the caller chose.
    const { upstreamRequest } = await run(
      { status: 200 },
      browserRequest({ 'x-forwarded-for': '1.2.3.4' }),
    )

    expect(upstreamRequest?.headers.get('x-forwarded-for')).toBeNull()
  })

  it('sends the path and the query to the configured backend', async () => {
    const { upstreamRequest } = await run({ status: 200 })

    expect(upstreamRequest?.url).toBe('https://merqo-api.onrender.com/api/v1/auth/login?x=1')
  })

  it('never lets the answer be stored', async () => {
    const { response } = await run({ status: 200 })

    expect(response.headers.get('cache-control')).toBe('no-store')
  })

  it('turns a gateway status of the backend into a problem detail', async () => {
    // A 502, 503 or 504 means the backend is asleep or busy. The browser must
    // get the same problem shape, so it can show the waking screen.
    const { response } = await run({ status: 503 })

    expect(response.status).toBe(503)
    expect(response.headers.get('content-type')).toBe('application/problem+json')
    expect(response.headers.get('retry-after')).toBe('10')

    const body = await response.json()
    expect(body.errorCode).toBe('BACKEND_UNAVAILABLE')
  })

  it('answers with a problem detail when the backend cannot be reached', async () => {
    const { response } = await run(new TypeError('Failed to fetch'))

    expect(response.status).toBe(503)

    const body = await response.json()
    expect(body.errorCode).toBe('BACKEND_UNREACHABLE')
  })

  it('keeps a business answer unchanged', async () => {
    const { response } = await run({ status: 401 })

    expect(response.status).toBe(401)
  })

  it('says so when the backend URL is not set', async () => {
    vi.stubGlobal('fetch', vi.fn())
    try {
      const response = await onRequest({
        request: browserRequest(),
        env: { API_BASE_URL: '' },
      })

      expect(response.status).toBe(500)
      const body = await response.json()
      expect(body.errorCode).toBe('PROXY_NOT_CONFIGURED')
    } finally {
      vi.unstubAllGlobals()
    }
  })

  it('keeps every Set-Cookie header of the answer on its own', async () => {
    // The answer of a sign in carries two cookies: the session cookie and the
    // CSRF cookie. If they were joined into one header, the browser would keep
    // only the first one and every request that changes something would be
    // refused with a 403. This is the bug that broke the live site.
    const upstream = new Headers()
    upstream.append('Set-Cookie', 'MERQO_SESSION=abc; Path=/; HttpOnly; SameSite=Lax')
    upstream.append('Set-Cookie', 'XSRF-TOKEN=def; Path=/')

    const fetchMock = vi.fn(async () => new Response('{}', { status: 200, headers: upstream }))
    vi.stubGlobal('fetch', fetchMock)

    try {
      const response = await onRequest({ request: browserRequest(), env: ENV })

      const cookies = response.headers.getSetCookie()
      expect(cookies).toHaveLength(2)
      expect(cookies[0]).toContain('MERQO_SESSION=abc')
      expect(cookies[1]).toContain('XSRF-TOKEN=def')
    } finally {
      vi.unstubAllGlobals()
    }
  })

  it('keeps the cookies of the answer when the backend sends only one', async () => {
    const upstream = new Headers()
    upstream.append('Set-Cookie', 'MERQO_SESSION=abc; Path=/; HttpOnly; SameSite=Lax')

    const fetchMock = vi.fn(async () => new Response('{}', { status: 200, headers: upstream }))
    vi.stubGlobal('fetch', fetchMock)

    try {
      const response = await onRequest({ request: browserRequest(), env: ENV })

      const cookies = response.headers.getSetCookie()
      expect(cookies).toHaveLength(1)
      expect(cookies[0]).toContain('MERQO_SESSION=abc')
    } finally {
      vi.unstubAllGlobals()
    }
  })
})
