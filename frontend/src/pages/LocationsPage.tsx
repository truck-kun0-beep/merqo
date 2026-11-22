import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  activateLocation,
  createLocation,
  deactivateLocation,
  fetchLocations,
  setOnlineFulfillment,
  updateLocation,
} from '../api/business'
import { ApiError } from '../api/types'
import type { LocationSummary } from '../api/models'
import { useSession } from '../session/sessionContext'
import { formatDateTime } from '../lib/format'

/**
 * The locations screen.
 *
 * It shows only the locations the signed in user may see. The backend decides
 * that, not this screen: a manager of two branches sees two branches, and a
 * request for a third one is answered with a 404 so that the caller cannot
 * even learn that it exists.
 *
 * The owner also gets the management parts: a new location, the edit, the
 * activation and the switch that decides which warehouse fulfils the online
 * orders.
 *
 * @returns the locations screen element
 */
export default function LocationsPage() {
  const { user } = useSession()
  const queryClient = useQueryClient()
  const isOwner = user?.accountType === 'OWNER'

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

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight text-slate-900">Locations</h1>
        <p className="mt-1 text-sm text-slate-600">
          {isOwner
            ? 'Every branch and the warehouse of this business.'
            : 'The branches you are assigned to. You do not see the branches of other managers.'}
        </p>
      </div>

      {isOwner ? <CreateLocationForm /> : null}

      {query.isPending ? (
        <div
          role="status"
          className="rounded-xl border border-slate-200 bg-white px-6 py-8 text-center text-sm text-slate-600"
        >
          Loading the locations...
        </div>
      ) : null}

      {query.error ? (
        <div
          role="alert"
          className="rounded-xl border border-rose-200 bg-rose-50 px-6 py-6 text-sm text-rose-800"
        >
          <p className="font-medium">The locations could not be loaded.</p>
          <p className="mt-1">{query.error.message}</p>
        </div>
      ) : null}

      {query.isSuccess && locations.length === 0 ? (
        <div className="rounded-xl border border-slate-200 bg-white px-6 py-8 text-center text-sm text-slate-600">
          No location is assigned to your account yet. Ask the owner to assign you to a branch.
        </div>
      ) : null}

      {locations.length > 0 ? (
        <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {locations.map((location) => (
            <li
              key={location.id}
              className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm"
            >
              <div className="flex items-start justify-between gap-2">
                <div>
                  <p className="text-sm font-semibold text-slate-900">{location.name}</p>
                  <p className="mt-0.5 text-xs text-slate-500">
                    {location.code} -{' '}
                    {location.locationType === 'WAREHOUSE' ? 'Warehouse' : 'Branch'}
                  </p>
                </div>
                <span
                  className={[
                    'rounded-full px-2 py-0.5 text-xs font-medium',
                    location.isActive
                      ? 'bg-emerald-50 text-emerald-700'
                      : 'bg-slate-100 text-slate-500',
                  ].join(' ')}
                >
                  {location.isActive ? 'Active' : 'Inactive'}
                </span>
              </div>

              {location.isOnlineFulfillment ? (
                <p className="mt-2 text-xs font-medium text-merqo-700">Fulfils the online orders</p>
              ) : null}

              {location.address ? (
                <p className="mt-2 text-xs text-slate-600">{location.address}</p>
              ) : null}
              {location.phone ? <p className="text-xs text-slate-600">{location.phone}</p> : null}

              {isOwner ? (
                <LocationActions
                  location={location}
                  onDeactivate={() => deactivateMutation.mutate(location.id)}
                  onActivate={() => activateMutation.mutate(location.id)}
                  onMakeFulfillment={() => fulfillmentMutation.mutate(location.id)}
                  canMakeFulfillment={
                    location.locationType === 'WAREHOUSE' && !location.isOnlineFulfillment
                  }
                />
              ) : null}
            </li>
          ))}
        </ul>
      ) : null}

      {deactivateMutation.error || activateMutation.error || fulfillmentMutation.error ? (
        <p role="alert" className="text-sm text-rose-700">
          {readError(
            deactivateMutation.error ?? activateMutation.error ?? fulfillmentMutation.error,
          )}
        </p>
      ) : null}

      <p className="text-xs text-slate-500">
        Checked at {formatDateTime(new Date())}. The list comes from the server on every visit.
      </p>
    </div>
  )
}

/**
 * The management actions of one location.
 *
 * @param props the location and the handlers of the three actions
 * @returns the actions element
 */
function LocationActions({
  location,
  onDeactivate,
  onActivate,
  onMakeFulfillment,
  canMakeFulfillment,
}: {
  location: LocationSummary
  onDeactivate: () => void
  onActivate: () => void
  onMakeFulfillment: () => void
  canMakeFulfillment: boolean
}) {
  const [editing, setEditing] = useState(false)

  if (editing) {
    return <EditLocationForm location={location} onDone={() => setEditing(false)} />
  }

  return (
    <div className="mt-3 flex flex-wrap gap-3 border-t border-slate-100 pt-3">
      <button
        type="button"
        onClick={() => setEditing(true)}
        className="text-xs font-medium text-merqo-700 hover:underline"
      >
        Edit
      </button>
      {location.isActive ? (
        <button
          type="button"
          onClick={onDeactivate}
          className="text-xs font-medium text-rose-700 hover:underline"
        >
          Deactivate
        </button>
      ) : (
        <button
          type="button"
          onClick={onActivate}
          className="text-xs font-medium text-emerald-700 hover:underline"
        >
          Activate
        </button>
      )}
      {canMakeFulfillment ? (
        <button
          type="button"
          onClick={onMakeFulfillment}
          className="text-xs font-medium text-merqo-700 hover:underline"
        >
          Fulfil the online orders
        </button>
      ) : null}
    </div>
  )
}

