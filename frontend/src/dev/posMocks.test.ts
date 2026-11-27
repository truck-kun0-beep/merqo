import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { installPosMocks, resetPosMockStore } from './posMocks'
import posApiSource from '../api/pos.ts?raw'
import mainSource from '../main.tsx?raw'

describe('POS dev mock fetch wrapper', () => {
  let uninstall: (() => void) | undefined
  let passthroughFetch: ReturnType<typeof vi.fn>

  beforeEach(() => {
    resetPosMockStore()
    passthroughFetch = vi
      .fn()
      .mockResolvedValue(new Response(JSON.stringify({ ok: true }), { status: 200 }))
    globalThis.fetch = passthroughFetch as unknown as typeof fetch
    uninstall = installPosMocks()
  })

  afterEach(() => {
    uninstall?.()
    vi.restoreAllMocks()
  })

  it('passes non-POS/inspection requests straight through untouched', async () => {
    await fetch('/api/v1/auth/me')
    await fetch('/api/v1/catalog/products')
    expect(passthroughFetch).toHaveBeenCalledTimes(2)
  })

  it('handles real Phase 3 routes (/catalog/lookup, /pos/*, /inventory/inspections) when installed', async () => {
    const okLookup = await fetch(
      '/api/v1/catalog/lookup?barcode=2000000000001&scope=POS&locationId=loc-1',
    )
    expect(okLookup.status).toBe(200)
    const item = await okLookup.json()
    expect(item.variant.sku).toBe('TEE01-BLA-M')
    expect(passthroughFetch).not.toHaveBeenCalled()

    const badLookup = await fetch(
      '/api/v1/catalog/lookup?barcode=0000000000000&scope=POS&locationId=loc-1',
    )
    expect(badLookup.status).toBe(404)
    const problem = await badLookup.json()
    expect(problem.errorCode).toBe('BARCODE_NOT_FOUND')
  })

  it('keeps real API paths unconditionally in src/api/pos.ts and gates mocks behind DEV + VITE_POS_MOCKS=1', () => {
    expect(posApiSource).not.toContain('VITE_POS_MOCKS')
    expect(posApiSource).not.toContain('USE_MOCK_PATHS')
    expect(posApiSource).not.toContain('posMocks')
    expect(mainSource).toContain("import.meta.env.DEV && import.meta.env.VITE_POS_MOCKS === '1'")
  })
})
