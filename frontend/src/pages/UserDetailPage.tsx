import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'
import {
  createAssignment,
  endAssignment,
  fetchEffectivePermissions,
  fetchLocations,
  fetchPermissionCatalogue,
  fetchPermissionTemplates,
  fetchUser,
  replaceUserDelegations,
  replaceUserOverrides,
  replaceUserTemplates,
  updateUser,
} from '../api/business'
import { ApiError } from '../api/types'
import type { AssignmentSummary, LocationSummary, UserDetail, UserOverride } from '../api/models'
import { useSession } from '../session/sessionContext'
import { formatDateTime } from '../lib/format'

/**
 * The screen of one account.
 *
 * It shows everything that decides what the account may do, and lets the owner
 * or the manager change it:
 *
 * <ul>
 *   <li>the profile,
 *   <li>the permission templates,
 *   <li>the overrides, which sit on top of the templates,
 *   <li>the delegation list, which only a manager account has,
 *   <li>the branch assignments,
 *   <li>the effective permissions with the reason for each one.
 * </ul>
 *
 * Every write goes to the backend, and the backend decides whether the caller
 * is allowed to make it. The screen only hides the parts the caller cannot use.
 *
 * @returns the account screen element
 */
export default function UserDetailPage() {
  const { id } = useParams<{ id: string }>()
  const { user: signedIn } = useSession()
  const queryClient = useQueryClient()

  const accountQuery = useQuery({
    queryKey: ['user', id],
    queryFn: () => fetchUser(id ?? ''),
    enabled: Boolean(id),
  })

  const permissionsQuery = useQuery({
    queryKey: ['user', id, 'effective-permissions'],
    queryFn: () => fetchEffectivePermissions(id ?? ''),
    enabled: Boolean(id),
  })

  const account = accountQuery.data
  const isOwner = signedIn?.accountType === 'OWNER'

  if (accountQuery.isPending) {
    return (
      <div
        role="status"
        className="rounded-xl border border-slate-200 bg-white px-6 py-8 text-center text-sm text-slate-600"
      >
        Loading the account...
      </div>
    )
  }

  if (accountQuery.error) {
    return (
      <div
        role="alert"
        className="rounded-xl border border-rose-200 bg-rose-50 px-6 py-6 text-sm text-rose-800"
      >
        <p className="font-medium">The account could not be loaded.</p>
        <p className="mt-1">{accountQuery.error.message}</p>
      </div>
    )
  }

  if (!account) {
    return null
  }

  return (
    <div className="space-y-6">
      <div>
        <Link to="/users" className="text-xs font-medium text-merqo-700 hover:underline">
          Back to the people
        </Link>
        <h1 className="mt-2 text-2xl font-semibold tracking-tight text-slate-900">
          {account.fullName}
        </h1>
        <p className="mt-1 text-sm text-slate-600">
          {account.username} - {describeRole(account.accountType)} -{' '}
          {account.isActive ? 'Active' : 'Inactive'}
          {account.mustChangePassword ? ' - has to change the password' : ''}
        </p>
      </div>

      <ProfileSection key={profileKey(account)} account={account} queryClient={queryClient} />

      <TemplatesSection key={templateKey(account)} account={account} queryClient={queryClient} />

      <OverridesSection key={overrideKey(account)} account={account} queryClient={queryClient} />

      {account.accountType === 'MANAGER' ? (
        <DelegationsSection
          key={delegationKey(account)}
          account={account}
          queryClient={queryClient}
          isOwner={isOwner}
        />
      ) : null}

      <AssignmentsSection account={account} queryClient={queryClient} />

      <EffectivePermissionsSection
        permissions={permissionsQuery.data ?? []}
        isLoading={permissionsQuery.isPending}
      />
    </div>
  )
}

/**
 * The profile of the account.
 *
 * @param props the account and the query client
 * @returns the section element
 */
