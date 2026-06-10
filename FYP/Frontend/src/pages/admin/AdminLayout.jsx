import { NavLink, Outlet } from 'react-router-dom';
import { LayoutDashboard, Users, List, BarChart2, Tag, AlertCircle } from 'lucide-react';

const NAV = [
  { to: '/admin', icon: LayoutDashboard, label: 'Overview', end: true },
  { to: '/admin/users', icon: Users, label: 'User Moderation' },
  { to: '/admin/listings', icon: List, label: 'Listing Moderation' },
  { to: '/admin/analytics', icon: BarChart2, label: 'Analytics' },
  { to: '/admin/categories', icon: Tag, label: 'Categories' },
  { to: '/admin/reports', icon: AlertCircle, label: 'User Reports' },
];

export default function AdminLayout() {
  return (
    <div className="flex min-h-screen">
      <aside className="w-56 bg-gray-900 text-white shrink-0 flex flex-col">
        <div className="p-5 border-b border-gray-700">
          <div className="font-bold text-lg">Admin Panel</div>
          <div className="text-gray-400 text-xs">AuctionHub</div>
        </div>
        <nav className="flex-1 p-3">
          {NAV.map(({ to, icon: Icon, label, end }) => (
            <NavLink
              key={to}
              to={to}
              end={end}
              className={({ isActive }) =>
                `flex items-center gap-3 px-3 py-2.5 rounded-lg mb-1 text-sm transition-colors ${isActive ? 'bg-blue-600 text-white' : 'text-gray-300 hover:bg-gray-800'}`
              }
            >
              <Icon size={16} />
              {label}
            </NavLink>
          ))}
        </nav>
      </aside>
      <main className="flex-1 bg-gray-50 overflow-auto">
        <Outlet />
      </main>
    </div>
  );
}
