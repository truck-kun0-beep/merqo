import { useState, type FormEvent } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { changePassword } from '../api/auth'
import { ApiError } from '../api/types'
import { SESSION_QUERY_KEY, useSession } from '../session/sessionContext'

/**
 * The change password screen.
 *
 * It is the one screen a user with a pending password change may open. The backend refuses every
 * business request of such an account with `PASSWORD_CHANGE_REQUIRED`, so the session guard sends
 * the user here until the password has been changed.
 *
 * @returns the change password screen element
 */
export default function ChangePasswordPage() {
  const { user } = useSession()
  const queryClient = useQueryClient()

  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')

  const mutation = useMutation({
    mutationFn: () => changePassword(currentPassword, newPassword),
    onSuccess: async () => {
      // The backend cleared the pending flag. The session is read again, so the guard lets the
      // user into the application without a new sign in.
      await queryClient.invalidateQueries({ queryKey: SESSION_QUERY_KEY })
      setCurrentPassword('')
      setNewPassword('')
      setConfirmation('')
    },
  })

  const confirmationMatches = confirmation.length === 0 || confirmation === newPassword

  /**
   * Sends the new password to the backend.
   *
   * @param event the submit event
   */
  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    mutation.mutate()
  }

  return (
    <div className="mx-auto max-w-md space-y-6 py-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight text-slate-900">
          Change your password
        </h1>
        <p className="mt-1 text-sm text-slate-600">
          {user?.mustChangePassword
            ? 'You must choose a new password before you can use the application.'
            : 'Choose a new password for your account.'}
        </p>
      </div>

      <form
        onSubmit={handleSubmit}
        className="space-y-4 rounded-xl border border-slate-200 bg-white p-5 shadow-sm"
      >
        <div>
          <label htmlFor="current-password" className="block text-sm font-medium text-slate-700">
            Current password
          </label>
          <input
            id="current-password"
            name="currentPassword"
            type="password"
            autoComplete="current-password"
            required
            value={currentPassword}
            onChange={(event) => setCurrentPassword(event.target.value)}
            className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900 outline-none focus:border-merqo-500 focus:ring-2 focus:ring-merqo-100"
          />
        </div>

        <div>
          <label htmlFor="new-password" className="block text-sm font-medium text-slate-700">
            New password
          </label>
          <input
            id="new-password"
            name="newPassword"
            type="password"
            autoComplete="new-password"
            required
            minLength={8}
            value={newPassword}
            onChange={(event) => setNewPassword(event.target.value)}
            className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900 outline-none focus:border-merqo-500 focus:ring-2 focus:ring-merqo-100"
          />
          <p className="mt-1 text-xs text-slate-500">
            At least 8 characters. It may not be the same as your username.
          </p>
        </div>

        <div>
          <label htmlFor="confirmation" className="block text-sm font-medium text-slate-700">
            Repeat the new password
          </label>
          <input
            id="confirmation"
            name="confirmation"
            type="password"
            autoComplete="new-password"
            required
            value={confirmation}
            onChange={(event) => setConfirmation(event.target.value)}
            className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900 outline-none focus:border-merqo-500 focus:ring-2 focus:ring-merqo-100"
          />
          {confirmation.length > 0 && !confirmationMatches ? (
            <p role="alert" className="mt-1 text-xs text-rose-700">
              The two passwords are not the same.
            </p>
          ) : null}
        </div>

        {mutation.isError ? (
          <p role="alert" className="text-sm text-rose-700">
            {readError(mutation.error)}
          </p>
        ) : null}

        {mutation.isSuccess ? (
          <p role="status" className="text-sm text-emerald-700">
            The password was changed.
          </p>
        ) : null}

        <button
          type="submit"
          disabled={mutation.isPending || !confirmationMatches}
          className="w-full rounded-md bg-merqo-700 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-merqo-800 disabled:cursor-not-allowed disabled:opacity-60"
        >
          {mutation.isPending ? 'Saving...' : 'Change the password'}
        </button>
      </form>
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
      return 'The current password is not right.'
    }
    if (caught.errorCode === 'PASSWORD_POLICY') {
      return caught.message
    }
    return caught.message
  }
  return 'Something went wrong. Try again.'
}