function ProfileSection({
  account,
  queryClient,
}: {
  account: UserDetail
  queryClient: ReturnType<typeof useQueryClient>
}) {
  const [fullName, setFullName] = useState(account.fullName)
  const [phone, setPhone] = useState(account.phone ?? '')
  const [email, setEmail] = useState(account.email ?? '')

  const mutation = useMutation({
    mutationFn: () =>
      updateUser(account.id, {
        fullName: fullName.trim(),
        phone: phone.trim(),
        email: email.trim(),
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['user', account.id] }),
  })

  return (
    <section aria-labelledby="profile-heading" className="space-y-3">
      <h2 id="profile-heading" className="text-sm font-semibold text-slate-700">
        Profile
      </h2>
      <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
        <div className="grid gap-3 sm:grid-cols-3">
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
            <span className="block text-xs font-medium text-slate-500">Phone</span>
            <input
              type="text"
              value={phone}
              onChange={(event) => setPhone(event.target.value)}
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
        </div>

        {mutation.isError ? (
          <p role="alert" className="mt-3 text-sm text-rose-700">
            {readError(mutation.error)}
          </p>
        ) : null}
        {mutation.isSuccess ? (
          <p role="status" className="mt-3 text-sm text-emerald-700">
            The profile was saved.
          </p>
        ) : null}

        <div className="mt-4">
          <button
            type="button"
            onClick={() => mutation.mutate()}
            disabled={mutation.isPending}
            className="rounded-md bg-merqo-700 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-merqo-800 disabled:opacity-60"
          >
            {mutation.isPending ? 'Saving...' : 'Save the profile'}
          </button>
        </div>
      </div>
    </section>
  )
}

/**
 * The permission templates of the account.
 *
 * @param props the account and the query client
 * @returns the section element
 */
function TemplatesSection({
  account,
  queryClient,
}: {
  account: UserDetail
  queryClient: ReturnType<typeof useQueryClient>
}) {
  const templatesQuery = useQuery({
    queryKey: ['permission-templates'],
    queryFn: fetchPermissionTemplates,
    retry: false,
  })

  const [selected, setSelected] = useState<string[]>(account.templates.map((item) => item.id))

  const mutation = useMutation({
    mutationFn: () => replaceUserTemplates(account.id, selected),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['user', account.id] }),
  })

  const canListTemplates = templatesQuery.isSuccess

  return (
    <section aria-labelledby="templates-heading" className="space-y-3">
      <h2 id="templates-heading" className="text-sm font-semibold text-slate-700">
        Permission templates
      </h2>
      <p className="text-xs text-slate-500">
        A template is a named set of permissions. The account gets every permission of every
        template it holds.
      </p>

      {templatesQuery.error ? (
        <p className="text-xs text-slate-500">
          The full list of templates cannot be read from here, so only the templates of this account
          are shown.
        </p>
      ) : null}

      <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
        <ul className="space-y-2">
          {account.templates.map((template) => (
            <li key={template.id} className="flex items-start gap-2 text-sm">
              <input
                id={`template-${template.id}`}
                type="checkbox"
                checked={selected.includes(template.id)}
                disabled={!canListTemplates || mutation.isPending}
                onChange={(event) =>
                  setSelected((previous) =>
                    event.target.checked
                      ? [...previous, template.id]
                      : previous.filter((value) => value !== template.id),
                  )
                }
                className="mt-1"
              />
              <label htmlFor={`template-${template.id}`}>
                <span className="font-medium text-slate-800">{template.name}</span>
                <span className="ml-2 font-mono text-xs text-slate-400">{template.code}</span>
                {template.description ? (
                  <span className="block text-xs text-slate-500">{template.description}</span>
                ) : null}
              </label>
            </li>
          ))}
        </ul>

        {account.templates.length === 0 ? (
          <p className="text-sm text-slate-600">This account holds no template.</p>
        ) : null}

        {mutation.isError ? (
          <p role="alert" className="mt-3 text-sm text-rose-700">
            {readError(mutation.error)}
          </p>
        ) : null}
        {mutation.isSuccess ? (
          <p role="status" className="mt-3 text-sm text-emerald-700">
            The templates were saved.
          </p>
        ) : null}

        <button
          type="button"
          onClick={() => mutation.mutate()}
          disabled={mutation.isPending}
          className="mt-4 rounded-md bg-merqo-700 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-merqo-800 disabled:opacity-60"
        >
          {mutation.isPending ? 'Saving...' : 'Save the templates'}
        </button>
      </div>
    </section>
  )
}

