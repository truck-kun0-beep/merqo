import { useEffect, useRef, useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { fetchAuthStatus } from '../api/auth'
import { useBackendHealth } from '../hooks/useBackendHealth'
import { useSession } from '../session/sessionContext'
import { Badge } from './ui/Badge'

interface NavItem {
  to: string
  label: string
  end?: boolean
  visible: boolean
}

interface NavGroup {
  id: string
  label: string
  items: NavItem[]
}

/**
 * Application shell with module-grouped sidebar, top bar with business name
 * from GET /auth/status, demo banner, offline banner, and user menu.
 */
export default function AppShell() {
  const { isOffline: isBackendOffline } = useBackendHealth()
  const { user, can, signOut } = useSession()
  const navigate = useNavigate()
  const location = useLocation()

  const [sidebarOpenPath, setSidebarOpenPath] = useState<string | null>(null)
  const [userMenuOpenPath, setUserMenuOpenPath] = useState<string | null>(null)
  const sidebarOpen = sidebarOpenPath === location.pathname
  const userMenuOpen = userMenuOpenPath === location.pathname
  const setSidebarOpen = (open: boolean | ((prev: boolean) => boolean)) => {
    const next = typeof open === 'function' ? open(sidebarOpen) : open
    setSidebarOpenPath(next ? location.pathname : null)
  }
  const setUserMenuOpen = (open: boolean | ((prev: boolean) => boolean)) => {
    const next = typeof open === 'function' ? open(userMenuOpen) : open
    setUserMenuOpenPath(next ? location.pathname : null)
  }
  const [browserOffline, setBrowserOffline] = useState(
    typeof navigator !== 'undefined' ? navigator.onLine === false : false,
  )
  const menuRef = useRef<HTMLDivElement>(null)

  const statusQuery = useQuery({
    queryKey: ['auth', 'status'],
    queryFn: fetchAuthStatus,
    staleTime: 60_000,
  })

  const businessName = statusQuery.data?.businessName || 'Merqo'
  const demoMode = statusQuery.data?.demoMode === true
  const isOffline = browserOffline || isBackendOffline

  useEffect(() => {
    function handleOnline() {
      setBrowserOffline(false)
    }
    function handleOffline() {
      setBrowserOffline(true)
    }
    window.addEventListener('online', handleOnline)
    window.addEventListener('offline', handleOffline)
    return () => {
      window.removeEventListener('online', handleOnline)
      window.removeEventListener('offline', handleOffline)
    }
  }, [])

  useEffect(() => {
    if (!userMenuOpen) {
      return
    }
    function handleOutside(event: MouseEvent) {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) {
        setUserMenuOpenPath(null)
      }
    }
    function handleEscape(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        setUserMenuOpenPath(null)
      }
    }
    document.addEventListener('mousedown', handleOutside)
    document.addEventListener('keydown', handleEscape)
    return () => {
      document.removeEventListener('mousedown', handleOutside)
      document.removeEventListener('keydown', handleEscape)
    }
  }, [userMenuOpen])

  async function handleSignOut() {
    setUserMenuOpen(false)
    await signOut()
    navigate('/login', { replace: true })
  }

  const navGroups: NavGroup[] = [
    {
      id: 'dashboard',
      label: 'Dashboard',
      items: [{ to: '/', label: 'Overview', end: true, visible: true }],
    },
    {
      id: 'catalog',
      label: 'Catalog',
      items: [
        { to: '/catalog/products', label: 'Products', visible: false },
        { to: '/promotions', label: 'Promotions', visible: false },
        { to: '/catalog/import', label: 'CSV import', visible: false },
      ],
    },
    {
      id: 'inventory',
      label: 'Inventory',
      items: [
        { to: '/inventory', label: 'Stock', visible: false },
        { to: '/inventory/counts', label: 'Stock counts', visible: false },
      ],
    },
    {
      id: 'transfers',
      label: 'Transfers',
      items: [{ to: '/transfers', label: 'Stock transfers', visible: false }],
    },
    {
      id: 'operations',
      label: 'Organization',
      items: [
        { to: '/locations', label: 'Locations', visible: true },
        { to: '/users', label: 'People', visible: can('USER_VIEW') },
        { to: '/permission-templates', label: 'Templates', visible: can('TEMPLATE_MANAGE') },
        { to: '/settings', label: 'Settings', visible: can('SETTINGS_VIEW') },
        { to: '/audit', label: 'Audit', visible: can('AUDIT_VIEW') },
      ],
    },
  ]

  const visibleGroups = navGroups
    .map((group) => ({
      ...group,
      items: group.items.filter((item) => item.visible),
    }))
    .filter((group) => group.items.length > 0)

  const roleLabel =
    user?.accountType === 'OWNER'
      ? 'Owner'
      : user?.accountType === 'MANAGER'
        ? 'Manager'
        : 'Staff'

  const sidebarContent = (
    <nav aria-label="Main" className="flex flex-col gap-5 px-3 py-4">
      {visibleGroups.map((group) => (
        <div key={group.id}>
          <p className="px-3 text-[11px] font-semibold tracking-wider text-slate-400 uppercase">
            {group.label}
          </p>
          <ul className="mt-1.5 space-y-0.5">
            {group.items.map((item) => (
              <li key={item.to}>
                <NavLink
                  to={item.to}
                  end={item.end}
                  className={({ isActive }) =>
                    [
                      'flex items-center rounded-lg px-3 py-2 text-sm font-medium transition-colors',
                      'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-merqo-500',
                      isActive
                        ? 'bg-merqo-50 text-merqo-800'
                        : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900',
                    ].join(' ')
                  }
                >
                  {item.label}
                </NavLink>
              </li>
            ))}
          </ul>
        </div>
      ))}
    </nav>
  )

  return (
    <div className="flex min-h-full flex-col bg-slate-50">
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:fixed focus:top-2 focus:left-2 focus:z-50 focus:rounded-md focus:bg-merqo-700 focus:px-4 focus:py-2 focus:text-sm focus:font-medium focus:text-white focus:shadow-md"
      >
        Skip to content
      </a>

      {demoMode ? (
        <div
          role="region"
          aria-label="Demo environment notice"
          className="border-b border-amber-200 bg-amber-50 px-4 py-1.5 text-center text-xs font-semibold tracking-wide text-amber-900"
        >
          DEMO MODE — Sample retail data is active. Changes are reset periodically.
        </div>
      ) : null}

      {isOffline ? (
        <div
          role="status"
          className="border-b border-rose-200 bg-rose-50 px-4 py-2 text-center text-sm font-medium text-rose-900"
        >
          Offline: connection lost. Reconnecting automatically when online.
        </div>
      ) : null}

      <header className="sticky top-0 z-30 border-b border-slate-200 bg-white">
        <div className="mx-auto flex h-14 max-w-7xl items-center justify-between gap-4 px-4 sm:px-6">
          <div className="flex items-center gap-3">
            <button
              type="button"
              onClick={() => setSidebarOpen((open) => !open)}
              aria-expanded={sidebarOpen}
              aria-controls="mobile-sidebar"
              aria-label="Toggle navigation menu"
              className="inline-flex h-9 w-9 items-center justify-center rounded-md border border-slate-200 text-slate-600 hover:bg-slate-100 hover:text-slate-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-merqo-500 lg:hidden"
            >
              <span aria-hidden="true" className="text-base leading-none">
                {sidebarOpen ? '✕' : '☰'}
              </span>
            </button>
            <NavLink
              to="/"
              className="flex items-center gap-2 text-base font-semibold tracking-tight text-slate-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-merqo-500 rounded"
            >
              <span className="inline-flex h-7 w-7 items-center justify-center rounded-md bg-merqo-700 text-xs font-bold text-white">
                {businessName.slice(0, 1).toUpperCase()}
              </span>
              <span>{businessName}</span>
            </NavLink>
            {demoMode ? <Badge tone="warning">DEMO</Badge> : null}
          </div>

          {user ? (
            <div ref={menuRef} className="relative">
              <button
                type="button"
                onClick={() => setUserMenuOpen((open) => !open)}
                aria-expanded={userMenuOpen}
                aria-haspopup="menu"
                className="flex items-center gap-2.5 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-left transition-colors hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-merqo-500"
              >
                <div className="leading-tight">
                  <span className="block text-xs font-semibold text-slate-900">
                    {user.fullName}
                  </span>
                  <span className="block text-[11px] text-slate-500">{roleLabel}</span>
                </div>
                <span aria-hidden="true" className="text-xs text-slate-400">
                  ▾
                </span>
              </button>

              {userMenuOpen ? (
                <div
                  role="menu"
                  className="absolute right-0 mt-1.5 w-52 rounded-xl border border-slate-200 bg-white py-1.5 shadow-lg"
                >
                  <div className="border-b border-slate-100 px-3.5 py-2">
                    <p className="text-xs font-semibold text-slate-900">{user.fullName}</p>
                    <p className="text-xs text-slate-500">
                      {user.username} · {roleLabel}
                    </p>
                  </div>
                  <NavLink
                    to="/change-password"
                    role="menuitem"
                    onClick={() => setUserMenuOpen(false)}
                    className="block px-3.5 py-2 text-sm text-slate-700 hover:bg-slate-50"
                  >
                    Change password
                  </NavLink>
                  <button
                    type="button"
                    role="menuitem"
                    onClick={handleSignOut}
                    className="block w-full px-3.5 py-2 text-left text-sm font-medium text-rose-700 hover:bg-rose-50"
                  >
                    Sign out
                  </button>
                </div>
              ) : null}
            </div>
          ) : null}
        </div>
      </header>

      <div className="mx-auto flex w-full max-w-7xl flex-1">
        <aside className="hidden w-60 shrink-0 border-r border-slate-200 bg-white lg:block">
          {sidebarContent}
        </aside>

        {sidebarOpen ? (
          <div
            className="fixed inset-0 z-40 flex bg-slate-950/35 lg:hidden"
            onMouseDown={(event) => {
              if (event.target === event.currentTarget) {
                setSidebarOpen(false)
              }
            }}
          >
            <aside
              id="mobile-sidebar"
              aria-label="Mobile navigation"
              className="h-full w-64 border-r border-slate-200 bg-white shadow-xl"
            >
              <div className="flex h-14 items-center justify-between border-b border-slate-200 px-4">
                <span className="text-sm font-semibold text-slate-900">{businessName}</span>
                <button
                  type="button"
                  onClick={() => setSidebarOpen(false)}
                  aria-label="Close navigation menu"
                  className="rounded-md p-1 text-slate-500 hover:bg-slate-100"
                >
                  ✕
                </button>
              </div>
              {sidebarContent}
            </aside>
          </div>
        ) : null}

        <main
          id="main-content"
          tabIndex={-1}
          className="min-w-0 flex-1 px-4 py-6 outline-none sm:px-6 lg:px-8"
        >
          <Outlet />
        </main>
      </div>
    </div>
  )
}
