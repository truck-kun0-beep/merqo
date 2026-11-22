import { ApiError, BackendUnreachableError, type ProblemDetail } from './types'

/**
 * The base path of every backend call.
 *
 * It is always a relative path. The browser therefore only ever talks to its
 * own origin, which is what keeps the session cookie working when third party
 * cookies are blocked.
 */
export const API_BASE_PATH = '/api/v1'

/**
 * The name of the cookie that holds the CSRF token.
 *
 * The backend writes it with `CookieCsrfTokenRepository`, so the token is
 * readable by JavaScript on purpose. It is not a secret: it only proves that
 * the request came from this site and not from another one.
 */
export const CSRF_COOKIE_NAME = 'XSRF-TOKEN'

/**
 * The header the CSRF token has to be sent back in.
 */
export const CSRF_HEADER_NAME = 'X-XSRF-TOKEN'

/**
 * How long a single request may take before it is aborted.
 */
const REQUEST_TIMEOUT_MS = 20_000

/**
 * Reads the CSRF token from the cookie.
 *
 * The browser is the only place that can read the cookie, so this is the one
 * function that knows about cookies. The token is read straight before a
 * request that changes something, which is the only time it is needed.
 *
 * @returns the token, or an empty string when the cookie is not there yet
 */
export function readCsrfToken(): string {
  if (typeof document === 'undefined') {
    return ''
  }
  const prefix = `${CSRF_COOKIE_NAME}=`
  const match = document.cookie
    .split(';')
    .map((part) => part.trim())
    .find((part) => part.startsWith(prefix))
  if (!match) {
    return ''
  }
  return decodeURIComponent(match.slice(prefix.length))
}

/**
 * Whether a request changes something and therefore needs the CSRF token.
 *
 * @param method the HTTP method
 * @returns {@code true} when the token is required
 */
export function requiresCsrfToken(method: string): boolean {
  const upper = method.toUpperCase()
  return upper !== 'GET' && upper !== 'HEAD' && upper !== 'OPTIONS' && upper !== 'TRACE'
}

/**
 * Performs one request against the Merqo backend.
 *
 * The backend always answers failures with an RFC 7807 problem detail, so this
 * function turns any error response into an {@link ApiError} with a stable
 * error code.
 *
 * @param path the path below `/api/v1`, for example `/health`
 * @param options the fetch options
 * @returns the parsed response body
 */
export async function apiRequest<T>(path: string, options: RequestInit = {}): Promise<T> {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS)

  const method = options.method ?? 'GET'
  const headers: Record<string, string> = {
    Accept: 'application/json',
    ...(options.body ? { 'Content-Type': 'application/json' } : {}),
    ...(options.headers as Record<string, string> | undefined),
  }

  if (requiresCsrfToken(method)) {
    const token = readCsrfToken()
    if (token) {
      headers[CSRF_HEADER_NAME] = token
    }
  }

  let response: Response
  try {
    response = await fetch(`${API_BASE_PATH}${path}`, {
      ...options,
      method,
      signal: controller.signal,
      credentials: 'same-origin',
      headers,
    })
  } catch (error) {
    throw new BackendUnreachableError(
      error instanceof Error ? error.message : 'The server is not reachable',
    )
  } finally {
    clearTimeout(timeout)
  }

  if (!response.ok) {
    throw await toApiError(response)
  }

  if (response.status === 204) {
    return undefined as T
  }

  const text = await response.text()
  return (text ? JSON.parse(text) : undefined) as T
}

/**
 * Reads the problem detail from an error response.
 *
 * @param response the failed response
 * @returns the error carrying the stable backend code
 */
async function toApiError(response: Response): Promise<ApiError> {
  let problem: ProblemDetail | undefined
  try {
    problem = (await response.json()) as ProblemDetail
  } catch {
    problem = undefined
  }

  if (!problem) {
    problem = {
      type: 'about:blank',
      title: response.statusText || 'Request failed',
      status: response.status,
      detail: `The server answered with status ${response.status}.`,
      instance: response.url,
      errorCode: 'HTTP_' + response.status,
      correlationId: response.headers.get('X-Correlation-Id') ?? 'unknown',
      timestamp: new Date().toISOString(),
      fieldErrors: {},
      errors: [],
    }
  }

  return new ApiError(problem)
}