/**
 * The overrides of the account.
 *
 * @param props the account and the query client
 * @returns the section element
 */
function OverridesSection({
  account,
  queryClient,
}: {
  account: UserDetail
  queryClient: ReturnType<typeof useQueryClient>
}) {
  const catalogueQuery = useQuery({
    queryKey: ['permission-catalogue'],
    queryFn: fetchPermissionCatalogue,
  })

  const [draft, setDraft] = useState<UserOverride[]>(() =>
    account.overrides.map((override) => ({ ...override })),
  )

  const mutation = useMutation({
    mutationFn: () =>
      replaceUserOverrides(
        account.id,
        draft.map((override) => ({
          permission: override.permission,
          effect: override.effect,
          locationId: override.locationId,
        })),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['user', account.id] }),
  })

  const grantable = useMemo(
    () =>
      (catalogueQuery.data ?? []).flatMap((module) =>
        module.permissions
          .filter((permission) => !permission.ownerOnly)
          .map((permission) => ({
            code: permission.code,
            description: permission.description,
          })),
      ),
    [catalogueQuery.data],
  )

  /**
   * Adds one override to the draft.
   *
   * @param code the permission code
   */
  function addOverride(code: string) {
    setDraft((previous) => [
      ...previous,
      { id: `new-${code}`, permission: code, effect: 'ALLOW', locationId: null },
    ])
  }

  /**
   * Removes one override from the draft.
   *
   * @param index the position of the override
   */
  function removeOverride(index: number) {
    setDraft((previous) => previous.filter((_, position) => position !== index))
  }

  return (
    <section aria-labelledby="overrides-heading" className="space-y-3">
      <h2 id="overrides-heading" className="text-sm font-semibold text-slate-700">
        Overrides
      </h2>
      <p className="text-xs text-slate-500">
        An override sits on top of the templates: an allow adds a permission and a deny takes one
        away. A deny always wins.
      </p>

      <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
        {draft.length > 0 ? (
          <ul className="mb-4 space-y-2">
            {draft.map((override, index) => (
              <li
                key={`${override.permission}-${index}`}
                className="flex flex-wrap items-center gap-2"
              >
                <span className="font-mono text-xs text-slate-700">{override.permission}</span>
                <select
                  aria-label={`The effect of ${override.permission}`}
                  value={override.effect}
                  onChange={(event) =>
                    setDraft((previous) =>
                      previous.map((item, position) =>
                        position === index
                          ? { ...item, effect: event.target.value as 'ALLOW' | 'DENY' }
                          : item,
                      ),
                    )
                  }
                  className="rounded-md border border-slate-300 px-2 py-1 text-xs text-slate-800"
                >
                  <option value="ALLOW">Allow</option>
                  <option value="DENY">Deny</option>
                </select>
                <button
                  type="button"
                  onClick={() => removeOverride(index)}
                  className="text-xs font-medium text-rose-700 hover:underline"
                >
                  Remove
                </button>
              </li>
            ))}
          </ul>
        ) : (
          <p className="mb-4 text-sm text-slate-600">This account holds no override.</p>
        )}

        {grantable.length > 0 ? (
          <label className="block text-sm text-slate-700">
            <span className="block text-xs font-medium text-slate-500">Add an override</span>
            <select
              value=""
              onChange={(event) => {
                if (event.target.value) {
                  addOverride(event.target.value)
                }
              }}
              className="mt-1 w-full max-w-sm rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900"
            >
              <option value="">Choose a permission</option>
              {grantable.map((permission) => (
                <option key={permission.code} value={permission.code}>
                  {permission.code} - {permission.description}
                </option>
              ))}
            </select>
          </label>
        ) : null}

        {mutation.isError ? (
          <p role="alert" className="mt-3 text-sm text-rose-700">
            {readError(mutation.error)}
          </p>
        ) : null}
        {mutation.isSuccess ? (
          <p role="status" className="mt-3 text-sm text-emerald-700">
            The overrides were saved.
          </p>
        ) : null}

        <button
          type="button"
          onClick={() => mutation.mutate()}
          disabled={mutation.isPending}
          className="mt-4 rounded-md bg-merqo-700 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-merqo-800 disabled:opacity-60"
        >
          {mutation.isPending ? 'Saving...' : 'Save the overrides'}
        </button>
      </div>
    </section>
  )
}

