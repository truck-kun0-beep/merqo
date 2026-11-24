import { apiRequest, toQueryString } from './client'
import type { TransferDetail, TransferRow, TransferStatus } from './models'

/**
 * The calls of the stock transfers.
 *
 * A transfer moves through a fixed set of states and every step is its own
 * endpoint, so a caller cannot skip the approval by calling the wrong one.
 */

/**
 * One line of a transfer, with the number of pieces.
 */
export interface TransferLineRequest {
  variantId: string
  quantity: number
}

/**
 * The body of a request transfer request.
 */
export interface RequestTransferRequest {
  sourceLocationId: string
  destinationLocationId: string
  lines: TransferLineRequest[]
  note?: string | null
}

/**
 * Reads the transfers the caller may see.
 *
 * @param status the state filter
 * @returns the transfers
 */
export function fetchTransfers(status?: string): Promise<TransferRow[]> {
  return apiRequest<TransferRow[]>(`/transfers${toQueryString({ status })}`)
}

/**
 * Reads one transfer with its lines and its history.
 *
 * @param id the transfer
 * @returns the transfer
 */
export function fetchTransfer(id: string): Promise<TransferDetail> {
  return apiRequest<TransferDetail>(`/transfers/${id}`)
}

/**
 * Asks for a transfer of stock.
 *
 * @param request the transfer to ask for
 * @returns the new transfer
 */
export function requestTransfer(request: RequestTransferRequest): Promise<TransferRow> {
  return apiRequest<TransferRow>('/transfers', {
    method: 'POST',
    body: JSON.stringify(request),
  })
}

/**
 * Approves a transfer. Every line needs an approved number.
 *
 * @param id the transfer
 * @param lines the approved numbers
 * @returns the approved transfer
 */
export function approveTransfer(
  id: string,
  lines: TransferLineRequest[],
): Promise<TransferRow> {
  return apiRequest<TransferRow>(`/transfers/${id}/approve`, {
    method: 'POST',
    body: JSON.stringify({ lines }),
  })
}

/**
 * Refuses a transfer.
 *
 * @param id the transfer
 * @param note why it was refused
 * @returns the refused transfer
 */
export function rejectTransfer(id: string, note?: string): Promise<TransferRow> {
  return apiRequest<TransferRow>(`/transfers/${id}/reject`, {
    method: 'POST',
    body: JSON.stringify({ note }),
  })
}

/**
 * Sends the stock away. The stock on the shelf of the source drops now.
 *
 * @param id the transfer
 * @param lines the numbers that leave
 * @returns the dispatched transfer
 */
export function dispatchTransfer(id: string, lines: TransferLineRequest[]): Promise<TransferRow> {
  return apiRequest<TransferRow>(`/transfers/${id}/dispatch`, {
    method: 'POST',
    body: JSON.stringify({ lines }),
  })
}

/**
 * Receives the stock at the destination.
 *
 * @param id the transfer
 * @param lines the numbers that arrive
 * @returns the received transfer
 */
export function receiveTransfer(id: string, lines: TransferLineRequest[]): Promise<TransferRow> {
  return apiRequest<TransferRow>(`/transfers/${id}/receive`, {
    method: 'POST',
    body: JSON.stringify({ lines }),
  })
}

/**
 * Closes a transfer that lost some of its goods. The note is mandatory.
 *
 * @param id the transfer
 * @param note why it is closed
 * @returns the closed transfer
 */
export function closeTransferWithShortage(id: string, note: string): Promise<TransferRow> {
  return apiRequest<TransferRow>(`/transfers/${id}/close`, {
    method: 'POST',
    body: JSON.stringify({ note }),
  })
}

/**
 * Calls a transfer off before anything was sent.
 *
 * @param id the transfer
 * @returns the cancelled transfer
 */
export function cancelTransfer(id: string): Promise<TransferRow> {
  return apiRequest<TransferRow>(`/transfers/${id}/cancel`, { method: 'POST' })
}

/**
 * The state of a transfer, as the backend names it.
 */
export type { TransferStatus }

