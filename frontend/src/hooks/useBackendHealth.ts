import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { fetchHealth } from '../api/health'
import { BackendUnreachableError } from '../api/types'

/**
 * The shape the health hook returns.
 */
export interface HealthState {
  isLoading: boolean
  isWakingUp: boolean
  isOffline: boolean
  report: Awaited<ReturnType<typeof fetchHealth>> | undefined
  error: Error | undefined
}

/**
 * Loads the backend health report.
 *
 * The free backend sleeps after fifteen idle minutes and needs about a minute
 * to wake up. While that happens the request fails with a network error, so
 * the hook treats a network failure as "waking up" and keeps retrying instead
 * of showing an error.
 *
 * The "waking up" state is set by the error callback of the query, which fires
 * on the very first failed attempt. Waiting for all retries to be exhausted
 * would leave the user looking at a spinner for over a minute.
 *
 * @returns the health state
 */
export function useBackendHealth(): HealthState {
  const [unreachable, setUnreachable] = useState(false)

  const query = useQuery({
    queryKey: ['backend-health'],
    queryFn: async () => {
      try {
        const report = await fetchHealth()
        setUnreachable(false)
        return report
      } catch (error) {
        // The state is set inside the query function so that it changes on the
        // very first failed attempt, not only after every retry has been used.
        if (error instanceof BackendUnreachableError) {
          setUnreachable(true)
        }
        throw error
      }
    },
    retry: (failureCount, error) => {
      if (error instanceof BackendUnreachableError) {
        return failureCount < 8
      }
      return failureCount < 2
    },
    retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 15_000),
    refetchInterval: 30_000,
    staleTime: 10_000,
  })

  return {
    isLoading: query.isPending && !unreachable,
    isWakingUp: unreachable,
    isOffline: unreachable,
    report: query.data,
    error: query.error ?? undefined,
  }
}
