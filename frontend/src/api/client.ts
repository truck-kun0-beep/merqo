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
 * The status codes that mean the backend is not able to answer right now.
 *
 * The Cloudflare proxy answers with these codes when the free backend is
 * asleep, when it did not answer in time or when it answered with its own
 * gateway error. They are not business errors, so they are reported the same
 * way as a network failure: the user sees the waking screen and the request
 * retries on its own.
 */
export const GATEWAY_STATUSES = [502, 503, 504]

/**
 * Whether a status means the backend could not answer right now.
 *
 * @param status the status of the answer
 * @returns {@code true} when the backend was not able to answer
 */
export function isGatewayStatus(status: number): boolean {
  return GATEWAY_STATUSES.includes(status)
}

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
 * Whether a request body is a form.
 *
 * A form is uploaded as `multipart/form-data`, and the browser has to write the
 * boundary itself. So the content type must not be set by hand for a form: the
 * browser would send the wrong boundary and the backend would not find the
 * file.
 *
 * @param body the body of the request
 * @returns {@code true} when the body is a form
 */
export function isFormData(body: BodyInit | null | undefined): boolean {
  return typeof FormData !== 'undefined' && body instanceof FormData
}

/**
 * Builds a query string from the filters that are actually set.
 *
 * <p><b>Why the parameter is a generic and not a {@code Record}.</b> TypeScript gives an
 * `interface` no implicit index signature, so a filter that is declared as an interface
 * cannot be handed to a function that wants `Record<string, ...>`. A type alias would work,
 * but then the two ways of writing the same filter would behave differently, and only one
 * of them would compile. The generic accepts both, so the caller keeps its named fields
 * and the values are still checked here.
 *
 * @param filters the filters
 * @returns the query string, with a leading question mark or empty
 */
export function toQueryString<T extends object>(filters: T): string {
  const parts: string[] = []
  const entries = filters as Record<string, string | number | boolean | undefined>
  for (const [key, value] of Object.entries(entries)) {
    if (value === undefined || value === '') {
      continue
    }
    parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
  }
  return parts.length ? `?${parts.join('&')}` : ''
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
    ...(options.body && !isFormData(options.body) ? { 'Content-Type': 'application/json' } : {}),
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
    // A gateway status is not a business error. The backend was not able to
    // answer at all, so it is reported the same way as a network failure: the
    // user sees the waking screen and the request retries on its own.
    if (isGatewayStatus(response.status)) {
      throw new BackendUnreachableError(
        `The server answered with status ${response.status}. It may still be waking up.`,
      )
    }
    throw await toApiError(response)
  }

  if (response.status === 204) {
    return undefined as T
  }

  const text = await response.text()
  return (text ? JSON.parse(text) : undefined) as T
}

/**
 * Performs one request against the Merqo backend and reads the answer as text.
 *
 * Some answers are not JSON. The template of the import file is CSV text, for
 * example, so it cannot be parsed as JSON. The request itself is the same as
 * the one {@link apiRequest} makes: the same base path, the same CSRF token
 * and the same errors.
 *
 * @param path the path below `/api/v1`, for example `/catalog/import/template`
 * @param options the fetch options
 * @returns the body of the answer as text
 */
export async function apiText(path: string, options: RequestInit = {}): Promise<string> {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS)

  const method = options.method ?? 'GET'
  const headers: Record<string, string> = {
    Accept: 'text/plain, text/csv, */*',
    ...(options.body && !isFormData(options.body) ? { 'Content-Type': 'application/json' } : {}),
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
    if (isGatewayStatus(response.status)) {
      throw new BackendUnreachableError(
        `The server answered with status ${response.status}. It may still be waking up.`,
      )
    }
    throw await toApiError(response)
  }

  return response.text()
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
