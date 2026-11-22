import { createContext, useContext } from 'react'
import type { CurrentUser } from '../api/models'

/**
 * The state the session context carries.
 */
export interface SessionState {
  /** The signed in user, or `undefined` when nobody is signed in. */
  user: CurrentUser | undefined
  /** Whether the first call to the server is still running. */
  isLoading: boolean
  /** Whether the signed in user holds a permission. */
  can: (code: string) => boolean
  /** Signs a user in with a username or an email address. */
  signIn: (identifier: string, password: string) => Promise<CurrentUser>
  /** Signs a demo account in with one click. */
  signInAsDemo: (accountKey: string) => Promise<CurrentUser>
  /** Ends the session. */
  signOut: () => Promise<void>
  /** Refuses the page when the session ended and sends the user to the login page. */
  requireSession: () => void
}

/**
 * The context itself. It is `undefined` outside the provider, which is how the
 * hook below can give a clear error instead of failing later.
 */
export const SessionContext = createContext<SessionState | undefined>(undefined)

/**
 * The query key of the signed in user.
 */
export const SESSION_QUERY_KEY = ['session', 'me'] as const

/**
 * Reads the session.
 *
 * @returns the session state
 */
export function useSession(): SessionState {
  const context = useContext(SessionContext)
  if (!context) {
    throw new Error('useSession must be used inside a SessionProvider')
  }
  return context
}
