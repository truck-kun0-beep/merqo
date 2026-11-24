export interface SkeletonProps {
  className?: string
}

/**
 * Simple rectangular loading shimmer placeholder.
 */
export function Skeleton({ className = 'h-4 w-full' }: SkeletonProps) {
  return (
    <div
      aria-hidden="true"
      className={`animate-pulse rounded-md bg-slate-200/80 ${className}`}
    />
  )
}

/**
 * Stacked skeleton lines for card or paragraph loading states.
 */
export function SkeletonText({ lines = 3 }: { lines?: number }) {
  return (
    <div className="space-y-2.5" aria-hidden="true">
      {Array.from({ length: lines }).map((_, index) => (
        <Skeleton
          key={index}
          className={`h-3.5 ${index === lines - 1 ? 'w-2/3' : 'w-full'}`}
        />
      ))}
    </div>
  )
}