/**
 * The delegation list of a manager.
 *
 * @param props the account, the query client and whether the caller is the owner
 * @returns the section element
 */
function DelegationsSection({
  account,
  queryClient,
  isOwner,
}: {
  account: UserDetail
  queryClient: ReturnType<typeof useQueryClient>
  isOwner: boolean
}) {
  const catalogueQuery = useQuery({
    queryKey: ['permission-catalogue'],
    queryFn: fetchPermissionCatalogue,
  })

  const [selected, setSelected] = useState<string[]>(account.delegations)

  const mutation = useMutation({
    mutationFn: () => replaceUserDelegations(account.id, selected),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['user', account.id] }),
  })

  const delegable = useMemo(
    () =>
      (catalogueQuery.data ?? []).flatMap((module) =>
        module.permissions
          .filter((permission) => permission.delegable && !permission.ownerOnly)
          .map((permission) => permission.code),
      ),
    [catalogueQuery.data],
  )

  if (!isOwner) {
    return (
      <section aria-labelledby="delegations-heading" className="space-y-3">
        <h2 id="delegations-heading" className="text-sm font-semibold text-slate-700">
          Delegation list
        </h2>
        <p className="rounded-xl border border-slate-200 bg-white px-4 py-3 text-sm text-slate-600">
          Only the owner may change the delegation list of a manager.
        </p>
      </section>
    )
  }

  return (
    <section aria-labelledby="delegations-heading" className="space-y-3">
      <h2 id="delegations-heading" className="text-sm font-semibold text-slate-700">
        Delegation list
      </h2>
      <p className="text-xs text-slate-500">
        The permissions this manager may hand to a staff member. Only a permission that is on this
        list can be delegated.
      </p>

      <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
        <ul className="grid gap-2 sm:grid-cols-2">
          {delegable.map((code) => (
            <li key={code} className="flex items-center gap-2 text-sm">
              <input
                id={`delegation-${code}`}
                type="checkbox"
                checked={selected.includes(code)}
                disabled={mutation.isPending}
                onChange={(event) =>
                  setSelected((previous) =>
                    event.target.checked
                      ? [...previous, code]
                      : previous.filter((value) => value !== code),
                  )
                }
              />
              <label htmlFor={`delegation-${code}`} className="font-mono text-xs text-slate-700">
                {code}
              </label>
            </li>
          ))}
        </ul>

        {delegable.length === 0 ? (
          <p className="text-sm text-slate-600">The catalogue could not be read.</p>
        ) : null}

        {mutation.isError ? (
          <p role="alert" className="mt-3 text-sm text-rose-700">
            {readError(mutation.error)}
          </p>
        ) : null}
        {mutation.isSuccess ? (
          <p role="status" className="mt-3 text-sm text-emerald-700">
            The delegation list was saved.
          </p>
        ) : null}

        <button
          type="button"
          onClick={() => mutation.mutate()}
          disabled={mutation.isPending}
          className="mt-4 rounded-md bg-merqo-700 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-merqo-800 disabled:opacity-60"
        >
          {mutation.isPending ? 'Saving...' : 'Save the delegation list'}
        </button>
      </div>
    </section>
  )
}

