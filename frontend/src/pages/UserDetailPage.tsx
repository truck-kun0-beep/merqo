import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useParams } from 'react-router-dom'
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
import type {
  AssignmentSummary,
  ExplainedPermission,
  LocationSummary,
  UserDetail,
  UserOverride,
} from '../api/models'
import { useSession } from '../session/sessionContext'
import {
  Badge,
  Button,
  Checkbox,
  DataTable,
  DateText,
  ErrorState,
  FormField,
  Input,
  PageHeader,
  Select,
  Skeleton,
  StatusPill,
  Tabs,
  type DataTableColumn,
  type TabItem,
} from '../components/ui'

/**
 * Detail screen for one user account with tabbed sections for profile,
 * permission templates, overrides, manager delegations, location assignments,
 * and resolved effective permissions.
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
      <div role="status" aria-label="Loading account" className="space-y-4">
        <Skeleton className="h-16 w-full rounded-xl" />
        <Skeleton className="h-48 w-full rounded-xl" />
      </div>
    )
  }

  if (accountQuery.error) {
    return (
      <ErrorState
        title="The account could not be loaded."
        error={accountQuery.error}
        onRetry={() => accountQuery.refetch()}
      />
    )
  }

  if (!account) {
    return null
  }

  const profileCard = (
    <ProfileSection key={profileKey(account)} account={account} queryClient={queryClient} />
  )
  const templatesCard = (
    <TemplatesSection key={templateKey(account)} account={account} queryClient={queryClient} />
  )
  const overridesCard = (
    <OverridesSection key={overrideKey(account)} account={account} queryClient={queryClient} />
  )
  const delegationsCard =
    account.accountType === 'MANAGER' ? (
      <DelegationsSection
        key={delegationKey(account)}
        account={account}
        queryClient={queryClient}
        isOwner={isOwner}
      />
    ) : null
  const assignmentsCard = <AssignmentsSection account={account} queryClient={queryClient} />
  const effectiveCard = (
    <EffectivePermissionsSection
      permissions={permissionsQuery.data ?? []}
      isLoading={permissionsQuery.isPending}
    />
  )

  const tabs: TabItem[] = [
    {
      id: 'all',
      label: 'All sections',
      content: (
        <div className="space-y-6">
          {profileCard}
          {templatesCard}
          {overridesCard}
          {delegationsCard}
          {assignmentsCard}
          {effectiveCard}
        </div>
      ),
    },
    { id: 'profile', label: 'Profile', content: profileCard },
    {
      id: 'templates',
      label: 'Templates',
      badge: <Badge tone="neutral">{account.templates.length}</Badge>,
      content: templatesCard,
    },
    {
      id: 'overrides',
      label: 'Overrides',
      badge: <Badge tone="neutral">{account.overrides.length}</Badge>,
      content: overridesCard,
    },
    ...(delegationsCard
      ? [{ id: 'delegations', label: 'Delegations', content: delegationsCard }]
      : []),
    {
      id: 'assignments',
      label: 'Locations',
      badge: <Badge tone="neutral">{account.locations.length}</Badge>,
      content: assignmentsCard,
    },
    {
      id: 'effective',
      label: 'Effective permissions',
      content: effectiveCard,
    },
  ]

  return (
    <div className="space-y-6">
      <PageHeader
        breadcrumbs={[{ label: 'People', to: '/users' }, { label: account.fullName }]}
        title={account.fullName}
        description={`${account.username} · ${describeRole(account.accountType)}${
          account.mustChangePassword ? ' · Password change required' : ''
        }`}
        actions={<StatusPill status={account.isActive ? 'ACTIVE' : 'INACTIVE'} />}
      />

      <Tabs tabs={tabs} defaultTab="all" />
    </div>
  )
}

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
    <section
      aria-labelledby="profile-heading"
      className="rounded-xl border border-slate-200 bg-white p-5 shadow-xs"
    >
      <h2 id="profile-heading" className="text-sm font-semibold text-slate-900">
        Profile
      </h2>
      <div className="mt-4 grid gap-4 sm:grid-cols-3">
        <FormField label="Full name">
          <Input value={fullName} onChange={(e) => setFullName(e.target.value)} />
        </FormField>
        <FormField label="Phone">
          <Input value={phone} onChange={(e) => setPhone(e.target.value)} />
        </FormField>
        <FormField label="Email">
          <Input type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
        </FormField>
      </div>

      {mutation.isError ? (
        <div className="mt-4">
          <ErrorState title="Could not save profile" error={mutation.error} />
        </div>
      ) : null}
      {mutation.isSuccess ? (
        <p role="status" className="mt-3 text-sm font-medium text-emerald-700">
          The profile was saved.
        </p>
      ) : null}

      <div className="mt-4">
        <Button
          onClick={() => mutation.mutate()}
          isLoading={mutation.isPending}
          loadingText="Saving..."
        >
          Save the profile
        </Button>
      </div>
    </section>
  )
}

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
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['user', account.id] })
      queryClient.invalidateQueries({ queryKey: ['user', account.id, 'effective-permissions'] })
    },
  })

  const canListTemplates = templatesQuery.isSuccess
  const availableTemplates =
    canListTemplates && templatesQuery.data.length > 0 ? templatesQuery.data : account.templates

  return (
    <section
      aria-labelledby="templates-heading"
      className="rounded-xl border border-slate-200 bg-white p-5 shadow-xs"
    >
      <h2 id="templates-heading" className="text-sm font-semibold text-slate-900">
        Permission templates
      </h2>
      <p className="mt-1 text-xs text-slate-500">
        A template is a named set of permissions. The account receives every permission in each
        assigned template.
      </p>

      {templatesQuery.error ? (
        <p className="mt-2 text-xs text-slate-500">
          The full template list cannot be read from this account, so only assigned templates are
          shown.
        </p>
      ) : null}

      <div className="mt-4">
        {availableTemplates.length > 0 ? (
          <ul className="grid gap-2.5 sm:grid-cols-2">
            {availableTemplates.map((template) => (
              <li
                key={template.id}
                className="rounded-lg border border-slate-200 bg-slate-50/50 p-3"
              >
                <Checkbox
                  id={`template-${template.id}`}
                  checked={selected.includes(template.id)}
                  disabled={!canListTemplates || mutation.isPending}
                  onChange={(event) =>
                    setSelected((previous) =>
                      event.target.checked
                        ? [...previous, template.id]
                        : previous.filter((value) => value !== template.id),
                    )
                  }
                  label={
                    <span>
                      {template.name}{' '}
                      <span className="ml-1 font-mono text-xs font-normal text-slate-400">
                        {template.code}
                      </span>
                    </span>
                  }
                  description={template.description ?? undefined}
                />
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-sm text-slate-600">This account holds no template.</p>
        )}

        {mutation.isError ? (
          <div className="mt-4">
            <ErrorState title="Could not update templates" error={mutation.error} />
          </div>
        ) : null}
        {mutation.isSuccess ? (
          <p role="status" className="mt-3 text-sm font-medium text-emerald-700">
            The templates were saved.
          </p>
        ) : null}

        <div className="mt-4">
          <Button
            onClick={() => mutation.mutate()}
            isLoading={mutation.isPending}
            loadingText="Saving..."
          >
            Save the templates
          </Button>
        </div>
      </div>
    </section>
  )
}

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
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['user', account.id] })
      queryClient.invalidateQueries({ queryKey: ['user', account.id, 'effective-permissions'] })
    },
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

  function addOverride(code: string) {
    setDraft((previous) => [
      ...previous,
      { id: `new-${code}`, permission: code, effect: 'ALLOW', locationId: null },
    ])
  }

  function removeOverride(index: number) {
    setDraft((previous) => previous.filter((_, position) => position !== index))
  }

  return (
    <section
      aria-labelledby="overrides-heading"
      className="rounded-xl border border-slate-200 bg-white p-5 shadow-xs"
    >
      <h2 id="overrides-heading" className="text-sm font-semibold text-slate-900">
        Overrides
      </h2>
      <p className="mt-1 text-xs text-slate-500">
        Overrides sit on top of templates: an Allow adds a permission and a Deny removes one. Deny
        always wins.
      </p>

      <div className="mt-4">
        {draft.length > 0 ? (
          <ul className="mb-4 divide-y divide-slate-100 rounded-lg border border-slate-200">
            {draft.map((override, index) => (
              <li
                key={`${override.permission}-${index}`}
                className="flex flex-wrap items-center justify-between gap-3 px-3.5 py-2.5"
              >
                <span className="font-mono text-xs font-medium text-slate-800">
                  {override.permission}
                </span>
                <div className="flex items-center gap-2">
                  <Select
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
                    className="w-28 py-1 text-xs"
                  >
                    <option value="ALLOW">Allow</option>
                    <option value="DENY">Deny</option>
                  </Select>
                  <Button
                    variant="ghost"
                    size="sm"
                    className="text-rose-700 hover:bg-rose-50"
                    onClick={() => removeOverride(index)}
                  >
                    Remove
                  </Button>
                </div>
              </li>
            ))}
          </ul>
        ) : (
          <p className="mb-4 text-sm text-slate-600">This account holds no override.</p>
        )}

        {grantable.length > 0 ? (
          <FormField label="Add an override" className="max-w-md">
            <Select
              value=""
              onChange={(event) => {
                if (event.target.value) {
                  addOverride(event.target.value)
                }
              }}
            >
              <option value="">Choose a permission</option>
              {grantable.map((permission) => (
                <option key={permission.code} value={permission.code}>
                  {permission.code} - {permission.description}
                </option>
              ))}
            </Select>
          </FormField>
        ) : null}

        {mutation.isError ? (
          <div className="mt-4">
            <ErrorState title="Could not update overrides" error={mutation.error} />
          </div>
        ) : null}
        {mutation.isSuccess ? (
          <p role="status" className="mt-3 text-sm font-medium text-emerald-700">
            The overrides were saved.
          </p>
        ) : null}

        <div className="mt-4">
          <Button
            onClick={() => mutation.mutate()}
            isLoading={mutation.isPending}
            loadingText="Saving..."
          >
            Save the overrides
          </Button>
        </div>
      </div>
    </section>
  )
}

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
      <section
        aria-labelledby="delegations-heading"
        className="rounded-xl border border-slate-200 bg-white p-5 shadow-xs"
      >
        <h2 id="delegations-heading" className="text-sm font-semibold text-slate-900">
          Delegation list
        </h2>
        <p className="mt-2 text-sm text-slate-600">
          Only the owner may change the delegation list of a manager.
        </p>
      </section>
    )
  }

  return (
    <section
      aria-labelledby="delegations-heading"
      className="rounded-xl border border-slate-200 bg-white p-5 shadow-xs"
    >
      <h2 id="delegations-heading" className="text-sm font-semibold text-slate-900">
        Delegation list
      </h2>
      <p className="mt-1 text-xs text-slate-500">
        Permissions this manager may grant to staff members.
      </p>

      <div className="mt-4">
        <ul className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
          {delegable.map((code) => (
            <li key={code}>
              <Checkbox
                id={`delegation-${code}`}
                checked={selected.includes(code)}
                disabled={mutation.isPending}
                onChange={(event) =>
                  setSelected((previous) =>
                    event.target.checked
                      ? [...previous, code]
                      : previous.filter((value) => value !== code),
                  )
                }
                label={<span className="font-mono text-xs">{code}</span>}
              />
            </li>
          ))}
        </ul>

        {delegable.length === 0 ? (
          <p className="text-sm text-slate-600">The catalogue could not be read.</p>
        ) : null}

        {mutation.isError ? (
          <div className="mt-4">
            <ErrorState title="Could not save delegations" error={mutation.error} />
          </div>
        ) : null}
        {mutation.isSuccess ? (
          <p role="status" className="mt-3 text-sm font-medium text-emerald-700">
            The delegation list was saved.
          </p>
        ) : null}

        <div className="mt-4">
          <Button
            onClick={() => mutation.mutate()}
            isLoading={mutation.isPending}
            loadingText="Saving..."
          >
            Save the delegation list
          </Button>
        </div>
      </div>
    </section>
  )
}

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
      queryClient.invalidateQueries({ queryKey: ['user', account.id, 'effective-permissions'] })
    },
  })

  const endMutation = useMutation({
    mutationFn: (assignmentId: string) => endAssignment(assignmentId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['user', account.id] })
      queryClient.invalidateQueries({ queryKey: ['user', account.id, 'effective-permissions'] })
    },
  })

  const locations = locationsQuery.data ?? []

  return (
    <section
      aria-labelledby="assignments-heading"
      className="rounded-xl border border-slate-200 bg-white p-5 shadow-xs"
    >
      <h2 id="assignments-heading" className="text-sm font-semibold text-slate-900">
        Where this account works
      </h2>
      <p className="mt-1 text-xs text-slate-500">
        Location-scoped permissions only apply where the account holds an active assignment.
      </p>

      <div className="mt-4">
        {account.locations.length > 0 ? (
          <ul className="mb-4 divide-y divide-slate-100 rounded-lg border border-slate-200">
            {account.locations.map((assignment: AssignmentSummary) => (
              <li
                key={assignment.id}
                className="flex flex-wrap items-center justify-between gap-3 px-3.5 py-2.5 text-sm"
              >
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-medium text-slate-900">
                    {describeLocation(locations, assignment.locationId)}
                  </span>
                  <Badge tone="neutral">
                    {assignment.assignmentType === 'MANAGER' ? 'Manager' : 'Staff'}
                  </Badge>
                  <span className="text-xs text-slate-500">
                    since <DateText value={assignment.startedAt} withTime />
                  </span>
                </div>
                <Button
                  variant="ghost"
                  size="sm"
                  className="text-rose-700 hover:bg-rose-50"
                  onClick={() => endMutation.mutate(assignment.id)}
                >
                  End
                </Button>
              </li>
            ))}
          </ul>
        ) : (
          <p className="mb-4 text-sm text-slate-600">
            This account is not assigned to any location.
          </p>
        )}

        <div className="flex flex-wrap items-end gap-3">
          <FormField label="Location" className="min-w-[200px]">
            <Select value={locationId} onChange={(e) => setLocationId(e.target.value)}>
              <option value="">Choose a location</option>
              {locations.map((location) => (
                <option key={location.id} value={location.id}>
                  {location.name}
                </option>
              ))}
            </Select>
          </FormField>
          <FormField label="Role there" className="w-36">
            <Select
              value={assignmentType}
              onChange={(e) => setAssignmentType(e.target.value as 'MANAGER' | 'STAFF')}
            >
              <option value="STAFF">Staff</option>
              <option value="MANAGER">Manager</option>
            </Select>
          </FormField>
          <Button
            onClick={() => assignMutation.mutate()}
            disabled={!locationId}
            isLoading={assignMutation.isPending}
            loadingText="Assigning..."
          >
            Assign
          </Button>
        </div>

        {assignMutation.isError ? (
          <div className="mt-4">
            <ErrorState title="Could not assign location" error={assignMutation.error} />
          </div>
        ) : null}
        {endMutation.isError ? (
          <div className="mt-4">
            <ErrorState title="Could not end assignment" error={endMutation.error} />
          </div>
        ) : null}
      </div>
    </section>
  )
}

function EffectivePermissionsSection({
  permissions,
  isLoading,
}: {
  permissions: ExplainedPermission[]
  isLoading: boolean
}) {
  const columns: DataTableColumn<ExplainedPermission>[] = [
    {
      key: 'code',
      header: 'Permission',
      sortable: true,
      sortValue: (p) => p.code,
      render: (permission) => (
        <div>
          <span className="font-mono text-xs font-medium text-slate-900">{permission.code}</span>
          <span className="block text-xs text-slate-500">{permission.description}</span>
        </div>
      ),
    },
    {
      key: 'scope',
      header: 'Scope',
      render: (permission) =>
        permission.scope === 'ALL' ? (
          <Badge tone="primary">Everywhere</Badge>
        ) : (
          <Badge tone="neutral">{permission.locationIds.length} location(s)</Badge>
        ),
    },
    {
      key: 'reasons',
      header: 'Why',
      render: (permission) => (
        <span className="text-xs text-slate-600">{permission.reasons.join(', ')}</span>
      ),
    },
  ]

  return (
    <section aria-labelledby="effective-heading" className="space-y-3">
      <div>
        <h2 id="effective-heading" className="text-sm font-semibold text-slate-900">
          Effective permissions
        </h2>
        <p className="mt-0.5 text-xs text-slate-500">
          Resolved permissions after combining templates and overrides.
        </p>
      </div>

      <DataTable
        columns={columns}
        rows={permissions}
        getRowKey={(p) => p.code}
        isLoading={isLoading}
        emptyTitle="No effective permissions"
        emptyDescription="This account holds no effective permission."
      />
    </section>
  )
}

function describeRole(accountType: 'OWNER' | 'MANAGER' | 'STAFF'): string {
  if (accountType === 'OWNER') return 'Owner'
  if (accountType === 'MANAGER') return 'Manager'
  return 'Staff'
}

function profileKey(account: UserDetail): string {
  return [account.fullName, account.phone ?? '', account.email ?? ''].join('|')
}

function templateKey(account: UserDetail): string {
  return account.templates.map((template) => template.id).join('|')
}

function overrideKey(account: UserDetail): string {
  return account.overrides
    .map((override) => `${override.permission}:${override.effect}:${override.locationId ?? '*'}`)
    .join('|')
}

function delegationKey(account: UserDetail): string {
  return account.delegations.join('|')
}

function describeLocation(locations: LocationSummary[], locationId: string | undefined): string {
  if (!locationId) return 'Unknown location'
  const match = locations.find((location) => location.id === locationId)
  return match ? match.name : locationId
}
