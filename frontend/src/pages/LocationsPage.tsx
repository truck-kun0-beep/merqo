import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  activateLocation,
  createLocation,
  deactivateLocation,
  fetchLocations,
  setOnlineFulfillment,
  updateLocation,
} from '../api/business'
import type { LocationSummary } from '../api/models'
import { useSession } from '../session/sessionContext'
import {
  Badge,
  Button,
  DateText,
  EmptyState,
  ErrorState,
  FormField,
  Input,
  Modal,
  PageHeader,
  Select,
  Skeleton,
  StatusPill,
} from '../components/ui'

/**
 * The locations screen.
 *
 * Shows the branches and warehouse within the caller's scope, with owner
 * management actions in clean dialogs.
 */
export default function LocationsPage() {
  const { user } = useSession()
  const queryClient = useQueryClient()
  const isOwner = user?.accountType === 'OWNER'

  const [createOpen, setCreateOpen] = useState(false)
  const [editingLocation, setEditingLocation] = useState<LocationSummary | null>(null)

  const query = useQuery({ queryKey: ['locations'], queryFn: fetchLocations })
  const locations = query.data ?? []

  const deactivateMutation = useMutation({
    mutationFn: (id: string) => deactivateLocation(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['locations'] }),
  })

  const activateMutation = useMutation({
    mutationFn: (id: string) => activateLocation(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['locations'] }),
  })

  const fulfillmentMutation = useMutation({
    mutationFn: (id: string) => setOnlineFulfillment(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['locations'] }),
  })

  const actionError =
    deactivateMutation.error ?? activateMutation.error ?? fulfillmentMutation.error

  return (
    <div className="space-y-6">
      <PageHeader
        title="Locations"
        description={
          isOwner
            ? 'Every branch and warehouse configured for this business.'
            : 'Branches and warehouses assigned to your account.'
        }
        actions={
          isOwner ? (
            <Button onClick={() => setCreateOpen(true)}>Add a location</Button>
          ) : undefined
        }
      />

      {query.isPending ? (
        <div role="status" aria-label="Loading the locations" className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          <Skeleton className="h-44 w-full rounded-xl" />
          <Skeleton className="h-44 w-full rounded-xl" />
          <Skeleton className="h-44 w-full rounded-xl" />
        </div>
      ) : null}

      {query.error ? (
        <ErrorState
          title="The locations could not be loaded."
          error={query.error}
          onRetry={() => query.refetch()}
        />
      ) : null}

      {query.isSuccess && locations.length === 0 ? (
        <EmptyState
          title="No locations assigned"
          description="No location is assigned to your account yet. Ask the owner to assign you to a branch."
        />
      ) : null}

      {locations.length > 0 ? (
        <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {locations.map((location) => (
            <li
              key={location.id}
              className="flex flex-col justify-between rounded-xl border border-slate-200 bg-white p-5 shadow-xs"
            >
              <div>
                <div className="flex items-start justify-between gap-2">
                  <div>
                    <h2 className="text-base font-semibold text-slate-900">{location.name}</h2>
                    <div className="mt-1 flex flex-wrap items-center gap-1.5">
                      <span className="font-mono text-xs text-slate-500">{location.code}</span>
                      <Badge tone="neutral">
                        {location.locationType === 'WAREHOUSE' ? 'Warehouse' : 'Branch'}
                      </Badge>
                    </div>
                  </div>
                  <StatusPill status={location.isActive ? 'ACTIVE' : 'INACTIVE'} />
                </div>

                {location.isOnlineFulfillment ? (
                  <div className="mt-3">
                    <Badge tone="primary">Fulfils online orders</Badge>
                  </div>
                ) : null}

                {location.address || location.phone ? (
                  <dl className="mt-3 space-y-1 text-xs text-slate-600">
                    {location.address ? (
                      <div>
                        <dt className="sr-only">Address</dt>
                        <dd>{location.address}</dd>
                      </div>
                    ) : null}
                    {location.phone ? (
                      <div>
                        <dt className="sr-only">Phone</dt>
                        <dd className="font-mono">{location.phone}</dd>
                      </div>
                    ) : null}
                  </dl>
                ) : null}
              </div>

              {isOwner ? (
                <div className="mt-4 flex flex-wrap items-center gap-2 border-t border-slate-100 pt-3">
                  <Button variant="outline" size="sm" onClick={() => setEditingLocation(location)}>
                    Edit
                  </Button>
                  {location.isActive ? (
                    <Button
                      variant="ghost"
                      size="sm"
                      className="text-rose-700 hover:bg-rose-50"
                      onClick={() => deactivateMutation.mutate(location.id)}
                      isLoading={
                        deactivateMutation.isPending && deactivateMutation.variables === location.id
                      }
                    >
                      Deactivate
                    </Button>
                  ) : (
                    <Button
                      variant="ghost"
                      size="sm"
                      className="text-emerald-700 hover:bg-emerald-50"
                      onClick={() => activateMutation.mutate(location.id)}
                      isLoading={
                        activateMutation.isPending && activateMutation.variables === location.id
                      }
                    >
                      Activate
                    </Button>
                  )}
                  {location.locationType === 'WAREHOUSE' && !location.isOnlineFulfillment ? (
                    <Button
                      variant="ghost"
                      size="sm"
                      className="text-merqo-700 hover:bg-merqo-50"
                      onClick={() => fulfillmentMutation.mutate(location.id)}
                      isLoading={
                        fulfillmentMutation.isPending && fulfillmentMutation.variables === location.id
                      }
                    >
                      Fulfil online orders
                    </Button>
                  ) : null}
                </div>
              ) : null}
            </li>
          ))}
        </ul>
      ) : null}

      {actionError ? <ErrorState title="Action failed" error={actionError} /> : null}

      <p className="text-xs text-slate-500">
        Last checked <DateText value={new Date()} withTime />. Scoped directly by the server.
      </p>

      <CreateLocationModal open={createOpen} onClose={() => setCreateOpen(false)} />
      {editingLocation ? (
        <EditLocationModal
          key={editingLocation.id}
          location={editingLocation}
          onClose={() => setEditingLocation(null)}
        />
      ) : null}
    </div>
  )
}

function CreateLocationModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const queryClient = useQueryClient()
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [locationType, setLocationType] = useState<'BRANCH' | 'WAREHOUSE'>('BRANCH')
  const [address, setAddress] = useState('')
  const [phone, setPhone] = useState('')

  const mutation = useMutation({
    mutationFn: () =>
      createLocation({
        code: code.trim(),
        name: name.trim(),
        locationType,
        address: address.trim() || undefined,
        phone: phone.trim() || undefined,
      }),
    onSuccess: () => {
      setCode('')
      setName('')
      setAddress('')
      setPhone('')
      queryClient.invalidateQueries({ queryKey: ['locations'] })
      onClose()
    },
  })

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    mutation.mutate()
  }

  return (
    <Modal open={open} onClose={onClose} title="New location">
      <form id="create-location-form" onSubmit={handleSubmit} className="space-y-4">
        <div className="grid gap-3 sm:grid-cols-2">
          <FormField label="Code" required>
            <Input value={code} onChange={(e) => setCode(e.target.value)} required />
          </FormField>
          <FormField label="Name" required>
            <Input value={name} onChange={(e) => setName(e.target.value)} required />
          </FormField>
          <FormField label="Kind">
            <Select
              value={locationType}
              onChange={(e) => setLocationType(e.target.value as 'BRANCH' | 'WAREHOUSE')}
            >
              <option value="BRANCH">Branch</option>
              <option value="WAREHOUSE">Warehouse</option>
            </Select>
          </FormField>
          <FormField label="Phone">
            <Input value={phone} onChange={(e) => setPhone(e.target.value)} />
          </FormField>
        </div>

        <FormField label="Address">
          <Input value={address} onChange={(e) => setAddress(e.target.value)} />
        </FormField>

        {mutation.isError ? <ErrorState title="Could not create location" error={mutation.error} /> : null}

        <div className="flex justify-end gap-2 pt-2">
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button
            type="submit"
            isLoading={mutation.isPending}
            loadingText="Saving..."
            disabled={!code.trim() || !name.trim()}
          >
            Create the location
          </Button>
        </div>
      </form>
    </Modal>
  )
}

function EditLocationModal({
  location,
  onClose,
}: {
  location: LocationSummary
  onClose: () => void
}) {
  const queryClient = useQueryClient()
  const [name, setName] = useState(location.name)
  const [address, setAddress] = useState(location.address ?? '')
  const [phone, setPhone] = useState(location.phone ?? '')

  const mutation = useMutation({
    mutationFn: () =>
      updateLocation(location.id, {
        name: name.trim(),
        address: address.trim(),
        phone: phone.trim(),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['locations'] })
      onClose()
    },
  })

  return (
    <Modal open onClose={onClose} title={`Edit ${location.name}`}>
      <form
        onSubmit={(event) => {
          event.preventDefault()
          mutation.mutate()
        }}
        className="space-y-4"
      >
        <FormField label="Name" required>
          <Input value={name} onChange={(e) => setName(e.target.value)} required />
        </FormField>
        <FormField label="Address">
          <Input value={address} onChange={(e) => setAddress(e.target.value)} />
        </FormField>
        <FormField label="Phone">
          <Input value={phone} onChange={(e) => setPhone(e.target.value)} />
        </FormField>

        {mutation.isError ? <ErrorState title="Could not save location" error={mutation.error} /> : null}

        <div className="flex justify-end gap-2 pt-2">
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" isLoading={mutation.isPending} loadingText="Saving...">
            Save
          </Button>
        </div>
      </form>
    </Modal>
  )
}