/**
 * The branch assignments of the account.
 *
 * @param props the account and the query client
 * @returns the section element
 */
function AssignmentsSection({
  account,
  queryClient,
}: {
  account: UserDetail
  queryClient: ReturnType<typeof useQueryClient>
}) {
  const locationsQuery = useQuery({
    queryKey: ['locations'],
    queryFn: fetchLocations,
  })

  const [locationId, setLocationId] = useState('')
  const [assignmentType, setAssignmentType] = useState<'MANAGER' | 'STAFF'>('STAFF')

  const assignMutation = useMutation({
    mutationFn: () =>
      createAssignment({
        userId: account.id,
        locationId,
        assignmentType,
      }),
    onSuccess: () => {
      setLocationId('')
      queryClient.invalidateQueries({ queryKey: ['user', account.id] })
    },
  })

  const endMutation = useMutation({
    mutationFn: (assignmentId: string) => endAssignment(assignmentId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['user', account.id] }),
  })

  const locations = locationsQuery.data ?? []

  return (
    <section aria-labelledby="assignments-heading" className="space-y-3">
      <h2 id="assignments-heading" className="text-sm font-semibold text-slate-700">
        Where this account works
      </h2>
      <p className="text-xs text-slate-500">
        A location scoped permission only works where the account is assigned.
      </p>

      <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
        {account.locations.length > 0 ? (
          <ul className="mb-4 space-y-2">
            {account.locations.map((assignment: AssignmentSummary) => (
              <li key={assignment.id} className="flex flex-wrap items-center gap-3 text-sm">
                <span className="text-slate-800">
                  {describeLocation(locations, assignment.locationId)}
                </span>
                <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs text-slate-600">
                  {assignment.assignmentType === 'MANAGER' ? 'Manager' : 'Staff'}
                </span>
                <span className="text-xs text-slate-500">
                  since {formatDateTime(assignment.startedAt)}
                </span>
                <button
                  type="button"
                  onClick={() => endMutation.mutate(assignment.id)}
                  className="text-xs font-medium text-rose-700 hover:underline"
                >
                  End
                </button>
              </li>
            ))}
          </ul>
        ) : (
          <p className="mb-4 text-sm text-slate-600">
            This account is not assigned to any location.
          </p>
        )}

        <div className="flex flex-wrap items-end gap-3">
          <label className="text-sm text-slate-700">
            <span className="block text-xs font-medium text-slate-500">Location</span>
            <select
              value={locationId}
              onChange={(event) => setLocationId(event.target.value)}
              className="mt-1 rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900"
            >
              <option value="">Choose a location</option>
              {locations.map((location) => (
                <option key={location.id} value={location.id}>
                  {location.name}
                </option>
              ))}
            </select>
          </label>
          <label className="text-sm text-slate-700">
            <span className="block text-xs font-medium text-slate-500">Role there</span>
            <select
              value={assignmentType}
              onChange={(event) => setAssignmentType(event.target.value as 'MANAGER' | 'STAFF')}
              className="mt-1 rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900"
            >
              <option value="STAFF">Staff</option>
              <option value="MANAGER">Manager</option>
            </select>
          </label>
          <button
            type="button"
            onClick={() => assignMutation.mutate()}
            disabled={!locationId || assignMutation.isPending}
            className="rounded-md bg-merqo-700 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-merqo-800 disabled:opacity-60"
          >
            {assignMutation.isPending ? 'Assigning...' : 'Assign'}
          </button>
        </div>

        {assignMutation.isError ? (
          <p role="alert" className="mt-3 text-sm text-rose-700">
            {readError(assignMutation.error)}
          </p>
        ) : null}
        {endMutation.isError ? (
          <p role="alert" className="mt-3 text-sm text-rose-700">
            {readError(endMutation.error)}
          </p>
        ) : null}
      </div>
    </section>
  )
}

