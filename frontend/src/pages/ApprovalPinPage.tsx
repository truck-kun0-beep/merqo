import { useState, type FormEvent } from 'react'
import { useMutation } from '@tanstack/react-query'
import { setApprovalPin } from '../api/pos'
import { useSession } from '../session/sessionContext'
import { Button, ErrorState, FormField, Input, PageHeader, useToast } from '../components/ui'

/**
 * Approval PIN management page under the user menu.
 * Accessible only to account owners and staff holding `POS_DISCOUNT_APPROVE`.
 */
export default function ApprovalPinPage() {
  const { user, can } = useSession()
  const { toast } = useToast()

  const allowed = user?.accountType === 'OWNER' || can('POS_DISCOUNT_APPROVE')

  const [currentPassword, setCurrentPassword] = useState('')
  const [pin, setPin] = useState('')
  const [confirmPin, setConfirmPin] = useState('')
  const [validationError, setValidationError] = useState<string | null>(null)
  const [savedBanner, setSavedBanner] = useState(false)

  const mutation = useMutation({
    mutationFn: () => setApprovalPin({ currentPassword, pin: pin.trim() }),
    onSuccess: () => {
      setCurrentPassword('')
      setPin('')
      setConfirmPin('')
      setValidationError(null)
      setSavedBanner(true)
      toast({ title: 'Approval PIN saved', tone: 'success' })
    },
  })

  if (!allowed) {
    return (
      <div
        role="alert"
        className="rounded-xl border border-amber-200 bg-amber-50 px-6 py-6 text-sm text-amber-900"
      >
        <p className="font-semibold">This screen is not open to your account.</p>
        <p className="mt-1">
          Setting a discount approval PIN requires{' '}
          <code className="font-mono">POS_DISCOUNT_APPROVE</code> or owner access.
        </p>
      </div>
    )
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setSavedBanner(false)

    if (!currentPassword) {
      setValidationError('Enter your current account password.')
      return
    }
    if (!pin.trim()) {
      setValidationError('Enter a new approval PIN.')
      return
    }
    if (pin.trim() !== confirmPin.trim()) {
      setValidationError('New approval PIN and confirmation do not match.')
      return
    }

    setValidationError(null)
    mutation.mutate()
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="Discount approval PIN"
        description="Set or rotate the personal PIN you enter when approving cashier discounts at POS."
      />

      <div className="max-w-lg rounded-xl border border-slate-200 bg-white p-6 shadow-xs">
        <form onSubmit={handleSubmit} className="space-y-4">
          <FormField label="Current password" required>
            <Input
              type="password"
              autoComplete="current-password"
              value={currentPassword}
              onChange={(e) => setCurrentPassword(e.target.value)}
            />
          </FormField>

          <FormField
            label="New approval PIN"
            hint="Use 4 to 8 digits. Avoid repeated digits or simple sequences."
            required
          >
            <Input
              type="password"
              inputMode="numeric"
              autoComplete="new-password"
              maxLength={8}
              value={pin}
              onChange={(e) => setPin(e.target.value)}
            />
          </FormField>

          <FormField label="Confirm approval PIN" required>
            <Input
              type="password"
              inputMode="numeric"
              autoComplete="new-password"
              maxLength={8}
              value={confirmPin}
              onChange={(e) => setConfirmPin(e.target.value)}
            />
          </FormField>

          {validationError ? (
            <p role="alert" className="text-sm font-medium text-rose-700">
              {validationError}
            </p>
          ) : null}

          {mutation.isError ? (
            <ErrorState title="Could not save approval PIN" error={mutation.error} />
          ) : null}

          {savedBanner ? (
            <div
              role="status"
              className="rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm font-medium text-emerald-900"
            >
              Your approval PIN has been updated.
            </div>
          ) : null}

          <div className="flex justify-end pt-2">
            <Button type="submit" isLoading={mutation.isPending} loadingText="Saving PIN...">
              Save approval PIN
            </Button>
          </div>
        </form>
      </div>
    </div>
  )
}
