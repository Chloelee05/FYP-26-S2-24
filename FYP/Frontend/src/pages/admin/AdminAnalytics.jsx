import { useState, useEffect } from 'react';
import { getAdminAnalytics } from '../../api/admin';

// Backend response: { totalUsers, activeUsers, totalListings, activeListings,
//   flagged, revenue, topCreators, topRevenue }

const REPORTS = [
  { icon: '📄', label: 'User Activity Report', sub: 'Export user statistics', color: 'text-blue-500' },
  { icon: '📗', label: 'Revenue Report', sub: 'Financial analytics', color: 'text-green-500' },
  { icon: '📕', label: 'Moderation Report', sub: 'Flags and bans summary', color: 'text-purple-500' },
];

export default function AdminAnalytics() {
  const [data, setData] = useState(null);

  useEffect(() => {
    getAdminAnalytics().then(r => setData(r.data)).catch(() => {});
  }, []);

  const stats = data ? [
    { label: 'Total Users', value: data.totalUsers ?? '—', color: 'text-blue-600', bg: 'bg-blue-50' },
    { label: 'Active Users', value: data.activeUsers ?? '—', color: 'text-green-600', bg: 'bg-green-50' },
    { label: 'Total Listings', value: data.totalListings ?? '—', color: 'text-purple-600', bg: 'bg-purple-50' },
    { label: 'Active Listings', value: data.activeListings ?? '—', color: 'text-indigo-600', bg: 'bg-indigo-50' },
    { label: 'Flagged', value: data.flagged ?? '—', color: 'text-yellow-600', bg: 'bg-yellow-50' },
    { label: 'Revenue', value: data.revenue != null ? `$${Number(data.revenue).toLocaleString()}` : '—', color: 'text-green-700', bg: 'bg-green-50' },
  ] : [];

  return (
    <div className="p-8">
      <h1 className="text-2xl font-bold text-gray-900 mb-1">Analytics & Reports</h1>
      <p className="text-gray-400 text-sm mb-6">Generate reports and view insights</p>

      {data ? (
        <>
          <div className="grid grid-cols-2 md:grid-cols-3 gap-4 mb-8">
            {stats.map(s => (
              <div key={s.label} className={`card p-5 ${s.bg}`}>
                <p className="text-xs text-gray-500 font-medium mb-1">{s.label}</p>
                <p className={`text-3xl font-bold ${s.color}`}>{s.value}</p>
              </div>
            ))}
          </div>

          {data.topCreators?.length > 0 && (
            <div className="card p-5 mb-6">
              <h2 className="font-bold text-gray-900 mb-3">Top Sellers by Listings</h2>
              <div className="space-y-2">
                {data.topCreators.map((c, i) => (
                  <div key={i} className="flex items-center justify-between text-sm">
                    <span className="text-gray-700">{c.username ?? c.name ?? `User ${i + 1}`}</span>
                    <span className="font-medium text-gray-900">{c.count ?? c.listingCount ?? 0} listings</span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </>
      ) : (
        <div className="text-center py-12 text-gray-400">Loading analytics…</div>
      )}

      <div className="card p-5">
        <h2 className="font-bold text-gray-900 mb-4">Generate Reports</h2>
        <div className="grid md:grid-cols-3 gap-4">
          {REPORTS.map(r => (
            <button
              key={r.label}
              onClick={() => alert(`Generating ${r.label}…`)}
              className="flex flex-col items-center gap-2 p-5 border border-gray-200 rounded-xl hover:bg-gray-50 hover:border-gray-300 transition-colors"
            >
              <span className={`text-3xl ${r.color}`}>{r.icon}</span>
              <span className="font-medium text-sm text-gray-900">{r.label}</span>
              <span className="text-xs text-gray-400">{r.sub}</span>
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
