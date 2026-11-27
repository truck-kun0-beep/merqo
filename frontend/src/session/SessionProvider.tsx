import { useCallback, useMemo, useState, type ReactNode } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  fetchCurrentUser,
  login as loginRequest,
  loginAsDemoAccount,
  logout as logoutRequest,
} from '../api/auth'
import { ApiError, BackendUnreachableError } from '../api/types'
import { SESSION_QUERY_KEY, SessionContext, type SessionState } from './sessionContext'

/**
 * The session of the browser.
 *
 * The session itself lives in an `HttpOnly` cookie on the server. The
 * frontend only ever asks the server "who am I?" and caches the answer for a
 * short time. There is no token in `localStorage`, so a script on the page
 * cannot steal the session.
 */

/**
 * The provider of the session.
 *
 * @param props the children of the provider
 * @returns the provider element
 */
export function SessionProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()

  // The flag is set inside the query function so that it changes on the very
  // first failed attempt, not only after every retry has been used. Waiting
  // for the retries would leave the user looking at a spinner for a minute.
  const [unreachable, setUnreachable] = useState(false)

  // The flag that says "the user asked to sign out".
  //
  // The answer of the server stays in the cache for a while, and the cache is
  // not told that the session ended. `setQueryData` with `undefined` writes
  // nothing at all, so the cached user would survive the sign out and the
  // screens would keep showing the signed in shell. This flag is what the
  // screens read, so the sign out takes effect on the very next render.
  const [signedOut, setSignedOut] = useState(false)

  const query = useQuery({
    queryKey: SESSION_QUERY_KEY,
    queryFn: async () => {
      try {
        const user = await fetchCurrentUser()
        setUnreachable(false)
        return user
      } catch (error) {
        if (error instanceof BackendUnreachableError) {
          setUnreachable(true)
        }
        if (error instanceof ApiError && error.status === 401) {
          // Nobody is signed in any more, so the screens must not keep showing
          // the signed in shell with the answer the cache still holds.
          setSignedOut(true)
        }
        throw error
      }
    },
    // A 401 means "nobody is signed in", which is a normal answer and not a
    // failure, so it must not be retried and must not be shown as an error.
    // A backend that cannot be reached is the free instance waking up, so it
    // is retried for a while before the user is told anything.
    retry: (failureCount, error) => {
      if (error instanceof ApiError && error.status === 401) {
        return false
      }
      if (error instanceof BackendUnreachableError) {
        return failureCount < 8
      }
      return failureCount < 2
    },
    retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 15_000),
    staleTime: 30_000,
  })

  const user = signedOut ? undefined : query.data

  const can = useCallback(
    (code: string) => user?.permissions.some((permission) => permission.code === code) ?? false,
    [user],
  )

  const signIn = useCallback(
    async (identifier: string, password: string) => {
      const signedIn = await loginRequest(identifier, password)
      setSignedOut(false)
      queryClient.setQueryData(SESSION_QUERY_KEY, signedIn)
      return signedIn
    },
    [queryClient],
  )

  const signInAsDemo = useCallback(
    async (accountKey: string) => {
      const signedIn = await loginAsDemoAccount(accountKey)
      setSignedOut(false)
      queryClient.setQueryData(SESSION_QUERY_KEY, signedIn)
      return signedIn
    },
    [queryClient],
  )

  const signOut = useCallback(async () => {
    try {
      await logoutRequest()
    } finally {
      // The session is gone on the server, so the cached answer of "who am I?"
      // has to go with it. Writing `undefined` with `setQueryData` does
      // nothing at all, so the cache is emptied and the flag is raised.
      setSignedOut(true)
      queryClient.clear()
    }
  }, [queryClient])

  const requireSession = useCallback(() => {
    if (query.error instanceof ApiError && query.error.status === 401) {
      setSignedOut(true)
    }
  }, [query.error])

  const value = useMemo<SessionState>(
    () => ({
      user,
      // A signed out session is known, so the screens must not wait for a
      // call that cannot succeed. The screen would show "checking the
      // session" for a moment after every sign out.
      isLoading: query.isPending && !unreachable && !signedOut,
      isBackendUnreachable: unreachable,
      can,
      signIn,
      signInAsDemo,
      signOut,
      requireSession,
    }),
    [
      user,
      query.isPending,
      unreachable,
      signedOut,
      can,
      signIn,
      signInAsDemo,
      signOut,
      requireSession,
    ],
  )

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
}
