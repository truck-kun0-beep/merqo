import type { OrderFlagsView, RiskTier } from '../api/orders'
import { Badge, type BadgeTone } from './ui'

const RISK_META: Record<RiskTier, { label: string; tone: BadgeTone }> = {
  NEW: { label: 'New customer', tone: 'neutral' },
  OK: { label: 'OK history', tone: 'success' },
  WATCH: { label: 'Watch', tone: 'warning' },
  HIGH: { label: 'High refusal risk', tone: 'danger' },
}

export function RiskBadge({ tier }: { tier: RiskTier }) {
  const meta = RISK_META[tier]
  return <Badge tone={meta.tone}>{meta.label}</Badge>
}

export function OrderFlagsBadges({ flags }: { flags: OrderFlagsView }) {
  return (
    <div className="flex flex-wrap gap-1">
      {flags.priceMismatch ? <Badge tone="warning">Price mismatch</Badge> : null}
      {flags.invalidPhone ? <Badge tone="danger">Bad phone</Badge> : null}
      {flags.highRisk ? <Badge tone="danger">High risk</Badge> : null}
      {flags.cancellationRequested ? <Badge tone="warning">Cancel requested</Badge> : null}
    </div>
  )
}
