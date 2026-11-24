import { useState, type FormEvent } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { fetchAuthStatus, fetchDemoAccounts } from '../api/auth'
import { ApiError, BackendUnreachableError } from '../api/types'
import { useSession } from '../session/sessionContext'
import ServerWakingUp from '../components/ServerWakingUp'

/**
 * The login page.
 *
 * Three ways in:
 *
 * 1. A username (or email address) and a password.
 * 2. One click on a demo account, when demo mode is on.
 * 3. Nothing else. There is no "forgot password" link and no public setup
 *    page, because either of them would let a stranger take the system over.
 *
 * @returns the login page element
 */
export default function LoginPage() {
  const { signIn, signInAsDemo } = useSession()
  const navigate = useNavigate()
  const location = useLocation()

  const [identifier, setIdentifier] = useState('')
  const [password, setPassword] = useState('')
  const [pendingDemoKey, setPendingDemoKey] = useState<string | undefined>(undefined)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [error, setError] = useState<string | undefined>(undefined)

  const statusQuery = useQuery({ queryKey: ['auth', 'status'], queryFn: fetchAuthStatus })
  const demoQuery = useQuery({
    queryKey: ['auth', 'demo-accounts'],
    queryFn: fetchDemoAccounts,
    enabled: statusQuery.data?.demoMode === true,
  })

  const demoMode = statusQuery.data?.demoMode === true
  const businessName = statusQuery.data?.businessName || 'Merqo'
  const setupRequired = statusQuery.data?.setupRequired === true

  /** The page the user wanted before the login page sent them here. */
  const from = (location.state as { from?: string } | null)?.from ?? '/'

  /**
   * Sends the user to the overview after a successful sign in.
   */
  const goToApp = () => {
    navigate(from, { replace: true })
  }

  /**
   * Signs a user in with the form.
   *
   * @param event the submit event
   */
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

  /**
   * Signs a demo account in with one click.
   *
   * @param accountKey the key of the demo account
   */
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
    <div className="mx-auto flex max-w-md flex-col gap-6 py-8">
      <div className="text-center">
        <h1 className="text-2xl font-semibold tracking-tight text-slate-900">{businessName}</h1>
        <p className="mt-1 text-sm text-slate-600">
          Sign in to run the shop. Merqo keeps you in one branch or in the whole business, depending
          on your account.
        </p>
      </div>

      {setupRequired ? (
        <div
          role="alert"
          className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900"
        >
          <p className="font-medium">This system has no owner yet.</p>
          <p className="mt-1">
            Set <code className="font-mono">INITIAL_OWNER_USERNAME</code> and{' '}
            <code className="font-mono">INITIAL_OWNER_PASSWORD</code> on the server and restart it.
            There is deliberately no public setup page.
          </p>
        </div>
      ) : null}

      <form
        onSubmit={handleSubmit}
        className="space-y-4 rounded-xl border border-slate-200 bg-white p-5 shadow-sm"
      >
        <div>
          <label htmlFor="identifier" className="block text-sm font-medium text-slate-700">
            Username or email
          </label>
          <input
            id="identifier"
            name="identifier"
            type="text"
            autoComplete="username"
            required
            value={identifier}
            onChange={(event) => setIdentifier(event.target.value)}
            className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900 outline-none focus:border-merqo-500 focus:ring-2 focus:ring-merqo-100"
          />
        </div>

        <div>
          <label htmlFor="password" className="block text-sm font-medium text-slate-700">
            Password
          </label>
          <input
            id="password"
            name="password"
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900 outline-none focus:border-merqo-500 focus:ring-2 focus:ring-merqo-100"
          />
        </div>

        {error ? (
          <p role="alert" className="text-sm text-rose-700">
            {error}
          </p>
        ) : null}

        <button
          type="submit"
          disabled={isSubmitting}
          className="w-full rounded-md bg-merqo-700 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-merqo-800 disabled:cursor-not-allowed disabled:opacity-60"
        >
          {isSubmitting ? 'Signing in...' : 'Sign in'}
        </button>
      </form>

      {demoMode ? (
        <section
          aria-labelledby="demo-heading"
          className="rounded-xl border border-merqo-200 bg-merqo-50 p-5"
        >
          <h2 id="demo-heading" className="text-sm font-semibold text-merqo-900">
            Demo accounts
          </h2>
          <p className="mt-1 text-xs text-merqo-800">
            One click signs you in. Every account shows a different scope.
          </p>

          {demoQuery.isPending ? (
            <p className="mt-3 text-sm text-merqo-800">Loading the demo accounts...</p>
          ) : null}

          {demoQuery.error ? (
            <p role="alert" className="mt-3 text-sm text-rose-700">
              {readError(demoQuery.error)}
            </p>
          ) : null}

          <ul className="mt-3 space-y-2">
            {(demoQuery.data ?? []).map((account) => (
              <li key={account.accountKey}>
                <button
                  type="button"
                  onClick={() => handleDemoLogin(account.accountKey)}
                  disabled={pendingDemoKey !== undefined}
                  className="w-full rounded-lg border border-merqo-200 bg-white px-4 py-3 text-left transition-colors hover:border-merqo-400 disabled:cursor-not-allowed disabled:opacity-60"
                >
                  <span className="block text-sm font-semibold text-slate-900">
                    {pendingDemoKey === account.accountKey ? 'Signing in...' : account.displayName}
                  </span>
                  <span className="block text-xs text-slate-600">{account.role}</span>
                  <span className="mt-0.5 block text-xs text-slate-500">{account.description}</span>
                </button>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </div>
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
    if (caught.errorCode === 'INVALID_CREDENTIALS') {
      return 'Invalid username or password.'
    }
    if (caught.errorCode === 'ACCOUNT_LOCKED') {
      return 'This account is locked. Try again later.'
    }
    if (caught.errorCode === 'TOO_MANY_REQUESTS') {
      return 'Too many attempts. Wait a moment and try again.'
    }
    return caught.message
  }
  if (caught instanceof BackendUnreachableError) {
    return 'The server could not be reached. It may still be waking up.'
  }
  return 'Something went wrong. Try again.'
}
