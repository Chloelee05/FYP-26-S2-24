import { useState, useEffect } from 'react';
import { Users, Package, AlertTriangle, DollarSign } from 'lucide-react';
import { getAdminDashboard } from '../../api/admin';

// Backend response: { metrics: DashboardMetrics, activities: DashboardActivityItem[], previewUsers, previewListings }
// DashboardMetrics: totalUsers, activeUsers, activeListings, totalListings, flaggedListings, revenueDollars, revenueGrowthLabel
// DashboardActivityItem: severity ("success"|"warning"|"danger"), message, timeLabel

const SEVERITY_COLOR = {
  success: 'bg-emerald-500',
  warning: 'bg-amber-400',
  danger: 'bg-red-500',
};

export default function AdminDashboard() {
  const [data, setData] = useState(null);

  useEffect(() => {
    getAdminDashboard().then(r => setData(r.data)).catch(() => {});
  }, []);

  const m = data?.metrics ?? {};
  const activities = data?.activities ?? [];

  const cards = [
    { label: 'Total Users', value: m.totalUsers ?? '—', sub: `${m.activeUsers ?? 0} active`, icon: Users, color: 'text-primary-600', bg: 'bg-primary-50' },
    { label: 'Active Listings', value: m.activeListings ?? '—', sub: `${m.totalListings ?? 0} total`, icon: Package, color: 'text-emerald-600', bg: 'bg-emerald-50' },
    { label: 'Flagged Items', value: m.flaggedListings ?? '—', sub: 'Needs review', icon: AlertTriangle, color: 'text-amber-600', bg: 'bg-amber-50' },
    { label: 'Revenue', value: m.revenueDollars != null ? `$${Number(m.revenueDollars).toLocaleString()}` : '—', sub: m.revenueGrowthLabel ?? '', icon: DollarSign, color: 'text-purple-600', bg: 'bg-purple-50' },
  ];

  return (
    <div className="p-6 sm:p-8">
      <h1 className="page-title">Dashboard Overview</h1>
      <p className="page-subtitle mb-6">Monitor platform activity and key metrics</p>

      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-8">
        {cards.map(({ label, value, sub, icon: Icon, color, bg }) => (
          <div key={label} className="card card-hover p-5">
            <div className="flex items-start justify-between gap-3 mb-3">
              <div className="min-w-0">
                <p className="eyebrow">{label}</p>
                <p className="text-2xl sm:text-3xl font-bold text-ink-900 mt-1.5 tabular-nums">{value}</p>
              </div>
              <div className={`grid place-items-center w-11 h-11 ${bg} rounded-xl shrink-0`}>
                <Icon size={20} className={color} />
              </div>
            </div>
            <p className={`text-xs font-semibold ${color}`}>{sub}</p>
          </div>
        ))}
      </div>

      <div className="card p-6">
        <h2 className="section-title text-base mb-4">Recent Activity</h2>
        {activities.length === 0 ? (
          <p className="text-sm text-ink-400">No recent activity.</p>
        ) : (
          <div className="divide-y divide-ink-100">
            {activities.map((item, i) => (
              <div key={i} className="flex items-center justify-between gap-4 py-3 first:pt-0 last:pb-0">
                <div className="flex items-center gap-3 min-w-0">
                  <span className={`w-2 h-2 rounded-full shrink-0 ${SEVERITY_COLOR[item.severity] ?? 'bg-ink-400'}`} />
                  <span className="text-sm text-ink-700 truncate">{item.message}</span>
                </div>
                <span className="text-xs text-ink-400 shrink-0">{item.timeLabel}</span>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
