import { Link } from 'react-router-dom'

/**
 * The page shown for a URL that does not exist.
 *
 * @returns the not found page element
 */
export default function NotFoundPage() {
  return (
    <div className="mx-auto max-w-md py-16 text-center">
      <h1 className="text-2xl font-semibold text-slate-900">Page not found</h1>
      <p className="mt-2 text-sm text-slate-600">
        That address does not exist in Merqo. It may belong to a screen that has not been built yet.
      </p>
      <Link
        to="/"
        className="mt-6 inline-block rounded-md bg-merqo-600 px-4 py-2 text-sm font-medium text-white hover:bg-merqo-700"
      >
        Back to the overview
      </Link>
    </div>
  )
}
