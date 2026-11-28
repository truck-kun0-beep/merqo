import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery } from '@tanstack/react-query'
import {
  fetchSimulatorInfo,
  simulateDemoOrder,
  type SimulateOrderResponse,
} from '../api/orders'
import {
  Badge,
  Button,
  ErrorState,
  FormField,
  Input,
  PageHeader,
  Select,
  Skeleton,
} from '../components/ui'

export default function DemoConsolePage() {
  const infoQuery = useQuery({
    queryKey: ['demo', 'simulator-info'],
    queryFn: fetchSimulatorInfo,
  })

  const [preset, setPreset] = useState('STANDARD_COD')
  const [sku, setSku] = useState('')
  const [quantity, setQuantity] = useState('1')
  const [lastResult, setLastResult] = useState<SimulateOrderResponse | null>(null)

  const simulateMutation = useMutation({
    mutationFn: (overridePreset?: string) =>
      simulateDemoOrder({
        preset: overridePreset ?? preset,
        sku: sku || undefined,
        quantity: Number(quantity) || 1,
      }),
    onSuccess: (res) => {
      setLastResult(res)
    },
  })

  if (infoQuery.isPending) {
    return <Skeleton className="h-64 w-full rounded-xl" />
  }

  if (infoQuery.error || !infoQuery.data) {
    return (
      <ErrorState
        title="Demo simulator unavailable"
        error={infoQuery.error}
        onRetry={() => infoQuery.refetch()}
      />
    )
  }

  const info = infoQuery.data

  return (
    <div className="space-y-6">
      <PageHeader
        title="Demo Console"
        description={`Owner-only signed webhook simulator (WEBSITE_MODE=${info.websiteMode}).`}
      />

      <section className="rounded-xl border border-slate-200 bg-white p-5 shadow-xs space-y-2 text-xs">
        <h2 className="text-sm font-semibold text-slate-900">Demo integration credentials</h2>
        <p className="font-mono">
          <code>X-Merqo-Key</code>: <strong>{info.demoClient.keyId}</strong>
        </p>
        <p className="font-mono break-all">
          HMAC Secret: <strong>{info.demoClient.secret}</strong>
        </p>
        <pre className="mt-2 overflow-x-auto rounded-lg bg-slate-900 p-3 text-[11px] text-slate-100">
{`node tools/website-simulator/send-order.mjs --sku ${info.sampleSkus[0]?.sku ?? 'TE-100-BLK'} --qty 1 --price ${info.sampleSkus[0]?.onlinePrice ?? '750.00'}`}
        </pre>
      </section>

      <section className="rounded-xl border border-slate-200 bg-white p-5 shadow-xs space-y-4">
        <h2 className="text-sm font-semibold text-slate-900">Fire simulated order webhook</h2>
        <div className="grid gap-3 sm:grid-cols-3">
          <FormField label="Preset">
            <Select
              aria-label="Simulator preset"
              value={preset}
              onChange={(e) => setPreset(e.target.value)}
            >
              {info.presets.map((p) => (
                <option key={p} value={p}>
                  {p.replaceAll('_', ' ')}
                </option>
              ))}
            </Select>
          </FormField>
          <FormField label="SKU (optional override)">
            <Select aria-label="Simulator SKU" value={sku} onChange={(e) => setSku(e.target.value)}>
              <option value="">Default preset SKU</option>
              {info.sampleSkus.map((s) => (
                <option key={s.variantId} value={s.sku}>
                  {s.sku} · {s.productName} (৳ {s.onlinePrice})
                </option>
              ))}
            </Select>
          </FormField>
          <FormField label="Quantity">
            <Input
              aria-label="Simulator quantity"
              type="number"
              min="1"
              value={quantity}
              onChange={(e) => setQuantity(e.target.value)}
            />
          </FormField>
        </div>

        <div className="flex flex-wrap gap-2">
          <Button
            onClick={() => simulateMutation.mutate(undefined)}
            isLoading={simulateMutation.isPending}
          >
            Send signed webhook
          </Button>
          {info.presets.map((p) => (
            <Button
              key={p}
              size="sm"
              variant="outline"
              disabled={simulateMutation.isPending}
              onClick={() => {
                setPreset(p)
                simulateMutation.mutate(p)
              }}
            >
              {p}
            </Button>
          ))}
        </div>

        {simulateMutation.isError ? (
          <ErrorState title="Simulation failed" error={simulateMutation.error} />
        ) : null}
      </section>

      {lastResult ? (
        <section className="rounded-xl border border-slate-200 bg-white p-5 shadow-xs space-y-3 text-xs">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="flex items-center gap-2">
              <Badge tone={lastResult.accepted ? 'success' : 'danger'}>
                HTTP {lastResult.httpStatus}
              </Badge>
              <span className="font-mono font-semibold">{lastResult.externalOrderId}</span>
              {lastResult.order ? (
                <Link
                  to={`/orders/${lastResult.order.orderId}`}
                  className="font-semibold text-merqo-700 hover:underline"
                >
                  Open {lastResult.order.orderNumber} &rarr;
                </Link>
              ) : null}
            </div>
            {lastResult.errorCode ? (
              <span className="font-mono text-rose-700">
                {lastResult.errorCode}: {lastResult.errorMessage}
              </span>
            ) : null}
          </div>
          <pre className="overflow-x-auto rounded-lg bg-slate-50 p-3 text-[11px] text-slate-800">
            {JSON.stringify(lastResult, null, 2)}
          </pre>
        </section>
      ) : null}
    </div>
  )
}
