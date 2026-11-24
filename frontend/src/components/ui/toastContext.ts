import { createContext, useContext } from 'react'

export type ToastTone = 'info' | 'success' | 'warning' | 'danger'

export interface ToastInput {
  title: string
  description?: string
  tone?: ToastTone
  durationMs?: number
}

export interface ToastContextValue {
  toast: (input: ToastInput) => void
  dismiss: (id: string) => void
}

export const ToastContext = createContext<ToastContextValue | undefined>(undefined)

/**
 * Returns toast helpers. Safe to call even outside a ToastProvider (no-ops cleanly in isolated tests).
 */
export function useToast(): ToastContextValue {
  const context = useContext(ToastContext)
  if (!context) {
    return {
      toast: () => {},
      dismiss: () => {},
    }
  }
  return context
}
