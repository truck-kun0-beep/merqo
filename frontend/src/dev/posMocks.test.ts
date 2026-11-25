import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { installPosMocks, resetPosMockStore } from './posMocks'

describe('POS dev mock fetch wrapper', () => {
  let uninstall: (() => void) | undefined
  let passthroughFetch: ReturnType<typeof vi.fn>

  beforeEach(() => {
    resetPosMockStore()
    passthroughFetch = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ ok: true }), { status: 200 }),
    )
    globalThis.fetch = passthroughFetch as unknown as typeof fetch
    uninstall = installPosMocks()
  })

  afterEach(() => {
    uninstall?.()
    vi.restoreAllMocks()
  })

  it('passes non-POS/sales requests straight through untouched', async () => {
    await fetch('/api/v1/auth/me')
    await fetch('/api/v1/catalog/products')
    expect(passthroughFetch).toHaveBeenCalledTimes(2)
  })

  it('handles /api/v1/pos/* and /api/v1/sales/* in memory with RFC 7807 errors', async () => {
    const okLookup = await fetch('/api/v1/pos/lookup?barcode=2000000000001&locationId=loc-1')
    expect(okLookup.status).toBe(200)
    const item = await okLookup.json()
    expect(item.sku).toBe('TEE01-BLA-M')
    expect(passthroughFetch).not.toHaveBeenCalled()

    const badLookup = await fetch('/api/v1/pos/lookup?barcode=0000000000000&locationId=loc-1')
    expect(badLookup.status).toBe(404)
    const problem = await badLookup.json()
    expect(problem.errorCode).toBe('BARCODE_NOT_FOUND')
  })
})
