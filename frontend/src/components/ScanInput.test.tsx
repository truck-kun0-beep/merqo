import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ScanInput } from './ScanInput'
import { ApiError } from '../api/types'

describe('ScanInput', () => {
  it('focuses on mount, submits trimmed code on Enter, and clears after success', async () => {
    const onScan = vi.fn().mockResolvedValue({ sku: 'TEE-BLK-M' })
    const onSuccess = vi.fn()

    render(
      <ScanInput
        label="Scan or type a barcode to find"
        scope="POS"
        locationId="loc-1"
        onScan={onScan}
        onSuccess={onSuccess}
      />,
    )

    const input = screen.getByLabelText('Scan or type a barcode to find')
    expect(input).toHaveFocus()

    await userEvent.type(input, '  2000000000001  {Enter}')

    await waitFor(() => {
      expect(onScan).toHaveBeenCalledWith('2000000000001', {
        scope: 'POS',
        locationId: 'loc-1',
      })
    })
    expect(onSuccess).toHaveBeenCalledWith({ sku: 'TEE-BLK-M' }, '2000000000001')
    expect(input).toHaveValue('')
    expect(input).toHaveFocus()
  })

  it('shows inline not-found message and keeps focus when scan fails', async () => {
    const notFoundError = new ApiError({
      type: 'about:blank',
      title: 'Not Found',
      status: 404,
      detail: 'No variant carries the barcode 999999.',
      instance: '/api/v1/catalog/lookup',
      errorCode: 'BARCODE_NOT_FOUND',
      correlationId: 'c-1',
      timestamp: '2026-11-20T10:00:00Z',
      fieldErrors: {},
      errors: [],
    })
    const onScan = vi.fn().mockRejectedValue(notFoundError)

    render(<ScanInput label="Scan barcode" onScan={onScan} />)

    const input = screen.getByLabelText('Scan barcode')
    await userEvent.type(input, '999999{Enter}')

    expect(await screen.findByRole('alert')).toHaveTextContent(/Not found.*999999/)
    await waitFor(() => {
      expect(input).toHaveFocus()
    })
  })
})
