import { describe, expect, it } from 'vitest'
import { API_BASE_PATH, apiRequest, toQueryString } from './client'
import { ApiError, BackendUnreachableError } from './types'

/**
 * A filter written as an interface.
 *
 * <p>TypeScript gives an interface no implicit index signature, so this is the
 * shape that a `Record<string, ...>` parameter would refuse. The helper has to
 * accept it anyway, because the catalogue and the stock screens use exactly
 * this shape.
 */
interface ProductFilters {
  q?: string
  activeOnly?: boolean
  page?: number
}

/**
 * A response double that behaves like a real fetch response.
 */
function jsonResponse(body: unknown, init: { status?: number; headers?: Headers } = {}) {
  const status = init.status ?? 200
  const headers = init.headers ?? new Headers({ 'Content-Type': 'application/json' })
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: status === 200 ? 'OK' : 'Error',
    headers,
    url: 'http://localhost/api/v1/health',
    text: async () => JSON.stringify(body),
    json: async () => body,
  } as unknown as Response
}

describe('apiRequest', () => {
  it('calls the API on its own origin with same-origin credentials', async () => {
    const calls: Array<[string, RequestInit | undefined]> = []
    const originalFetch = globalThis.fetch
    globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
      calls.push([String(input), init])
      return jsonResponse({ status: 'UP' })
    }) as typeof fetch

    try {
      await apiRequest<{ status: string }>('/health')

      expect(calls).toHaveLength(1)
      expect(calls[0][0]).toBe(`${API_BASE_PATH}/health`)
      expect(calls[0][1]?.credentials).toBe('same-origin')
    } finally {
      globalThis.fetch = originalFetch
    }
  })

  it('turns a problem detail into an ApiError with the stable error code', async () => {
    const originalFetch = globalThis.fetch
    globalThis.fetch = (async () =>
      jsonResponse(
        {
          type: 'https://merqo.dev/problems/insufficient-stock',
          title: 'Unprocessable Content',
          status: 422,
          detail: 'Not enough stock.',
          instance: '/api/v1/sales',
          errorCode: 'INSUFFICIENT_STOCK',
          correlationId: 'abc-123',
          timestamp: '2026-11-20T12:00:00Z',
          fieldErrors: {},
          errors: [],
        },
        { status: 422 },
      )) as typeof fetch

    try {
      await expect(apiRequest('/sales')).rejects.toBeInstanceOf(ApiError)

      try {
        await apiRequest('/sales')
      } catch (error) {
        const apiError = error as ApiError
        expect(apiError.errorCode).toBe('INSUFFICIENT_STOCK')
        expect(apiError.status).toBe(422)
        expect(apiError.correlationId).toBe('abc-123')
        expect(apiError.message).toBe('Not enough stock.')
      }
    } finally {
      globalThis.fetch = originalFetch
    }
  })

  it('reports an unreachable backend with a dedicated error type', async () => {
    const originalFetch = globalThis.fetch
    globalThis.fetch = (async () => {
      throw new TypeError('Failed to fetch')
    }) as typeof fetch

    try {
      await expect(apiRequest('/health')).rejects.toBeInstanceOf(BackendUnreachableError)
    } finally {
      globalThis.fetch = originalFetch
    }
  })

  it('treats a gateway status as an unreachable backend', async () => {
    const originalFetch = globalThis.fetch
    globalThis.fetch = (async () =>
      jsonResponse(
        {
          type: 'about:blank',
          title: 'Backend Unavailable',
          status: 503,
          detail: 'The backend could not be reached.',
          instance: '/api/v1/health',
          errorCode: 'BACKEND_UNREACHABLE',
          correlationId: 'abc-123',
          timestamp: '2026-11-20T12:00:00Z',
          fieldErrors: {},
          errors: [],
        },
        { status: 503 },
      )) as typeof fetch

    try {
      // A 502, 503 or 504 means the backend could not answer at all, so the
      // user must see the waking screen and not a business error.
      await expect(apiRequest('/health')).rejects.toBeInstanceOf(BackendUnreachableError)
    } finally {
      globalThis.fetch = originalFetch
    }
  })

  it('keeps a business error an ApiError', async () => {
    const originalFetch = globalThis.fetch
    globalThis.fetch = (async () =>
      jsonResponse(
        {
          type: 'about:blank',
          title: 'Unauthorized',
          status: 401,
          detail: 'Invalid username or password',
          instance: '/api/v1/auth/login',
          errorCode: 'INVALID_CREDENTIALS',
          correlationId: 'abc-123',
          timestamp: '2026-11-20T12:00:00Z',
          fieldErrors: {},
          errors: [],
        },
        { status: 401 },
      )) as typeof fetch

    try {
      await expect(apiRequest('/auth/login')).rejects.toBeInstanceOf(ApiError)
      await expect(apiRequest('/auth/login')).rejects.not.toBeInstanceOf(BackendUnreachableError)
    } finally {
      globalThis.fetch = originalFetch
    }
  })
})

describe('toQueryString', () => {
  it('leaves out the filters that are not set', () => {
    expect(toQueryString({ q: 'shirt', activeOnly: undefined, page: 2 })).toBe('?q=shirt&page=2')
  })

  it('returns nothing when no filter is set', () => {
    expect(toQueryString({ q: undefined, activeOnly: false })).toBe('?activeOnly=false')
    expect(toQueryString({})).toBe('')
  })

  it('accepts a filter that is declared as an interface', () => {
    // The interface has no index signature, so this is the shape that a
    // `Record<string, ...>` parameter would refuse to take.
    const filters: ProductFilters = { q: 'shoe', activeOnly: true }
    expect(toQueryString(filters)).toBe('?q=shoe&activeOnly=true')
  })

  it('escapes the key and the value', () => {
    expect(toQueryString({ q: 'a b&c' })).toBe('?q=a%20b%26c')
  })
})
