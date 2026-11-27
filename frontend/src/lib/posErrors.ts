/**
 * Maps backend RFC 7807 `errorCode` values to clear, user-facing messages for POS,
 * approval PIN, and inspection workflows.
 */
const STATIC_ERROR_MESSAGES: Readonly<Record<string, string>> = {
  POS_SESSION_REQUIRED: 'Open a cash session for this branch before making a sale or return.',
  SESSION_ALREADY_OPEN: 'A cash session is already open at this branch.',
  SESSION_NOT_OPEN: 'This cash session is already closed or not open.',
  DISCOUNT_LIMIT_EXCEEDED:
    'Discount exceeds your limit. Request manager approval to continue.',
  APPROVAL_INVALID: 'Manager approval PIN or token is invalid, expired, or already used.',
  PAYMENT_REFERENCE_REQUIRED:
    'Enter a payment reference (TrxID or approval code) for this payment method.',
  PAYMENT_INSUFFICIENT: 'Payment amount is less than the sale total.',
  CARD_NUMBER_REJECTED:
    'Do not enter a full card number. Enter only the last 4 digits or an approval code.',
  DUPLICATE_REFERENCE: 'This payment reference (TrxID) was already used on another sale.',
  IDEMPOTENCY_CONFLICT:
    'A different request was already processed with this idempotency key. Refresh and check before retrying.',
  EMPTY_CART: 'Add at least one item to the cart before completing a sale.',
  ALREADY_RETURNED: 'This item or receipt has already been fully returned.',
  RETURN_QUANTITY_EXCEEDED:
    'Return quantity exceeds the remaining returnable quantity on this sale.',
  RETURN_WINDOW_EXPIRED: 'The return window for this sale has expired.',
}

/**
 * Resolves a human-friendly error message from a backend `errorCode` and `detail`.
 *
 * Server details for policy/rate-limit/stock errors (`PIN_POLICY`, `PIN_LOCKED`,
 * `INSUFFICIENT_STOCK`) are preserved when available so exact limits and counts remain visible.
 */
export function resolveApiErrorMessage(
  errorCode: string | undefined,
  fallbackDetail: string,
): string {
  if (!errorCode) {
    return fallbackDetail
  }

  const staticMessage = STATIC_ERROR_MESSAGES[errorCode]
  if (staticMessage) {
    return staticMessage
  }

  switch (errorCode) {
    case 'PIN_POLICY':
      return fallbackDetail || 'Approval PIN does not meet security policy requirements.'
    case 'PIN_LOCKED':
      return (
        fallbackDetail ||
        'Approval PIN entry is temporarily locked after repeated failures. Try again later.'
      )
    case 'INSUFFICIENT_STOCK':
      return fallbackDetail || 'Not enough sellable stock available at this branch.'
    default:
      return fallbackDetail
  }
}

export const MAPPED_POS_ERROR_CODES = [
  'POS_SESSION_REQUIRED',
  'SESSION_ALREADY_OPEN',
  'SESSION_NOT_OPEN',
  'DISCOUNT_LIMIT_EXCEEDED',
  'APPROVAL_INVALID',
  'PIN_POLICY',
  'PIN_LOCKED',
  'PAYMENT_REFERENCE_REQUIRED',
  'PAYMENT_INSUFFICIENT',
  'CARD_NUMBER_REJECTED',
  'DUPLICATE_REFERENCE',
  'IDEMPOTENCY_CONFLICT',
  'EMPTY_CART',
  'ALREADY_RETURNED',
  'RETURN_QUANTITY_EXCEEDED',
  'RETURN_WINDOW_EXPIRED',
  'INSUFFICIENT_STOCK',
] as const
