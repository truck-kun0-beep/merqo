import { describe, expect, it } from 'vitest'
import { ApiError } from '../api/types'
import { readErrorDetails } from './format'
import { MAPPED_POS_ERROR_CODES, resolveApiErrorMessage } from './posErrors'

function makeApiError(errorCode: string, detail = 'Server raw detail'): ApiError {
  return new ApiError({
    type: 'about:blank',
    title: errorCode,
    status: 422,
    detail,
    instance: '/api/v1/pos/sales',
    errorCode,
    correlationId: 'corr-1',
    timestamp: '2026-11-27T00:00:00Z',
    fieldErrors: {},
    errors: [],
  })
}

describe('Phase 3 error code mappings', () => {
  it('maps every required Phase 3 error code to a clear message', () => {
    expect(MAPPED_POS_ERROR_CODES).toHaveLength(17)
    for (const code of MAPPED_POS_ERROR_CODES) {
      const msg = resolveApiErrorMessage(code, 'Raw server message')
      expect(msg.trim().length).toBeGreaterThan(8)
      expect(msg).not.toBe(code)
    }
  })

  it('preserves server PIN_POLICY and PIN_LOCKED detail messages via readErrorDetails', () => {
    const policy = readErrorDetails(
      makeApiError('PIN_POLICY', 'PIN cannot use sequential digits like 1234.'),
    )
    expect(policy.errorCode).toBe('PIN_POLICY')
    expect(policy.message).toBe('PIN cannot use sequential digits like 1234.')

    const locked = readErrorDetails(
      makeApiError('PIN_LOCKED', 'PIN locked for 15 minutes due to 5 failed attempts.'),
    )
    expect(locked.message).toContain('locked for 15 minutes')
  })

  it('maps static workflow codes into readable instructions', () => {
    expect(readErrorDetails(makeApiError('POS_SESSION_REQUIRED')).message).toMatch(
      /Open a cash session/i,
    )
    expect(readErrorDetails(makeApiError('CARD_NUMBER_REJECTED')).message).toMatch(
      /full card number/i,
    )
    expect(readErrorDetails(makeApiError('IDEMPOTENCY_CONFLICT')).message).toMatch(
      /idempotency key/i,
    )
    expect(readErrorDetails(makeApiError('RETURN_WINDOW_EXPIRED')).message).toMatch(/expired/i)
  })
})
