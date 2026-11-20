import { apiRequest } from './client'
import type { HealthReport, PingReport } from './types'

/**
 * Reads the full health report of the backend.
 *
 * @returns the health report
 */
export function fetchHealth(): Promise<HealthReport> {
  return apiRequest<HealthReport>('/health')
}

/**
 * Reads the trivial ping used by the uptime monitor.
 *
 * @returns the ping report
 */
export function fetchPing(): Promise<PingReport> {
  return apiRequest<PingReport>('/health/ping')
}
