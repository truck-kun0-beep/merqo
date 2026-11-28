import { Badge, type BadgeTone } from './Badge'

const STATUS_MAP: Record<string, { label: string; tone: BadgeTone }> = {
  ACTIVE: { label: 'Active', tone: 'success' },
  INACTIVE: { label: 'Inactive', tone: 'neutral' },
  SCHEDULED: { label: 'Scheduled', tone: 'info' },
  EXPIRED: { label: 'Expired', tone: 'neutral' },
  DISABLED: { label: 'Disabled', tone: 'warning' },
  LOW_STOCK: { label: 'Low stock', tone: 'warning' },
  IN_PROGRESS: { label: 'In progress', tone: 'info' },
  SUBMITTED: { label: 'Submitted', tone: 'warning' },
  APPLIED: { label: 'Applied', tone: 'success' },
  CANCELLED: { label: 'Cancelled', tone: 'neutral' },
  REQUESTED: { label: 'Requested', tone: 'info' },
  APPROVED: { label: 'Approved', tone: 'primary' },
  REJECTED: { label: 'Rejected', tone: 'danger' },
  DISPATCHED: { label: 'Dispatched', tone: 'warning' },
  PARTIALLY_RECEIVED: { label: 'Partially received', tone: 'warning' },
  RECEIVED: { label: 'Received', tone: 'success' },
  CLOSED_WITH_SHORTAGE: { label: 'Closed with shortage', tone: 'danger' },
  COMPLETED: { label: 'Completed', tone: 'success' },
  PARTIALLY_RETURNED: { label: 'Partially returned', tone: 'warning' },
  RETURNED: { label: 'Returned', tone: 'neutral' },
  FLAGGED: { label: 'Flagged', tone: 'danger' },
  PENDING_CONFIRMATION: { label: 'Pending confirmation', tone: 'warning' },
  FOLLOW_UP: { label: 'Follow-up', tone: 'info' },
  CONFIRMED: { label: 'Confirmed', tone: 'primary' },
  DECLINED: { label: 'Declined', tone: 'neutral' },
  READY_TO_SHIP: { label: 'Ready to ship', tone: 'info' },
  SHIPPED: { label: 'Shipped', tone: 'info' },
  DELIVERED: { label: 'Delivered', tone: 'success' },
  REFUSED: { label: 'Refused', tone: 'danger' },
  PENDING_VERIFICATION: { label: 'Pending verification', tone: 'warning' },
  PENDING: { label: 'Pending', tone: 'warning' },
  VERIFIED: { label: 'Verified', tone: 'success' },
  PAID: { label: 'Paid', tone: 'success' },
  NONE: { label: 'Matched', tone: 'success' },
  OVER: { label: 'Over', tone: 'info' },
  SHORT: { label: 'Short', tone: 'danger' },
  UP: { label: 'Up', tone: 'success' },
  DOWN: { label: 'Down', tone: 'danger' },
}

export interface StatusPillProps {
  status: string
  label?: string
  className?: string
}

/**
 * Renders a consistent status pill with semantic colors mapped by status key.
 */
export function StatusPill({ status, label, className = '' }: StatusPillProps) {
  const normalized = status.trim().toUpperCase()
  const mapped = STATUS_MAP[normalized] ?? {
    label: status.replaceAll('_', ' '),
    tone: 'neutral' as BadgeTone,
  }

  return (
    <Badge tone={mapped.tone} className={className}>
      {label ?? mapped.label}
    </Badge>
  )
}
