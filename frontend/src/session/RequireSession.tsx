import { Navigate, useLocation } from 'react-router-dom'
import type { ReactNode } from 'react'
import { useSession } from './sessionContext'
import ServerWakingUp from '../components/ServerWakingUp'

/**
 * The guard in front of every screen that needs a signed in user.
 *
 * The backend is the real gate: every endpoint checks the session and the
 * permissions. This guard only saves the user from a pointless round trip and
 * shows the login page instead of an empty screen.
 *
 * @param props the children and the permission the screen needs
 * @returns the children, the loading state or the redirect
 */
export default function RequireSession({
  children,
  permission,
}: {
  children: ReactNode
  permission?: string
}) {
  const { user, isLoading, isBackendUnreachable, can } = useSession()
  const location = useLocation()

  if (isLoading) {
    return (
      <div className="mx-auto max-w-md px-4 py-16 text-center text-sm text-slate-600">
        Checking the session...
      </div>
    )
  }

  // The backend could not be reached at all. The free instance is asleep, so
  // the user is not logged out; the waking screen waits and retries.
  if (isBackendUnreachable) {
    return <ServerWakingUp />
  }

  if (!user) {
    // The page the user wanted is remembered, so the login page can send them
    // back to it after a successful sign in.
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }

  // An account that still has to change its password may only open the change
  // password screen. The backend refuses everything else with
  // PASSWORD_CHANGE_REQUIRED, so the screen is sent here before the request is
  // ever made.
  if (user.mustChangePassword && location.pathname !== '/change-password') {
    return <Navigate to="/change-password" replace />
  }

  if (permission && !can(permission)) {
    return (
      <div
        role="alert"
        className="rounded-xl border border-amber-200 bg-amber-50 px-6 py-6 text-sm text-amber-900"
      >
        <p className="font-medium">This screen is not open to your account.</p>
        <p className="mt-1">
          You do not hold the permission <code className="font-mono">{permission}</code>. Ask the
          owner if you need it.
        </p>
      </div>
    )
  }

  return <>{children}</>
}

/**
 * The guard in front of the login page.
 *
 * A user who is already signed in has no business on the login page, so they
 * are sent to the overview.
 *
 * @returns the children or the redirect
 */
export function RequireSignedOut({ children }: { children: ReactNode }) {
  const { user, isLoading } = useSession()

  if (isLoading) {
    return (
      <div className="mx-auto max-w-md px-4 py-16 text-center text-sm text-slate-600">
        Checking the session...
      </div>
    )
  }

  if (user) {
    return <Navigate to="/" replace />
  }

  return <>{children}</>
}

/**
 * The screen shown while the free backend is waking up.
 *
 * @returns the waking screen
 */
export function WakingScreen() {
  return <ServerWakingUp />
}
