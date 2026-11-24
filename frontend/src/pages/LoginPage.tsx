import { useState, type FormEvent } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { fetchAuthStatus, fetchDemoAccounts } from '../api/auth'
import { ApiError, BackendUnreachableError } from '../api/types'
import { useSession } from '../session/sessionContext'
import ServerWakingUp from '../components/ServerWakingUp'
import { Badge, Button, FormField, Input, Skeleton } from '../components/ui'

/**
 * The login page.
 *
 * Supports username/email + password login and, when demo mode is enabled,
 * one-click demo account cards.
 */
export default function LoginPage() {
  const { signIn, signInAsDemo } = useSession()
  const navigate = useNavigate()
  const location = useLocation()

  const [identifier, setIdentifier] = useState('')
  const [password, setPassword] = useState('')
  const [pendingDemoKey, setPendingDemoKey] = useState<string | undefined>(undefined)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [error, setError] = useState<{ message: string; correlationId?: string } | undefined>(
    undefined,
  )

  const statusQuery = useQuery({ queryKey: ['auth', 'status'], queryFn: fetchAuthStatus })
  const demoQuery = useQuery({
    queryKey: ['auth', 'demo-accounts'],
    queryFn: fetchDemoAccounts,
    enabled: statusQuery.data?.demoMode === true,
  })

  const demoMode = statusQuery.data?.demoMode === true
  const businessName = statusQuery.data?.businessName || 'Merqo'
  const setupRequired = statusQuery.data?.setupRequired === true

  const from = (location.state as { from?: string } | null)?.from ?? '/'

  const goToApp = () => {
    navigate(from, { replace: true })
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError(undefined)
    setIsSubmitting(true)
    try {
      await signIn(identifier.trim(), password)
      goToApp()
    } catch (caught) {
      setError(readError(caught))
    } finally {
      setIsSubmitting(false)
    }
  }

  async function handleDemoLogin(accountKey: string) {
    setError(undefined)
    setPendingDemoKey(accountKey)
    try {
      await signInAsDemo(accountKey)
      goToApp()
    } catch (caught) {
      setError(readError(caught))
    } finally {
      setPendingDemoKey(undefined)
    }
  }

  if (statusQuery.error instanceof BackendUnreachableError) {
    return <ServerWakingUp />
  }

  if (demoQuery.error instanceof BackendUnreachableError) {
    return <ServerWakingUp />
  }

  return (
    <div className="flex min-h-full items-center justify-center bg-slate-50 px-4 py-10">
      <div className="w-full max-w-md space-y-6">
        <div className="text-center">
          <div className="mx-auto mb-3 inline-flex h-11 w-11 items-center justify-center rounded-xl bg-merqo-700 text-base font-bold text-white shadow-sm">
            {businessName.slice(0, 1).toUpperCase()}
          </div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-900">{businessName}</h1>
          <p className="mt-1 text-sm text-slate-600">
            Sign in with your staff credentials to continue.
          </p>
        </div>

        {setupRequired ? (
          <div
            role="alert"
            className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900"
          >
            <p className="font-semibold">This system has no owner yet.</p>
            <p className="mt-1 text-xs leading-relaxed">
              Set <code className="font-mono">INITIAL_OWNER_USERNAME</code> and{' '}
              <code className="font-mono">INITIAL_OWNER_PASSWORD</code> on the server and restart
              it. There is deliberately no public setup page.
            </p>
          </div>
        ) : null}

        <form
          onSubmit={handleSubmit}
          className="space-y-4 rounded-xl border border-slate-200 bg-white p-6 shadow-sm"
        >
          <FormField label="Username or email" htmlFor="identifier" required>
            <Input
              id="identifier"
              name="identifier"
              type="text"
              autoComplete="username"
              required
              value={identifier}
              onChange={(event) => setIdentifier(event.target.value)}
            />
          </FormField>

          <FormField label="Password" htmlFor="password" required>
            <Input
              id="password"
              name="password"
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </FormField>

          {error ? (
            <div
              role="alert"
              className="rounded-lg border border-rose-200 bg-rose-50 px-3.5 py-2.5 text-sm text-rose-800"
            >
              <p>{error.message}</p>
              {error.correlationId && error.correlationId !== 'unknown' ? (
                <p className="mt-0.5 text-xs text-rose-700/80">
                  Reference ID: <code className="font-mono">{error.correlationId}</code>
                </p>
              ) : null}
            </div>
          ) : null}

          <Button
            type="submit"
            isLoading={isSubmitting}
            loadingText="Signing in..."
            className="w-full"
            size="lg"
          >
            Sign in
          </Button>
        </form>

        {demoMode ? (
          <section
            aria-labelledby="demo-heading"
            className="rounded-xl border border-merqo-200 bg-merqo-50/70 p-5"
          >
            <div className="flex items-center justify-between gap-2">
              <h2 id="demo-heading" className="text-sm font-semibold text-merqo-950">
                Demo accounts
              </h2>
              <Badge tone="warning">One-click sign in</Badge>
            </div>
            <p className="mt-1 text-xs text-merqo-900/80">
              Select an account below to explore its branch scope and permissions.
            </p>

            {demoQuery.isPending ? (
              <div className="mt-3 space-y-2">
                <Skeleton className="h-16 w-full" />
                <Skeleton className="h-16 w-full" />
              </div>
            ) : null}

            {demoQuery.error ? (
              <p role="alert" className="mt-3 text-sm text-rose-700">
                {readError(demoQuery.error).message}
              </p>
            ) : null}

            <ul className="mt-3 space-y-2">
              {(demoQuery.data ?? []).map((account) => {
                const isPendingThis = pendingDemoKey === account.accountKey
                return (
                  <li key={account.accountKey}>
                    <button
                      type="button"
                      onClick={() => handleDemoLogin(account.accountKey)}
                      disabled={pendingDemoKey !== undefined}
                      className="group w-full rounded-lg border border-merqo-200 bg-white px-4 py-3 text-left shadow-xs transition-all hover:border-merqo-500 hover:shadow-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-merqo-500 disabled:cursor-not-allowed disabled:opacity-60"
                    >
                      <div className="flex items-center justify-between gap-2">
                        <span className="text-sm font-semibold text-slate-900 group-hover:text-merqo-800">
                          {isPendingThis ? 'Signing in...' : account.displayName}
                        </span>
                        <Badge tone="primary">{account.role}</Badge>
                      </div>
                      <span className="mt-1 block text-xs text-slate-600">
                        {account.description}
                      </span>
                    </button>
                  </li>
                )
              })}
            </ul>
          </section>
        ) : null}
      </div>
    </div>
  )
}

function readError(caught: unknown): { message: string; correlationId?: string } {
  if (caught instanceof ApiError) {
    if (caught.errorCode === 'INVALID_CREDENTIALS') {
      return { message: 'Invalid username or password.', correlationId: caught.correlationId }
    }
    if (caught.errorCode === 'ACCOUNT_LOCKED') {
      return {
        message: 'This account is locked. Try again later.',
        correlationId: caught.correlationId,
      }
    }
    if (caught.errorCode === 'TOO_MANY_REQUESTS') {
      return {
        message: 'Too many attempts. Wait a moment and try again.',
        correlationId: caught.correlationId,
      }
    }
    return { message: caught.message, correlationId: caught.correlationId }
  }
  if (caught instanceof BackendUnreachableError) {
    return { message: 'The server could not be reached. It may still be waking up.' }
  }
  return { message: 'Something went wrong. Try again.' }
}
