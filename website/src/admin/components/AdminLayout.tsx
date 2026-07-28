import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom'
import { getSupabase } from '../../lib/supabase'

const navItems = [
  { path: '/admin/dashboard', label: 'Dashboard', icon: '📊' },
  { path: '/admin/devices', label: 'Devices', icon: '📱' },
  { path: '/admin/orders', label: 'Orders', icon: '🧾' },
  { path: '/admin/qr-sheets', label: 'QR Sheets', icon: '📄' },
  { path: '/admin/settings', label: 'Settings', icon: '⚙️' },
]

export default function AdminLayout() {
  const navigate = useNavigate()

  const handleLogout = async () => {
    const supabase = getSupabase()
    await supabase.auth.signOut()
    navigate('/admin/login')
  }

  return (
    <div className="min-h-screen bg-gray-50">
      {/* Top navigation bar */}
      <header className="border-b border-gray-200 bg-white shadow-sm">
        <div className="mx-auto flex max-w-7xl items-center justify-between px-4 py-3 sm:px-6">
          <Link to="/admin/dashboard" className="flex items-center gap-2">
            <span className="text-xl font-bold text-emerald-700">Warung Admin</span>
          </Link>

          <nav className="hidden items-center gap-1 md:flex" aria-label="Main navigation">
            {navItems.map((item) => (
              <NavLink
                key={item.path}
                to={item.path}
                className={({ isActive }) =>
                  `rounded-md px-3 py-2 text-sm font-medium transition-colors ${
                    isActive
                      ? 'bg-emerald-50 text-emerald-700'
                      : 'text-gray-600 hover:bg-gray-100 hover:text-gray-900'
                  }`
                }
              >
                <span aria-hidden="true" className="mr-1">{item.icon}</span>
                {item.label}
              </NavLink>
            ))}
          </nav>

          <button
            onClick={handleLogout}
            className="rounded-md px-3 py-2 text-sm font-medium text-gray-600 transition-colors hover:bg-gray-100 hover:text-gray-900"
            aria-label="Sign out"
          >
            Sign Out
          </button>
        </div>

        {/* Mobile navigation */}
        <nav className="border-t border-gray-100 md:hidden" aria-label="Mobile navigation">
          <div className="flex overflow-x-auto px-2 py-1">
            {navItems.map((item) => (
              <NavLink
                key={item.path}
                to={item.path}
                className={({ isActive }) =>
                  `flex-shrink-0 rounded-md px-3 py-2 text-xs font-medium transition-colors ${
                    isActive
                      ? 'bg-emerald-50 text-emerald-700'
                      : 'text-gray-600 hover:bg-gray-100'
                  }`
                }
              >
                <span aria-hidden="true" className="mr-1">{item.icon}</span>
                {item.label}
              </NavLink>
            ))}
          </div>
        </nav>
      </header>

      {/* Main content */}
      <main className="mx-auto max-w-7xl px-4 py-6 sm:px-6">
        <Outlet />
      </main>
    </div>
  )
}
