import { useState, useEffect } from 'react';
import { Users, Package, AlertTriangle, DollarSign } from 'lucide-react';
import { getAdminDashboard } from '../../api/admin';

// Backend response: { metrics: DashboardMetrics, activities: DashboardActivityItem[], previewUsers, previewListings }
// DashboardMetrics: totalUsers, activeUsers, activeListings, totalListings, flaggedListings, revenueDollars, revenueGrowthLabel
// DashboardActivityItem: severity ("success"|"warning"|"danger"), message, timeLabel

const SEVERITY_COLOR = {
  success: 'bg-green-400',
  warning: 'bg-yellow-400',
  danger: 'bg-red-400',
};

export default function AdminDashboard() {
  const [data, setData] = useState(null);

  useEffect(() => {
    getAdminDashboard().then(r => setData(r.data)).catch(() => {});
  }, []);

  const m = data?.metrics ?? {};
  const activities = data?.activities ?? [];

  const cards = [
    { label: 'Total Users', value: m.totalUsers ?? '—', sub: `${m.activeUsers ?? 0} active`, icon: Users, color: 'text-blue-500', bg: 'bg-blue-50' },
    { label: 'Active Listings', value: m.activeListings ?? '—', sub: `${m.totalListings ?? 0} total`, icon: Package, color: 'text-green-500', bg: 'bg-green-50' },
    { label: 'Flagged Items', value: m.flaggedListings ?? '—', sub: 'Needs review', icon: AlertTriangle, color: 'text-yellow-500', bg: 'bg-yellow-50' },
    { label: 'Revenue', value: m.revenueDollars != null ? `$${Number(m.revenueDollars).toLocaleString()}` : '—', sub: m.revenueGrowthLabel ?? '', icon: DollarSign, color: 'text-purple-500', bg: 'bg-purple-50' },
  ];

  return (
    <div className="p-8">
      <h1 className="text-2xl font-bold text-gray-900 mb-1">Dashboard Overview</h1>
      <p className="text-gray-400 text-sm mb-6">Monitor platform activity and key metrics</p>

      <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-8">
        {cards.map(({ label, value, sub, icon: Icon, color, bg }) => (
          <div key={label} className="card p-5">
            <div className="flex items-center justify-between mb-2">
              <div>
                <p className="text-gray-400 text-xs font-medium">{label}</p>
                <p className="text-2xl font-bold text-gray-900 mt-0.5">{value}</p>
              </div>
              <div className={`w-10 h-10 ${bg} rounded-full flex items-center justify-center`}>
                <Icon size={20} className={color} />
              </div>
            </div>
            <p className={`text-xs ${color}`}>{sub}</p>
          </div>
        ))}
      </div>

      <div className="card p-5">
        <h2 className="font-bold text-gray-900 mb-4">Recent Activity</h2>
        {activities.length === 0 ? (
          <p className="text-sm text-gray-400">No recent activity.</p>
        ) : (
          <div className="space-y-3">
            {activities.map((item, i) => (
              <div key={i} className="flex items-center justify-between py-2 border-b border-gray-50 last:border-0">
                <div className="flex items-center gap-3">
                  <div className={`w-2.5 h-2.5 rounded-full ${SEVERITY_COLOR[item.severity] ?? 'bg-gray-400'}`} />
                  <span className="text-sm text-gray-700">{item.message}</span>
                </div>
                <span className="text-xs text-gray-400">{item.timeLabel}</span>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
