import { useEffect, useId, useRef, type ReactNode } from 'react'

export interface DrawerProps {
  open: boolean
  onClose: () => void
  title: string
  description?: string
  children: ReactNode
  footer?: ReactNode
  widthClassName?: string
}

/**
 * Slide-over detail drawer anchored to the right edge of the viewport.
 */
export function Drawer({
  open,
  onClose,
  title,
  description,
  children,
  footer,
  widthClassName = 'max-w-xl',
}: DrawerProps) {
  const titleId = useId()
  const descId = useId()
  const panelRef = useRef<HTMLDivElement>(null)
  const previousFocusRef = useRef<HTMLElement | null>(null)

  useEffect(() => {
    if (!open) {
      return
    }
    previousFocusRef.current = document.activeElement as HTMLElement | null
    panelRef.current?.focus()

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        event.stopPropagation()
        onClose()
      }
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => {
      document.removeEventListener('keydown', handleKeyDown)
      previousFocusRef.current?.focus?.()
    }
  }, [open, onClose])

  if (!open) {
    return null
  }

  return (
    <div
      className="fixed inset-0 z-50 flex justify-end bg-slate-950/40 backdrop-blur-[1px]"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) {
          onClose()
        }
      }}
    >
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={description ? descId : undefined}
        tabIndex={-1}
        className={`flex h-full w-full flex-col border-l border-slate-200 bg-white shadow-lg outline-none ${widthClassName}`}
      >
        <div className="flex items-start justify-between gap-4 border-b border-slate-200 px-5 py-4">
          <div>
            <h2 id={titleId} className="text-base font-semibold text-slate-900">
              {title}
            </h2>
            {description ? (
              <p id={descId} className="mt-1 text-xs text-slate-500">
                {description}
              </p>
            ) : null}
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close drawer"
            className="rounded-md p-1 text-slate-400 transition-colors hover:bg-slate-100 hover:text-slate-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-merqo-500"
          >
            <span aria-hidden="true" className="block text-lg leading-none">
              &times;
            </span>
          </button>
        </div>

        <div className="flex-1 overflow-y-auto px-5 py-4">{children}</div>

        {footer ? (
          <div className="flex flex-wrap items-center justify-end gap-2 border-t border-slate-200 bg-slate-50 px-5 py-3">
            {footer}
          </div>
        ) : null}
      </div>
    </div>
  )
}
