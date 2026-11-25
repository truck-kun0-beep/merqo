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
import type { UserSummary } from '../api/models'
import { useSession } from '../session/sessionContext'
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
  Select,
  StatusPill,
  type DataTableColumn,
} from '../components/ui'

/**
 * The people screen showing scoped staff accounts.
 */
export default function UsersPage() {
  const { user } = useSession()
  const queryClient = useQueryClient()
  const [search, setSearch] = useState('')
  const [appliedSearch, setAppliedSearch] = useState('')
  const [page, setPage] = useState(0)
  const [createOpen, setCreateOpen] = useState(false)
  const [createdPassword, setCreatedPassword] = useState<string | undefined>(undefined)
  const isOwner = user?.accountType === 'OWNER'

  const query = useQuery({
    queryKey: ['users', appliedSearch, page],
    queryFn: () => fetchUsers({ q: appliedSearch || undefined, page, size: 25 }),
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

  const columns: DataTableColumn<UserSummary>[] = [
    {
      key: 'name',
      header: 'Name',
      sortable: true,
      sortValue: (row) => row.fullName,
      render: (account) => (
        <div className="font-medium text-slate-900">
          <span>{account.fullName}</span>
          {account.id === user?.id ? (
            <Badge tone="primary" className="ml-2">
              You
            </Badge>
          ) : null}
        </div>
      ),
    },
    {
      key: 'username',
      header: 'Username',
      sortable: true,
      sortValue: (row) => row.username,
      render: (account) => <span className="text-slate-600">{account.username}</span>,
    },
    {
      key: 'role',
      header: 'Role',
      sortable: true,
      sortValue: (row) => row.accountType,
      render: (account) => (
        <Badge tone={account.accountType === 'OWNER' ? 'primary' : 'neutral'}>
          {account.accountType === 'OWNER'
            ? 'Owner'
            : account.accountType === 'MANAGER'
              ? 'Manager'
              : 'Staff'}
        </Badge>
      ),
    },
    {
      key: 'employeeCode',
      header: 'Staff no.',
      render: (account) => (
        <span className="font-mono text-xs text-slate-500">{account.employeeCode ?? '-'}</span>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      render: (account) => (
        <div className="flex flex-wrap items-center gap-1.5">
          <StatusPill status={account.isActive ? 'ACTIVE' : 'INACTIVE'} />
          {account.isDemo ? <Badge tone="primary">Demo</Badge> : null}
        </div>
      ),
    },
    {
      key: 'lastLoginAt',
      header: 'Last sign in',
      render: (account) => (
        <span className="text-xs text-slate-500">
          <DateText value={account.lastLoginAt} withTime fallback="Never" />
        </span>
      ),
    },
    {
      key: 'actions',
      header: 'Access',
      render: (account) => (
        <div className="flex flex-wrap items-center gap-2">
          <Link
            to={`/users/${account.id}`}
            className="text-xs font-semibold text-merqo-700 hover:underline"
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
              Reset password
            </button>
          ) : null}
        </div>
      ),
    },
  ]

  const actionError = deactivateMutation.error ?? activateMutation.error ?? resetMutation.error
  const tempPassword = resetMutation.data?.temporaryPassword ?? createdPassword

  return (
    <div className="space-y-6">
      <PageHeader
        title="People"
        description="Staff and manager accounts within your branch scope."
        actions={
          isOwner ? <Button onClick={() => setCreateOpen(true)}>Add an account</Button> : undefined
        }
      />

      <form
        className="flex flex-wrap items-center gap-2"
        onSubmit={(event) => {
          event.preventDefault()
          setPage(0)
          setAppliedSearch(search.trim())
        }}
      >
        <label htmlFor="user-search" className="sr-only">
          Search by name, username or staff number
        </label>
        <Input
          id="user-search"
          type="search"
          value={search}
          placeholder="Search by name, username or staff number"
          onChange={(event) => setSearch(event.target.value)}
          className="max-w-sm"
        />
        <Button type="submit" variant="outline">
          Search
        </Button>
      </form>

      {tempPassword ? (
        <div
          role="status"
          className="rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-900"
        >
          Temporary password: <code className="font-mono font-semibold">{tempPassword}</code>. It is
          shown once and the account must change it at the next sign in.
        </div>
      ) : null}

      {actionError ? <ErrorState title="Action failed" error={actionError} /> : null}

      {query.error ? (
        <ErrorState
          title="The accounts could not be loaded."
          error={query.error}
          onRetry={() => query.refetch()}
        />
      ) : (
        <DataTable
          columns={columns}
          rows={users}
          getRowKey={(row) => row.id}
          isLoading={query.isPending}
          emptyTitle="No accounts found"
          emptyDescription="No account matches this search."
          pagination={
            query.data
              ? {
                  page: query.data.page,
                  totalPages: query.data.totalPages,
                  totalItems: query.data.totalItems,
                  pageSize: query.data.size,
                  onPageChange: setPage,
                }
              : undefined
          }
        />
      )}

      <CreateUserModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={(temporaryPassword) => {
          if (temporaryPassword) {
            setCreatedPassword(temporaryPassword)
          }
        }}
      />
    </div>
  )
}

function CreateUserModal({
  open,
  onClose,
  onCreated,
}: {
  open: boolean
  onClose: () => void
  onCreated: (temporaryPassword?: string) => void
}) {
  const queryClient = useQueryClient()
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
    onSuccess: (created) => {
      setUsername('')
      setFullName('')
      setEmail('')
      queryClient.invalidateQueries({ queryKey: ['users'] })
      onCreated(created.temporaryPassword)
      onClose()
    },
  })

  return (
    <Modal open={open} onClose={onClose} title="New account">
      <form
        onSubmit={(event) => {
          event.preventDefault()
          mutation.mutate()
        }}
        className="space-y-4"
      >
        <div className="grid gap-3 sm:grid-cols-2">
          <FormField label="Username" required>
            <Input value={username} onChange={(e) => setUsername(e.target.value)} required />
          </FormField>
          <FormField label="Full name">
            <Input value={fullName} onChange={(e) => setFullName(e.target.value)} />
          </FormField>
          <FormField label="Email">
            <Input type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
          </FormField>
          <FormField label="Kind">
            <Select
              value={accountType}
              onChange={(e) => setAccountType(e.target.value as 'MANAGER' | 'STAFF')}
            >
              <option value="STAFF">Staff</option>
              <option value="MANAGER">Manager</option>
            </Select>
          </FormField>
        </div>

        <p className="text-xs text-slate-500">
          The server generates a temporary password that the user must change at first sign in.
        </p>

        {mutation.isError ? (
          <ErrorState title="Could not create account" error={mutation.error} />
        ) : null}

        <div className="flex justify-end gap-2 pt-2">
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button
            type="submit"
            isLoading={mutation.isPending}
            loadingText="Saving..."
            disabled={!username.trim()}
          >
            Create the account
          </Button>
        </div>
      </form>
    </Modal>
  )
}