/**
 * The effective permissions of the account, with the reason for each one.
 *
 * @param props the permissions and whether the list is still loading
 * @returns the section element
 */
function EffectivePermissionsSection({
  permissions,
  isLoading,
}: {
  permissions: Array<{
    code: string
    module: string
    description: string
    scope: string
    locationIds: string[]
    reasons: string[]
  }>
  isLoading: boolean
}) {
  return (
    <section aria-labelledby="effective-heading" className="space-y-3">
      <h2 id="effective-heading" className="text-sm font-semibold text-slate-700">
        Effective permissions
      </h2>
      <p className="text-xs text-slate-500">
        What the account may really do, after the templates and the overrides have been resolved.
      </p>

      {isLoading ? (
        <p className="text-sm text-slate-600">Loading the effective permissions...</p>
      ) : null}

      {!isLoading && permissions.length === 0 ? (
        <p className="rounded-xl border border-slate-200 bg-white px-4 py-3 text-sm text-slate-600">
          This account holds no effective permission.
        </p>
      ) : null}

      {permissions.length > 0 ? (
        <div className="overflow-x-auto rounded-xl border border-slate-200 bg-white shadow-sm">
          <table className="min-w-full divide-y divide-slate-200 text-sm">
            <thead className="bg-slate-50 text-left text-xs tracking-wide text-slate-500 uppercase">
              <tr>
                <th scope="col" className="px-4 py-3 font-medium">
                  Permission
                </th>
                <th scope="col" className="px-4 py-3 font-medium">
                  Scope
                </th>
                <th scope="col" className="px-4 py-3 font-medium">
                  Why
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {permissions.map((permission) => (
                <tr key={permission.code}>
                  <td className="px-4 py-3">
                    <span className="font-mono text-xs text-slate-800">{permission.code}</span>
                    <span className="block text-xs text-slate-500">{permission.description}</span>
                  </td>
                  <td className="px-4 py-3 text-xs text-slate-600">
                    {permission.scope === 'ALL'
                      ? 'Everywhere'
                      : `${permission.locationIds.length} location(s)`}
                  </td>
                  <td className="px-4 py-3 text-xs text-slate-600">
                    {permission.reasons.join(', ')}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </section>
  )
}

/**
 * Describes the role of an account.
 *
 * @param accountType the kind of account
 * @returns the label
 */
function describeRole(accountType: 'OWNER' | 'MANAGER' | 'STAFF'): string {
  if (accountType === 'OWNER') {
    return 'Owner'
  }
  if (accountType === 'MANAGER') {
    return 'Manager'
  }
  return 'Staff'
}

/**
 * Builds the key of the profile form.
 *
 * The form holds a draft of the profile values. It is rebuilt when the server
 * sends different values, so the draft never shows something the server does
 * not know.
 *
 * @param account the account
 * @returns the key
 */
function profileKey(account: UserDetail): string {
  return [account.fullName, account.phone ?? '', account.email ?? ''].join('|')
}

/**
 * Builds the key of the template section.
 *
 * @param account the account
 * @returns the key
 */
function templateKey(account: UserDetail): string {
  return account.templates.map((template) => template.id).join('|')
}

/**
 * Builds the key of the override section.
 *
 * @param account the account
 * @returns the key
 */
function overrideKey(account: UserDetail): string {
  return account.overrides
    .map((override) => `${override.permission}:${override.effect}:${override.locationId ?? '*'}`)
    .join('|')
}

/**
 * Builds the key of the delegation section.
 *
 * @param account the account
 * @returns the key
 */
function delegationKey(account: UserDetail): string {
  return account.delegations.join('|')
}

/**
 * Names a location, when the list of locations is known.
 *
 * @param locations the locations
 * @param locationId the id of the location
 * @returns the name, or the id when the location is unknown
 */
function describeLocation(locations: LocationSummary[], locationId: string | undefined): string {
  if (!locationId) {
    return 'Unknown location'
  }
  const match = locations.find((location) => location.id === locationId)
  return match ? match.name : locationId
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
