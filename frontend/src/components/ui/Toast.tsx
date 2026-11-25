import { useCallback, useMemo, useState, type ReactNode } from 'react'
import { ToastContext, type ToastInput, type ToastTone } from './toastContext'

interface ToastItem extends ToastInput {
  id: string
  tone: ToastTone
}

const TONE_CLASSES: Record<ToastTone, string> = {
  info: 'border-blue-200 bg-white text-slate-900',
  success: 'border-emerald-200 bg-emerald-50 text-emerald-950',
  warning: 'border-amber-200 bg-amber-50 text-amber-950',
  danger: 'border-rose-200 bg-rose-50 text-rose-950',
}

/**
 * Provides transient toast notifications across the application.
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([])

  const dismiss = useCallback((id: string) => {
    setItems((prev) => prev.filter((item) => item.id !== id))
  }, [])

  const toast = useCallback(
    ({ durationMs = 4000, tone = 'info', ...rest }: ToastInput) => {
      const id = `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`
      setItems((prev) => [...prev, { id, tone, ...rest }])
      if (durationMs > 0) {
        setTimeout(() => dismiss(id), durationMs)
      }
    },
    [dismiss],
  )

  const value = useMemo(() => ({ toast, dismiss }), [toast, dismiss])

  return (
    <ToastContext.Provider value={value}>
      {children}
      <div
        aria-live="polite"
        aria-atomic="false"
        className="pointer-events-none fixed right-4 bottom-4 z-50 flex w-full max-w-sm flex-col gap-2"
      >
        {items.map((item) => (
          <div
            key={item.id}
            role="status"
            className={[
              'pointer-events-auto flex items-start justify-between gap-3 rounded-lg border p-3.5 shadow-md',
              TONE_CLASSES[item.tone],
            ].join(' ')}
          >
            <div className="text-sm">
              <p className="font-medium">{item.title}</p>
              {item.description ? (
                <p className="mt-0.5 text-xs opacity-85">{item.description}</p>
              ) : null}
            </div>
            <button
              type="button"
              onClick={() => dismiss(item.id)}
              aria-label="Dismiss notification"
              className="rounded p-0.5 text-sm opacity-60 hover:opacity-100"
            >
              &times;
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}
