import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import {
  activateUser,
  createUser,
  deactivateUser,
  fetchUsers,
  resetUserPassword,
} from '../api/business'
import { ApiError } from '../api/types'
import { useSession } from '../session/sessionContext'
import { formatDateTime } from '../lib/format'

/**
 * The people screen.
 *
 * It shows the accounts the signed in user may see. The backend scopes the
 * list, so a manager sees the staff of their own branches and the owner sees
 * everybody.
 *
 * Every account links to the screen that shows what it may do, so the owner can
 * change the templates, the overrides, the delegation list and the assignments.
 *
 * @returns the people screen element
 */
export default function UsersPage() {
  const { user } = useSession()
  const queryClient = useQueryClient()
  const [search, setSearch] = useState('')
  const [appliedSearch, setAppliedSearch] = useState('')
  const isOwner = user?.accountType === 'OWNER'

  const query = useQuery({
    queryKey: ['users', appliedSearch],
    queryFn: () => fetchUsers({ q: appliedSearch || undefined, size: 50 }),
  })

  const deactivateMutation = useMutation({
    mutationFn: (id: string) => deactivateUser(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['users'] }),
  })

  const activateMutation = useMutation({
    mutationFn: (id: string) => activateUser(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['users'] }),
  })

  const resetMutation = useMutation({
    mutationFn: (id: string) => resetUserPassword(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['users'] }),
  })

  const users = query.data?.items ?? []

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight text-slate-900">People</h1>
        <p className="mt-1 text-sm text-slate-600">
          Every account of Merqo that you are allowed to see.
        </p>
      </div>

      <form
        className="flex gap-2"
        onSubmit={(event) => {
          event.preventDefault()
          setAppliedSearch(search.trim())
        }}
      >
        <label htmlFor="user-search" className="sr-only">
          Search by name, username or staff number
        </label>
        <input
          id="user-search"
          type="search"
          value={search}
          placeholder="Search by name, username or staff number"
          onChange={(event) => setSearch(event.target.value)}
          className="w-full max-w-sm rounded-md border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 outline-none focus:border-merqo-500 focus:ring-2 focus:ring-merqo-100"
        />
        <button
          type="submit"
          className="rounded-md border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 transition-colors hover:bg-slate-100"
        >
          Search
        </button>
      </form>

      {isOwner ? <CreateUserForm /> : null}

      {query.isPending ? (
        <div
          role="status"
          className="rounded-xl border border-slate-200 bg-white px-6 py-8 text-center text-sm text-slate-600"
        >
          Loading the accounts...
        </div>
      ) : null}

      {query.error ? (
        <div
          role="alert"
          className="rounded-xl border border-rose-200 bg-rose-50 px-6 py-6 text-sm text-rose-800"
        >
          <p className="font-medium">The accounts could not be loaded.</p>
          <p className="mt-1">{query.error.message}</p>
        </div>
      ) : null}

      {query.isSuccess && users.length === 0 ? (
        <div className="rounded-xl border border-slate-200 bg-white px-6 py-8 text-center text-sm text-slate-600">
          No account matches this search.
        </div>
      ) : null}

      {users.length > 0 ? (
        <div className="overflow-x-auto rounded-xl border border-slate-200 bg-white shadow-sm">
          <table className="min-w-full divide-y divide-slate-200 text-sm">
            <thead className="bg-slate-50 text-left text-xs tracking-wide text-slate-500 uppercase">
              <tr>
                <th scope="col" className="px-4 py-3 font-medium">
                  Name
                </th>
                <th scope="col" className="px-4 py-3 font-medium">
                  Username
                </th>
                <th scope="col" className="px-4 py-3 font-medium">
                  Role
                </th>
                <th scope="col" className="px-4 py-3 font-medium">
                  Staff no.
                </th>
                <th scope="col" className="px-4 py-3 font-medium">
                  Status
                </th>
                <th scope="col" className="px-4 py-3 font-medium">
                  Last sign in
                </th>
                <th scope="col" className="px-4 py-3 font-medium">
                  Access
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {users.map((account) => (
                <tr key={account.id}>
                  <td className="px-4 py-3 font-medium text-slate-900">
                    {account.fullName}
                    {account.id === user?.id ? (
                      <span className="ml-2 text-xs font-normal text-merqo-700">(you)</span>
                    ) : null}
                  </td>
                  <td className="px-4 py-3 text-slate-600">{account.username}</td>
                  <td className="px-4 py-3 text-slate-600">
                    {account.accountType === 'OWNER'
                      ? 'Owner'
                      : account.accountType === 'MANAGER'
                        ? 'Manager'
                        : 'Staff'}
                  </td>
                  <td className="px-4 py-3 font-mono text-xs text-slate-500">
                    {account.employeeCode ?? '-'}
                  </td>
                  <td className="px-4 py-3">
                    <span
                      className={[
                        'rounded-full px-2 py-0.5 text-xs font-medium',
                        account.isActive
                          ? 'bg-emerald-50 text-emerald-700'
                          : 'bg-slate-100 text-slate-500',
                      ].join(' ')}
                    >
                      {account.isActive ? 'Active' : 'Inactive'}
                    </span>
                    {account.isDemo ? (
                      <span className="ml-1 rounded-full bg-merqo-50 px-2 py-0.5 text-xs font-medium text-merqo-700">
                        Demo
                      </span>
                    ) : null}
                  </td>
                  <td className="px-4 py-3 text-xs text-slate-500">
                    {account.lastLoginAt ? formatDateTime(account.lastLoginAt) : 'Never'}
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex flex-wrap items-center gap-3">
                      <Link
                        to={`/users/${account.id}`}
                        className="text-xs font-medium text-merqo-700 hover:underline"
                      >
                        Open
                      </Link>
                      {account.accountType !== 'OWNER' ? (
                        account.isActive ? (
                          <button
                            type="button"
                            onClick={() => deactivateMutation.mutate(account.id)}
                            className="text-xs font-medium text-rose-700 hover:underline"
                          >
                            Deactivate
                          </button>
                        ) : (
                          <button
                            type="button"
                            onClick={() => activateMutation.mutate(account.id)}
                            className="text-xs font-medium text-emerald-700 hover:underline"
                          >
                            Activate
                          </button>
                        )
                      ) : null}
                      {account.accountType !== 'OWNER' ? (
                        <button
                          type="button"
                          onClick={() => resetMutation.mutate(account.id)}
                          className="text-xs font-medium text-slate-600 hover:underline"
                        >
                          Reset the password
                        </button>
                      ) : null}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}

      {deactivateMutation.error || activateMutation.error || resetMutation.error ? (
        <p role="alert" className="text-sm text-rose-700">
          {readError(deactivateMutation.error ?? activateMutation.error ?? resetMutation.error)}
        </p>
      ) : null}

      {resetMutation.data?.temporaryPassword ? (
        <p role="status" className="text-sm text-emerald-700">
          The temporary password is{' '}
          <span className="font-mono">{resetMutation.data.temporaryPassword}</span>. It is shown
          once and the account has to change it at the next sign in.
        </p>
      ) : null}

      {query.data ? (
        <p className="text-xs text-slate-500">
          Showing {query.data.items.length} of {query.data.totalItems} accounts.
        </p>
      ) : null}
    </div>
  )
}

/**
 * The form that creates an account.
 *
 * @returns the form element
 */
function CreateUserForm() {
  const queryClient = useQueryClient()
  const [open, setOpen] = useState(false)
  const [username, setUsername] = useState('')
  const [fullName, setFullName] = useState('')
  const [email, setEmail] = useState('')
  const [accountType, setAccountType] = useState<'MANAGER' | 'STAFF'>('STAFF')

  const mutation = useMutation({
    mutationFn: () =>
      createUser({
        username: username.trim(),
        fullName: fullName.trim() || undefined,
        email: email.trim() || undefined,
        accountType,
      }),
    onSuccess: () => {
      setUsername('')
      setFullName('')
      setEmail('')
      setOpen(false)
      queryClient.invalidateQueries({ queryKey: ['users'] })
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
          Add an account
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
      <h2 className="text-sm font-semibold text-slate-700">New account</h2>

      <div className="grid gap-3 sm:grid-cols-2">
        <label className="text-sm text-slate-700">
          <span className="block text-xs font-medium text-slate-500">Username</span>
          <input
            type="text"
            value={username}
            onChange={(event) => setUsername(event.target.value)}
            className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900 outline-none focus:border-merqo-500 focus:ring-2 focus:ring-merqo-100"
          />
        </label>
        <label className="text-sm text-slate-700">
          <span className="block text-xs font-medium text-slate-500">Full name</span>
          <input
            type="text"
            value={fullName}
            onChange={(event) => setFullName(event.target.value)}
            className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900 outline-none focus:border-merqo-500 focus:ring-2 focus:ring-merqo-100"
          />
        </label>
        <label className="text-sm text-slate-700">
          <span className="block text-xs font-medium text-slate-500">Email</span>
          <input
            type="text"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900 outline-none focus:border-merqo-500 focus:ring-2 focus:ring-merqo-100"
          />
        </label>
        <label className="text-sm text-slate-700">
          <span className="block text-xs font-medium text-slate-500">Kind</span>
          <select
            value={accountType}
            onChange={(event) => setAccountType(event.target.value as 'MANAGER' | 'STAFF')}
            className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900"
          >
            <option value="STAFF">Staff</option>
            <option value="MANAGER">Manager</option>
          </select>
        </label>
      </div>

      <p className="text-xs text-slate-500">
        The backend writes a temporary password and the account has to change it at the first sign
        in.
      </p>

      {mutation.isError ? (
        <p role="alert" className="text-sm text-rose-700">
          {readError(mutation.error)}
        </p>
      ) : null}
      {mutation.data?.temporaryPassword ? (
        <p role="status" className="text-sm text-emerald-700">
          The temporary password is{' '}
          <span className="font-mono">{mutation.data.temporaryPassword}</span>. It is shown once.
        </p>
      ) : null}

      <div className="flex items-center gap-3">
        <button
          type="submit"
          disabled={mutation.isPending || !username.trim()}
          className="rounded-md bg-merqo-700 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-merqo-800 disabled:opacity-60"
        >
          {mutation.isPending ? 'Saving...' : 'Create the account'}
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
