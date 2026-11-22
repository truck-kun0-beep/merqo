import { apiRequest } from './client'
import type { AuthStatus, CurrentUser, DemoAccount } from './models'

/**
 * The authentication calls of Merqo.
 *
 * Every function here is a thin wrapper around one endpoint. The session
 * itself lives in an `HttpOnly` cookie, so nothing is stored in
 * `localStorage` and nothing has to be sent by hand.
 */

/**
 * Reads whether the system still needs an owner and whether demo mode is on.
 *
 * @returns the setup state
 */
export function fetchAuthStatus(): Promise<AuthStatus> {
  return apiRequest<AuthStatus>('/auth/status')
}

/**
 * Signs a user in with a username or an email address.
 *
 * @param identifier the username or the email address
 * @param password the password
 * @returns the signed in user
 */
export function login(identifier: string, password: string): Promise<CurrentUser> {
  return apiRequest<CurrentUser>('/auth/login', {
    method: 'POST',
    body: JSON.stringify({ identifier, password }),
  })
}

/**
 * Signs a demo account in with one click.
 *
 * @param accountKey the stable key of the demo account
 * @returns the signed in user
 */
export function loginAsDemoAccount(accountKey: string): Promise<CurrentUser> {
  return apiRequest<CurrentUser>('/demo/login', {
    method: 'POST',
    body: JSON.stringify({ accountKey }),
  })
}

/**
 * Reads the signed in user.
 *
 * @returns the signed in user
 */
export function fetchCurrentUser(): Promise<CurrentUser> {
  return apiRequest<CurrentUser>('/auth/me')
}

/**
 * Ends the session.
 */
export async function logout(): Promise<void> {
  await apiRequest<void>('/auth/logout', { method: 'POST' })
}

/**
 * Changes the password of the signed in user.
 *
 * @param currentPassword the current password
 * @param newPassword the new password
 */
export async function changePassword(currentPassword: string, newPassword: string): Promise<void> {
  await apiRequest<void>('/auth/change-password', {
    method: 'POST',
    body: JSON.stringify({ currentPassword, newPassword }),
  })
}

/**
 * Reads the demo accounts, when demo mode is on.
 *
 * @returns the demo accounts
 */
export function fetchDemoAccounts(): Promise<DemoAccount[]> {
  return apiRequest<DemoAccount[]>('/demo/accounts')
}
