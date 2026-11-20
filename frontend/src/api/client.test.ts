import { describe, expect, it } from 'vitest'
import { API_BASE_PATH, apiRequest } from './client'
import { ApiError, BackendUnreachableError } from './types'

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
})
