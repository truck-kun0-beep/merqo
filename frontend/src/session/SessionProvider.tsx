import { useCallback, useMemo, type ReactNode } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  fetchCurrentUser,
  login as loginRequest,
  loginAsDemoAccount,
  logout as logoutRequest,
} from '../api/auth'
import { ApiError } from '../api/types'
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

  const query = useQuery({
    queryKey: SESSION_QUERY_KEY,
    queryFn: fetchCurrentUser,
    // A 401 means "nobody is signed in", which is a normal answer and not a
    // failure, so it must not be retried and must not be shown as an error.
    retry: (failureCount, error) => {
      if (error instanceof ApiError && error.status === 401) {
        return false
      }
      return failureCount < 2
    },
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
      isLoading: query.isPending,
      can,
      signIn,
      signInAsDemo,
      signOut,
      requireSession,
    }),
    [user, query.isPending, can, signIn, signInAsDemo, signOut, requireSession],
  )

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
}
