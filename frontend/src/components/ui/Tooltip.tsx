import { useId, useState, type ReactNode } from 'react'

export interface TooltipProps {
  content: ReactNode
  children: ReactNode
  className?: string
}

/**
 * Accessible hover/focus tooltip.
 */
export function Tooltip({ content, children, className = '' }: TooltipProps) {
  const id = useId()
  const [visible, setVisible] = useState(false)

  return (
    <span
      className={`relative inline-flex ${className}`}
      onMouseEnter={() => setVisible(true)}
      onMouseLeave={() => setVisible(false)}
      onFocus={() => setVisible(true)}
      onBlur={() => setVisible(false)}
    >
      <span aria-describedby={visible ? id : undefined} className="inline-flex">
        {children}
      </span>
      {visible ? (
        <span
          id={id}
          role="tooltip"
          className="pointer-events-none absolute bottom-full left-1/2 z-40 mb-1.5 -translate-x-1/2 rounded-md bg-slate-900 px-2.5 py-1 text-xs font-medium whitespace-nowrap text-white shadow-sm"
        >
          {content}
        </span>
      ) : null}
    </span>
  )
}
