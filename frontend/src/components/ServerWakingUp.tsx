/**
 * The screen shown while the free backend instance wakes up.
 *
 * A free Render web service sleeps after fifteen idle minutes and needs about
 * a minute to come back. This screen explains what is happening and the
 * request retries on its own, so the user never has to press a button.
 *
 * @returns the waking screen element
 */
export default function ServerWakingUp() {
  return (
    <section
      aria-live="polite"
      className="mx-auto flex max-w-md flex-col items-center gap-4 rounded-xl border border-slate-200 bg-white px-6 py-10 text-center shadow-sm"
    >
      <div
        aria-hidden="true"
        className="h-10 w-10 animate-spin rounded-full border-4 border-merqo-100 border-t-merqo-600"
      />
      <div>
        <h2 className="text-lg font-semibold text-slate-900">Server is waking up</h2>
        <p className="mt-2 text-sm text-slate-600">
          The free server sleeps when it is idle and needs a moment to start again. This page retries
          automatically, so there is nothing to do.
        </p>
      </div>
    </section>
  )
}
