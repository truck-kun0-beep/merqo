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

  const user = query.data

  const can = useCallback(
    (code: string) => user?.permissions.some((permission) => permission.code === code) ?? false,
    [user],
  )

  const signIn = useCallback(
    async (identifier: string, password: string) => {
      const signedIn = await loginRequest(identifier, password)
      queryClient.setQueryData(SESSION_QUERY_KEY, signedIn)
      return signedIn
    },
    [queryClient],
  )

  const signInAsDemo = useCallback(
    async (accountKey: string) => {
      const signedIn = await loginAsDemoAccount(accountKey)
      queryClient.setQueryData(SESSION_QUERY_KEY, signedIn)
      return signedIn
    },
    [queryClient],
  )

  const signOut = useCallback(async () => {
    try {
      await logoutRequest()
    } finally {
      queryClient.setQueryData(SESSION_QUERY_KEY, undefined)
      queryClient.clear()
    }
  }, [queryClient])

  const requireSession = useCallback(() => {
    if (query.error instanceof ApiError && query.error.status === 401) {
      queryClient.setQueryData(SESSION_QUERY_KEY, undefined)
    }
  }, [query.error, queryClient])

  const value = useMemo<SessionState>(
    () => ({
      user,
      isLoading: query.isPending && !unreachable,
      isBackendUnreachable: unreachable,
      can,
      signIn,
      signInAsDemo,
      signOut,
      requireSession,
    }),
    [user, query.isPending, unreachable, can, signIn, signInAsDemo, signOut, requireSession],
  )

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
}
