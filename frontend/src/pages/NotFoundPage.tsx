import { Link } from 'react-router-dom'
import { EmptyState } from '../components/ui'

/**
 * The page shown for a URL that does not exist.
 */
export default function NotFoundPage() {
  return (
    <div className="mx-auto max-w-lg py-12">
      <EmptyState
        title="Page not found"
        description="That address does not exist or has not been enabled for your workspace."
        action={
          <Link
            to="/"
            className="inline-flex h-9 items-center justify-center rounded-md bg-merqo-700 px-4 text-sm font-medium text-white transition-colors hover:bg-merqo-800"
          >
            Back to overview
          </Link>
        }
      />
    </div>
  )
}
