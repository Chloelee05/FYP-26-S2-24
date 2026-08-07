/*
 * Shell for every page under "/admin". ADMIN role only: App.jsx wraps this whole route in
 * <ProtectedRoute roles={['ADMIN']}>, so the guard is applied once here and every nested
 * admin page inherits it. A member who types an admin URL is redirected to the landing page
 * before any admin component mounts.
 * Deliberately outside MainLayout, so the admin console has its own sidebar rather than the
 * shopper navbar and footer. The nested page renders through <Outlet/>.
 * No API calls of its own beyond the notification bell; the session comes from useAuth().
 */
import { NavLink, Outlet, Link, useNavigate } from 'react-router-dom';
import { LayoutDashboard, Users, List, BarChart2, Tag, AlertCircle, ShoppingBag, MessageCircle, Database, LogOut, Globe, Star, Gavel, Type } from 'lucide-react';
import NotificationBell from '../../components/NotificationBell';
import { useAuth } from '../../context/AuthContext';

// Sidebar links, in the order the admin work tends to happen: overview, then moderation,
// then the configuration pages. Overview needs end:true or NavLink would mark it active on
// every /admin/* path, since they all begin with "/admin".
const NAV = [
  { to: '/admin', icon: LayoutDashboard, label: 'Overview', end: true },
  { to: '/admin/users', icon: Users, label: 'User Moderation' },
  { to: '/admin/listings', icon: List, label: 'Listing Moderation' },
  { to: '/admin/orders', icon: ShoppingBag, label: 'Orders' },
  { to: '/admin/analytics', icon: BarChart2, label: 'Analytics' },
  { to: '/admin/database', icon: Database, label: 'Database' },
  { to: '/admin/categories', icon: Tag, label: 'Categories' },
  { to: '/admin/landing-content', icon: Type, label: 'Landing Page' },
  { to: '/admin/reports', icon: AlertCircle, label: 'User Reports' },
  { to: '/admin/reviews', icon: Star, label: 'Reviews' },
  { to: '/admin/chat', icon: MessageCircle, label: 'Support Chat' },
];

export default function AdminLayout() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const handleLogout = async () => {
    await logout();
    navigate('/login');
  };

  return (
    <div className="flex h-screen bg-ink-50">
      <aside className="w-60 shrink-0 flex flex-col bg-ink-900 text-white">
        <div className="px-5 py-5 border-b border-white/10">
          <div className="flex items-center gap-2.5">
            <span className="grid place-items-center w-9 h-9 rounded-xl bg-gradient-to-br from-primary-500 to-primary-700 shadow-sm">
              <Gavel size={17} />
            </span>
            <div className="leading-tight">
              <div className="font-display font-bold text-sm">Admin Panel</div>
              <div className="text-ink-400 text-xs">AuctionHub</div>
            </div>
          </div>
        </div>

        <nav className="flex-1 p-3 overflow-y-auto">
          {NAV.map(({ to, icon: Icon, label, end }) => (
            <NavLink
              key={to}
              to={to}
              end={end}
              className={({ isActive }) =>
                `flex items-center gap-3 px-3 py-2.5 rounded-xl mb-1 text-sm font-medium transition-all ${
                  isActive
                    ? 'bg-primary-600 text-white shadow-sm'
                    : 'text-ink-300 hover:bg-white/5 hover:text-white'
                }`
              }
            >
              <Icon size={16} />
              {label}
            </NavLink>
          ))}
        </nav>

        {/* Bottom actions: Browse Site + Logout.
            "Browse Site" leaves the console for the public shop without signing out, which
            is how an admin checks that a moderation change looks right to a shopper. */}
        <div className="p-3 border-t border-white/10 space-y-1">
          <Link
            to="/"
            className="flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium text-ink-300 hover:bg-white/5 hover:text-white transition-colors"
          >
            <Globe size={16} /> Browse Site
          </Link>
          <button
            onClick={handleLogout}
            className="flex items-center gap-3 w-full px-3 py-2.5 rounded-xl text-sm font-medium text-red-400 hover:bg-red-500/10 transition-colors"
          >
            <LogOut size={16} /> Logout
          </button>
        </div>
      </aside>

      <main className="flex-1 overflow-auto flex flex-col min-h-0">
        <div className="flex items-center justify-end gap-3 px-6 py-3 border-b border-ink-200 bg-white/80 backdrop-blur-md shrink-0 sticky top-0 z-30">
          <NotificationBell />
          {user && (
            <div className="flex items-center gap-2 pl-3 border-l border-ink-200">
              <div className="w-8 h-8 rounded-full bg-gradient-to-br from-ink-700 to-ink-900 text-white grid place-items-center text-sm font-bold">
                {user.username?.[0]?.toUpperCase() ?? 'A'}
              </div>
              <div className="leading-tight hidden sm:block">
                <p className="text-sm font-semibold text-ink-900">{user.username}</p>
                <p className="text-[11px] text-ink-400">Administrator</p>
              </div>
            </div>
          )}
        </div>
        <Outlet />
      </main>
    </div>
  );
}
