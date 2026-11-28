import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  createIntegrationClient,
  disableIntegrationClient,
  enableIntegrationClient,
  fetchIntegrationClients,
  rotateIntegrationClient,
  type IntegrationClientSecretResponse,
  type IntegrationClientSummary,
} from '../api/orders'
import {
  Badge,
  Button,
  DataTable,
  DateText,
  ErrorState,
  FormField,
  Input,
  Modal,
  PageHeader,
  StatusPill,
  useToast,
  type DataTableColumn,
} from '../components/ui'

export default function IntegrationClientsPage() {
  const queryClient = useQueryClient()
  const { toast } = useToast()

  const clientsQuery = useQuery({
    queryKey: ['integration', 'clients'],
    queryFn: fetchIntegrationClients,
  })

  const [createOpen, setCreateOpen] = useState(false)
  const [name, setName] = useState('')
  const [nameError, setNameError] = useState<string | null>(null)
  const [revealedSecret, setRevealedSecret] =
    useState<IntegrationClientSecretResponse | null>(null)

  function refresh() {
    queryClient.invalidateQueries({ queryKey: ['integration', 'clients'] })
  }

  const createMutation = useMutation({
    mutationFn: () => createIntegrationClient(name.trim()),
    onSuccess: (res) => {
      setCreateOpen(false)
      setName('')
      setNameError(null)
      setRevealedSecret(res)
      refresh()
      toast({ title: `API key ${res.client.keyId} created`, tone: 'success' })
    },
  })

  const rotateMutation = useMutation({
    mutationFn: (id: string) => rotateIntegrationClient(id),
    onSuccess: (res) => {
      setRevealedSecret(res)
      refresh()
      toast({ title: `Secret rotated for ${res.client.keyId}`, tone: 'success' })
    },
  })

  const toggleMutation = useMutation({
    mutationFn: (client: IntegrationClientSummary) =>
      client.active ? disableIntegrationClient(client.id) : enableIntegrationClient(client.id),
    onSuccess: (client) => {
      refresh()
      toast({
        title: client.active ? `${client.keyId} enabled` : `${client.keyId} disabled`,
        tone: 'success',
      })
    },
  })

  function submitCreate(e: FormEvent) {
    e.preventDefault()
    if (!name.trim()) {
      setNameError('Enter a name for this external store.')
      return
    }
    setNameError(null)
    createMutation.mutate()
  }

  const columns: DataTableColumn<IntegrationClientSummary>[] = [
    {
      key: 'name',
      header: 'Storefront client',
      render: (c) => (
        <div>
          <p className="font-medium text-slate-900">{c.name}</p>
          <p className="font-mono text-xs text-slate-600">{c.keyId}</p>
        </div>
      ),
    },
    {
      key: 'active',
      header: 'Status',
      render: (c) => <StatusPill status={c.active ? 'ACTIVE' : 'DISABLED'} />,
    },
    {
      key: 'lastUsed',
      header: 'Last used',
      render: (c) =>
        c.lastUsedAt ? <DateText value={c.lastUsedAt} withTime /> : <Badge tone="neutral">Never</Badge>,
    },
    {
      key: 'rotated',
      header: 'Secret rotated',
      render: (c) =>
        c.rotatedAt ? <DateText value={c.rotatedAt} withTime /> : <span className="text-xs text-slate-400">—</span>,
    },
    {
      key: 'actions',
      header: 'Actions',
      render: (c) => (
        <div className="flex flex-wrap gap-2">
          <Button
            size="sm"
            variant="outline"
            disabled={rotateMutation.isPending}
            onClick={() => rotateMutation.mutate(c.id)}
          >
            Rotate secret
          </Button>
          <Button
            size="sm"
            variant="outline"
            disabled={toggleMutation.isPending}
            onClick={() => toggleMutation.mutate(c)}
          >
            {c.active ? 'Disable' : 'Enable'}
          </Button>
        </div>
      ),
    },
  ]

  return (
    <div className="space-y-6">
      <PageHeader
        title="Integration keys"
        description="Manage HMAC-SHA256 webhook keys for external storefronts. Secrets are shown only once."
        actions={<Button onClick={() => setCreateOpen(true)}>Create API key</Button>}
      />

      {revealedSecret ? (
        <div
          role="region"
          aria-label="New integration secret"
          className="space-y-2 rounded-xl border border-amber-300 bg-amber-50 p-4 text-sm text-amber-950"
        >
          <div className="flex items-start justify-between gap-3">
            <div>
              <p className="font-semibold">
                Copy secret for {revealedSecret.client.name} now — it will never be shown again.
              </p>
              <p className="mt-1 font-mono text-xs">
                Key ID (<code>X-Merqo-Key</code>): <strong>{revealedSecret.client.keyId}</strong>
              </p>
              <p className="mt-1 font-mono text-xs break-all">
                Secret: <strong data-testid="revealed-secret">{revealedSecret.secret}</strong>
              </p>
            </div>
            <Button size="sm" variant="outline" onClick={() => setRevealedSecret(null)}>
              Dismiss
            </Button>
          </div>
        </div>
      ) : null}

      {clientsQuery.error ? (
        <ErrorState
          title="Could not load integration keys"
          error={clientsQuery.error}
          onRetry={() => clientsQuery.refetch()}
        />
      ) : (
        <div className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-xs">
          <DataTable
            columns={columns}
            rows={clientsQuery.data ?? []}
            getRowKey={(c) => c.id}
            isLoading={clientsQuery.isPending}
            emptyTitle="No integration clients registered"
            emptyDescription="Create an API key to accept signed orders from an external webshop."
          />
        </div>
      )}

      <Modal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        title="Create website API key"
        size="sm"
      >
        <form onSubmit={submitCreate} className="space-y-3">
          <FormField label="Storefront name" required>
            <Input
              aria-label="Storefront name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="WooCommerce BD Store"
            />
          </FormField>
          {nameError ? (
            <p role="alert" className="text-xs font-medium text-rose-700">
              {nameError}
            </p>
          ) : null}
          {createMutation.isError ? (
            <ErrorState title="Could not create key" error={createMutation.error} />
          ) : null}
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="outline" onClick={() => setCreateOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" isLoading={createMutation.isPending}>
              Create key
            </Button>
          </div>
        </form>
      </Modal>
    </div>
  )
}