/**
 * The form that creates a location.
 *
 * @returns the form element
 */
function CreateLocationForm() {
  const queryClient = useQueryClient()
  const [open, setOpen] = useState(false)
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
      setOpen(false)
      queryClient.invalidateQueries({ queryKey: ['locations'] })
    },
  })

  if (!open) {
    return (
      <div>
        <button
          type="button"
          onClick={() => setOpen(true)}
          className="rounded-md bg-merqo-700 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-merqo-800"
        >
          Add a location
        </button>
      </div>
    )
  }

  return (
    <form
      className="space-y-3 rounded-xl border border-slate-200 bg-white p-4 shadow-sm"
      onSubmit={(event) => {
        event.preventDefault()
        mutation.mutate()
      }}
    >
      <h2 className="text-sm font-semibold text-slate-700">New location</h2>

      <div className="grid gap-3 sm:grid-cols-2">
        <label className="text-sm text-slate-700">
          <span className="block text-xs font-medium text-slate-500">Code</span>
          <input
            type="text"
            value={code}
            onChange={(event) => setCode(event.target.value)}
            className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900 outline-none focus:border-merqo-500 focus:ring-2 focus:ring-merqo-100"
          />
        </label>
        <label className="text-sm text-slate-700">
          <span className="block text-xs font-medium text-slate-500">Name</span>
          <input
            type="text"
            value={name}
            onChange={(event) => setName(event.target.value)}
            className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900 outline-none focus:border-merqo-500 focus:ring-2 focus:ring-merqo-100"
          />
        </label>
        <label className="text-sm text-slate-700">
          <span className="block text-xs font-medium text-slate-500">Kind</span>
          <select
            value={locationType}
            onChange={(event) => setLocationType(event.target.value as 'BRANCH' | 'WAREHOUSE')}
            className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900"
          >
            <option value="BRANCH">Branch</option>
            <option value="WAREHOUSE">Warehouse</option>
          </select>
        </label>
        <label className="text-sm text-slate-700">
          <span className="block text-xs font-medium text-slate-500">Phone</span>
          <input
            type="text"
            value={phone}
            onChange={(event) => setPhone(event.target.value)}
            className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900 outline-none focus:border-merqo-500 focus:ring-2 focus:ring-merqo-100"
          />
        </label>
      </div>

      <label className="block text-sm text-slate-700">
        <span className="block text-xs font-medium text-slate-500">Address</span>
        <input
          type="text"
          value={address}
          onChange={(event) => setAddress(event.target.value)}
          className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900 outline-none focus:border-merqo-500 focus:ring-2 focus:ring-merqo-100"
        />
      </label>

      {mutation.isError ? (
        <p role="alert" className="text-sm text-rose-700">
          {readError(mutation.error)}
        </p>
      ) : null}

      <div className="flex items-center gap-3">
        <button
          type="submit"
          disabled={mutation.isPending || !code.trim() || !name.trim()}
          className="rounded-md bg-merqo-700 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-merqo-800 disabled:opacity-60"
        >
          {mutation.isPending ? 'Saving...' : 'Create the location'}
        </button>
        <button
          type="button"
          onClick={() => setOpen(false)}
          className="rounded-md border border-slate-300 px-4 py-2 text-sm font-medium text-slate-700 transition-colors hover:bg-slate-100"
        >
          Cancel
        </button>
      </div>
    </form>
  )
}

/**
 * The form that edits one location.
 *
 * @param props the location and the callback after the change
 * @returns the form element
 */
function EditLocationForm({ location, onDone }: { location: LocationSummary; onDone: () => void }) {
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
      onDone()
    },
  })

  return (
    <form
      className="mt-3 space-y-2 border-t border-slate-100 pt-3"
      onSubmit={(event) => {
        event.preventDefault()
        mutation.mutate()
      }}
    >
      <label className="block text-xs text-slate-600">
        <span className="block font-medium text-slate-500">Name</span>
        <input
          type="text"
          value={name}
          onChange={(event) => setName(event.target.value)}
          className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900 outline-none focus:border-merqo-500 focus:ring-2 focus:ring-merqo-100"
        />
      </label>
      <label className="block text-xs text-slate-600">
        <span className="block font-medium text-slate-500">Address</span>
        <input
          type="text"
          value={address}
          onChange={(event) => setAddress(event.target.value)}
          className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900 outline-none focus:border-merqo-500 focus:ring-2 focus:ring-merqo-100"
        />
      </label>
      <label className="block text-xs text-slate-600">
        <span className="block font-medium text-slate-500">Phone</span>
        <input
          type="text"
          value={phone}
          onChange={(event) => setPhone(event.target.value)}
          className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900 outline-none focus:border-merqo-500 focus:ring-2 focus:ring-merqo-100"
        />
      </label>

      {mutation.isError ? (
        <p role="alert" className="text-xs text-rose-700">
          {readError(mutation.error)}
        </p>
      ) : null}

      <div className="flex items-center gap-3">
        <button
          type="submit"
          disabled={mutation.isPending}
          className="rounded-md bg-merqo-700 px-4 py-2 text-xs font-semibold text-white transition-colors hover:bg-merqo-800 disabled:opacity-60"
        >
          {mutation.isPending ? 'Saving...' : 'Save'}
        </button>
        <button
          type="button"
          onClick={onDone}
          className="text-xs font-medium text-slate-600 hover:underline"
        >
          Cancel
        </button>
      </div>
    </form>
  )
}

/**
 * Turns any failure into one sentence the user can act on.
 *
 * @param caught the thrown value
 * @returns the message to show
 */
function readError(caught: unknown): string {
  if (caught instanceof ApiError) {
    return caught.message
  }
  return 'Something went wrong. Try again.'
}
