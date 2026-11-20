import { NavLink, Outlet } from 'react-router-dom'
import { useBackendHealth } from '../hooks/useBackendHealth'

/**
 * The frame around every page: the header, the navigation and the demo banner.
 *
 * The navigation only shows items the current user may use. The backend is
 * still the real gate; the frontend only hides what would fail anyway.
 *
 * @returns the layout element
 */
export default function AppShell() {
  const { isOffline } = useBackendHealth()

  return (
    <div className="flex min-h-full flex-col bg-slate-50">
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-4 py-3">
          <div className="flex items-baseline gap-3">
            <span className="text-lg font-semibold tracking-tight text-merqo-700">Merqo</span>
            <span className="hidden text-xs text-slate-500 sm:inline">
              Omnichannel retail operations and profit intelligence
            </span>
          </div>
          <nav aria-label="Main" className="flex items-center gap-1 text-sm">
            <NavLink
              to="/"
              end
              className={({ isActive }) =>
                [
                  'rounded-md px-3 py-1.5 font-medium transition-colors',
                  isActive
                    ? 'bg-merqo-50 text-merqo-800'
                    : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900',
                ].join(' ')
              }
            >
              Overview
            </NavLink>
          </nav>
        </div>
      </header>

      {isOffline ? (
        <div
          role="status"
          className="bg-amber-100 px-4 py-2 text-center text-sm font-medium text-amber-900"
        >
          Offline: the connection to the server is lost. Trying again automatically.
        </div>
      ) : null}

      <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-6">
        <Outlet />
      </main>

      <footer className="border-t border-slate-200 bg-white px-4 py-3 text-center text-xs text-slate-500">
        Merqo - academic project. All amounts in BDT (৳).
      </footer>
    </div>
  )
}
