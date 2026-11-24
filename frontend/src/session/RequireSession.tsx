import { Navigate, useLocation } from 'react-router-dom'
import type { ReactNode } from 'react'
import { useSession } from '../session/sessionContext'
import ServerWakingUp from '../components/ServerWakingUp'

/**
 * Guard in front of every screen that needs a signed-in user and optional permission.
 */
export default function RequireSession({
  children,
  permission,
  anyPermission,
}: {
  children: ReactNode
  permission?: string
  anyPermission?: string[]
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

  if (isBackendUnreachable) {
    return <ServerWakingUp />
  }

  if (!user) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }

  if (user.mustChangePassword && location.pathname !== '/change-password') {
    return <Navigate to="/change-password" replace />
  }

  const missingSingle = permission && !can(permission)
  const missingAny =
    anyPermission && anyPermission.length > 0 && !anyPermission.some((code) => can(code))

  if (missingSingle || missingAny) {
    const requiredLabel = permission ?? anyPermission?.join(' or ')
    return (
      <div
        role="alert"
        className="rounded-xl border border-amber-200 bg-amber-50 px-6 py-6 text-sm text-amber-900"
      >
        <p className="font-semibold">This screen is not open to your account.</p>
        <p className="mt-1">
          You do not hold the permission <code className="font-mono">{requiredLabel}</code>. Ask
          the owner if you need it.
        </p>
      </div>
    )
  }

  return <>{children}</>
}

/**
 * Guard in front of the login page.
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
 */
export function WakingScreen() {
  return <ServerWakingUp />
}
