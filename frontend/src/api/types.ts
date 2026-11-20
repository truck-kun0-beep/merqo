/**
 * The shape of a backend error.
 *
 * The backend answers every failure with an RFC 7807 problem detail, so the
 * frontend only needs one error type. This mirrors
 * `com.merqo.common.error.ProblemDetail` on the Java side.
 */
export interface ProblemDetail {
  type: string
  title: string
  status: number
  detail: string
  instance: string
  errorCode: string
  correlationId: string
  timestamp: string
  fieldErrors: Record<string, string>
  errors: string[]
}

/**
 * The health report returned by `GET /api/v1/health`.
 */
export interface HealthReport {
  status: string
  service: string
  time: string
  businessTime: string
  database: {
    status: string
    probe?: number
    reason?: string
  }
}

/**
 * The payload returned by `GET /api/v1/health/ping`.
 */
export interface PingReport {
  status: string
  pong: boolean
  time: string
  database: {
    status: string
    probe?: number
    reason?: string
  }
}

/**
 * An error thrown by the API client.
 *
 * It always carries the backend's stable error code so the UI can react to a
 * specific problem instead of parsing messages.
 */
export class ApiError extends Error {
  readonly status: number
  readonly errorCode: string
  readonly correlationId: string
  readonly fieldErrors: Record<string, string>

  constructor(problem: ProblemDetail) {
    super(problem.detail || problem.title || 'Request failed')
    this.name = 'ApiError'
    this.status = problem.status
    this.errorCode = problem.errorCode
    this.correlationId = problem.correlationId
    this.fieldErrors = problem.fieldErrors ?? {}
  }
}

/**
 * Raised when the backend could not be reached at all.
 *
 * This is the normal case while the free Render instance is waking up, so it is
 * a separate type from {@link ApiError}.
 */
export class BackendUnreachableError extends Error {
  constructor(message = 'The server is not reachable') {
    super(message)
    this.name = 'BackendUnreachableError'
  }
}
