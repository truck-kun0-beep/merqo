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
 * How long a single request may take before it is aborted.
 */
const REQUEST_TIMEOUT_MS = 20_000

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

  let response: Response
  try {
    response = await fetch(`${API_BASE_PATH}${path}`, {
      ...options,
      signal: controller.signal,
      credentials: 'same-origin',
      headers: {
        Accept: 'application/json',
        ...(options.body ? { 'Content-Type': 'application/json' } : {}),
        ...options.headers,
      },
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
