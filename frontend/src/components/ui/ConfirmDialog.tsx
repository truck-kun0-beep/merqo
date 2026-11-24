import type { ReactNode } from 'react'
import { Button, type ButtonVariant } from './Button'
import { Modal } from './Modal'

export interface ConfirmDialogProps {
  open: boolean
  onClose: () => void
  onConfirm: () => void
  title: string
  description?: string
  children?: ReactNode
  confirmLabel?: string
  cancelLabel?: string
  confirmVariant?: ButtonVariant
  isConfirming?: boolean
}

/**
 * Confirmation modal for destructive or high-impact actions.
 */
export function ConfirmDialog({
  open,
  onClose,
  onConfirm,
  title,
  description,
  children,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  confirmVariant = 'primary',
  isConfirming = false,
}: ConfirmDialogProps) {
  return (
    <Modal
      open={open}
      onClose={onClose}
      title={title}
      description={description}
      size="sm"
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={isConfirming}>
            {cancelLabel}
          </Button>
          <Button variant={confirmVariant} onClick={onConfirm} isLoading={isConfirming}>
            {confirmLabel}
          </Button>
        </>
      }
    >
      {children ? <div className="text-sm text-slate-700">{children}</div> : null}
    </Modal>
  )
}
