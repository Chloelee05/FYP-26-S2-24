import { useState, useEffect } from 'react';
import { CheckCircle, XCircle } from 'lucide-react';
import { getAdminReports, resolveReport, dismissReport } from '../../api/admin';

export default function AdminReports() {
  const [reports, setReports] = useState([]);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState('all'); // 'all' | 'open' | 'resolved'

  useEffect(() => {
    getAdminReports()
      .then(r => setReports(r.data ?? []))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  const sameReport = (a, b) => a.id === b.id && (a.type ?? 'account') === (b.type ?? 'account');

  const handleResolve = async (report) => {
    try {
      await resolveReport(report.id, report.type);
      setReports(prev => prev.map(r => sameReport(r, report) ? { ...r, resolved: true } : r));
    } catch {
      alert('Failed to resolve report.');
    }
  };

  const handleDismiss = async (report) => {
    try {
      await dismissReport(report.id, report.type);
      setReports(prev => prev.map(r => sameReport(r, report) ? { ...r, resolved: false } : r));
    } catch {
      alert('Failed to dismiss report.');
    }
  };

  const visible = reports.filter(r => {
    if (filter === 'open')     return !r.resolved;
    if (filter === 'resolved') return r.resolved;
    return true;
  });

  return (
    <div className="p-8">
      <h1 className="text-2xl font-bold text-gray-900 mb-1">User Reports</h1>
      <p className="text-gray-400 text-sm mb-6">Account reports submitted by users</p>

      {/* Filter tabs */}
      <div className="flex gap-2 mb-5">
        {[['all', 'All'], ['open', 'Open'], ['resolved', 'Resolved']].map(([key, label]) => (
          <button
            key={key}
            onClick={() => setFilter(key)}
            className={`px-4 py-1.5 rounded-full text-sm font-medium transition-colors ${
              filter === key ? 'bg-blue-500 text-white' : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
            }`}
          >
            {label}
            {key === 'open' && (
              <span className="ml-1.5 bg-red-500 text-white text-xs rounded-full px-1.5 py-0.5">
                {reports.filter(r => !r.resolved).length}
              </span>
            )}
          </button>
        ))}
      </div>

      <div className="card overflow-hidden">
        {loading ? (
          <div className="text-center py-10 text-gray-400 text-sm">Loading reports…</div>
        ) : visible.length === 0 ? (
          <div className="text-center py-10 text-gray-400 text-sm">No reports found.</div>
        ) : (
          <table className="w-full text-sm">
            <thead className="text-xs text-gray-400 uppercase tracking-wide bg-gray-50">
              <tr>
                <th className="px-4 py-3 text-left font-semibold">Type</th>
                <th className="px-4 py-3 text-left font-semibold">Reporter ID</th>
                <th className="px-4 py-3 text-left font-semibold">Target ID</th>
                <th className="px-4 py-3 text-left font-semibold">Reason</th>
                <th className="px-4 py-3 text-left font-semibold">Comment</th>
                <th className="px-4 py-3 text-left font-semibold">Date</th>
                <th className="px-4 py-3 text-left font-semibold">Status</th>
                <th className="px-4 py-3 text-left font-semibold">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-50">
              {visible.map(report => (
                <tr key={`${report.type ?? 'account'}-${report.id}`} className="hover:bg-gray-50">
                  <td className="px-4 py-3">
                    <span className={`px-2 py-0.5 rounded text-xs font-medium ${
                      report.type === 'listing' ? 'bg-orange-100 text-orange-600' : 'bg-purple-100 text-purple-600'
                    }`}>
                      {report.type === 'listing' ? 'Listing' : 'User'}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-gray-500">#{report.reporter_id}</td>
                  <td className="px-4 py-3 text-gray-500">#{report.target_id}</td>
                  <td className="px-4 py-3 font-medium text-gray-900">{report.reason ?? '—'}</td>
                  <td className="px-4 py-3 text-gray-500 max-w-xs truncate">{report.comment ?? '—'}</td>
                  <td className="px-4 py-3 text-gray-400 whitespace-nowrap">
                    {report.created_at ? new Date(report.created_at).toLocaleDateString() : '—'}
                  </td>
                  <td className="px-4 py-3">
                    <span className={`px-2 py-0.5 rounded text-xs font-medium ${
                      report.resolved ? 'bg-green-100 text-green-600' : 'bg-yellow-100 text-yellow-600'
                    }`}>
                      {report.resolved ? 'Resolved' : 'Open'}
                    </span>
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex gap-2">
                      {!report.resolved && (
                        <button
                          onClick={() => handleResolve(report)}
                          className="flex items-center gap-1 text-xs text-green-600 hover:text-green-700 font-medium"
                          title="Mark resolved"
                        >
                          <CheckCircle size={14} /> Resolve
                        </button>
                      )}
                      {report.resolved && (
                        <button
                          onClick={() => handleDismiss(report)}
                          className="flex items-center gap-1 text-xs text-gray-400 hover:text-gray-600 font-medium"
                          title="Reopen"
                        >
                          <XCircle size={14} /> Reopen
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
