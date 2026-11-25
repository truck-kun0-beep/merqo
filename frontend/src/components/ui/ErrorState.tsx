import { readErrorDetails } from '../../lib/format'
import { Button } from './Button'

export interface ErrorStateProps {
  title?: string
  error?: unknown
  message?: string
  correlationId?: string
  onRetry?: () => void
  className?: string
}

/**
 * Displays an RFC 7807 problem or generic error with optional support correlation ID and retry button.
 */
export function ErrorState({
  title = 'Something went wrong',
  error,
  message,
  correlationId,
  onRetry,
  className = '',
}: ErrorStateProps) {
  const details = error ? readErrorDetails(error) : undefined
  const displayMessage = message ?? details?.message ?? 'An unexpected error occurred.'
  const displayCorrelationId = correlationId ?? details?.correlationId

  return (
    <div
      role="alert"
      className={[
        'rounded-xl border border-rose-200 bg-rose-50 px-5 py-4 text-sm text-rose-900',
        className,
      ].join(' ')}
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="space-y-1">
          <p className="font-semibold text-rose-950">{title}</p>
          <p className="text-rose-800">{displayMessage}</p>
          {displayCorrelationId && displayCorrelationId !== 'unknown' ? (
            <p className="pt-0.5 text-xs text-rose-700/80">
              Reference ID: <code className="font-mono">{displayCorrelationId}</code>
            </p>
          ) : null}
        </div>
        {onRetry ? (
          <Button variant="outline" size="sm" onClick={onRetry}>
            Retry
          </Button>
        ) : null}
      </div>
    </div>
  )
}
