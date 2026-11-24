import { useState, type FormEvent } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { changePassword } from '../api/auth'
import { ApiError } from '../api/types'
import { Button, ErrorState, FormField, Input, PageHeader } from '../components/ui'
import { SESSION_QUERY_KEY, useSession } from '../session/sessionContext'

/**
 * The change password screen.
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
      await queryClient.invalidateQueries({ queryKey: SESSION_QUERY_KEY })
      setCurrentPassword('')
      setNewPassword('')
      setConfirmation('')
    },
  })

  const confirmationMatches = confirmation.length === 0 || confirmation === newPassword

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    mutation.mutate()
  }

  return (
    <div className="mx-auto max-w-lg space-y-6">
      <PageHeader
        title="Change password"
        description={
          user?.mustChangePassword
            ? 'You must choose a new password before using the rest of the application.'
            : 'Update the password used to sign in to your account.'
        }
      />

      <form
        onSubmit={handleSubmit}
        className="space-y-4 rounded-xl border border-slate-200 bg-white p-6 shadow-xs"
      >
        <FormField label="Current password" htmlFor="current-password" required>
          <Input
            id="current-password"
            name="currentPassword"
            type="password"
            autoComplete="current-password"
            required
            value={currentPassword}
            onChange={(event) => setCurrentPassword(event.target.value)}
          />
        </FormField>

        <FormField
          label="New password"
          htmlFor="new-password"
          hint="At least 8 characters. It may not be the same as your username."
          required
        >
          <Input
            id="new-password"
            name="newPassword"
            type="password"
            autoComplete="new-password"
            required
            minLength={8}
            value={newPassword}
            onChange={(event) => setNewPassword(event.target.value)}
          />
        </FormField>

        <FormField
          label="Repeat the new password"
          htmlFor="confirmation"
          error={
            confirmation.length > 0 && !confirmationMatches
              ? 'The two passwords are not the same.'
              : undefined
          }
          required
        >
          <Input
            id="confirmation"
            name="confirmation"
            type="password"
            autoComplete="new-password"
            required
            invalid={confirmation.length > 0 && !confirmationMatches}
            value={confirmation}
            onChange={(event) => setConfirmation(event.target.value)}
          />
        </FormField>

        {mutation.isError ? (
          <ErrorState
            title="Could not change password"
            message={readError(mutation.error)}
            correlationId={
              mutation.error instanceof ApiError ? mutation.error.correlationId : undefined
            }
          />
        ) : null}

        {mutation.isSuccess ? (
          <div
            role="status"
            className="rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-900"
          >
            The password was changed.
          </div>
        ) : null}

        <Button
          type="submit"
          isLoading={mutation.isPending}
          loadingText="Saving..."
          disabled={!confirmationMatches}
          className="w-full"
        >
          Change the password
        </Button>
      </form>
    </div>
  )
}

function readError(caught: unknown): string {
  if (caught instanceof ApiError) {
    if (caught.errorCode === 'INVALID_CREDENTIALS') {
      return 'The current password is not right.'
    }
    return caught.message
  }
  return 'Something went wrong. Try again.'
}
