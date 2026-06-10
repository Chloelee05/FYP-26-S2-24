import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { CheckCircle, XCircle } from 'lucide-react';
import { getAdminReports, resolveReport, dismissReport, replyToReport } from '../../api/admin';

export default function AdminReports() {
  const [reports, setReports] = useState([]);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState('all');
  const [selected, setSelected] = useState(null);
  const [replyText, setReplyText] = useState('');
  const [msg, setMsg] = useState('');

  const reload = () => getAdminReports()
    .then(r => setReports(r.data ?? []))
    .catch(() => {})
    .finally(() => setLoading(false));

  useEffect(() => { reload(); }, []);

  const sameReport = (a, b) => a.id === b.id && (a.type ?? 'account') === (b.type ?? 'account');

  const patch = (report, patch) => {
    setReports(prev => prev.map(r => sameReport(r, report) ? { ...r, ...patch } : r));
    if (selected && sameReport(selected, report)) setSelected(s => ({ ...s, ...patch }));
  };

  const openReport = (report) => {
    setSelected(report);
    setReplyText(report.admin_reply ?? '');
    setMsg('');
  };

  const handleResolve = async (report) => {
    try {
      await resolveReport(report.id, report.type);
      patch(report, { resolved: true });
      setMsg('Marked as resolved.');
    } catch {
      setMsg('Failed to resolve report.');
    }
  };

  const handleDismiss = async (report) => {
    try {
      await dismissReport(report.id, report.type);
      patch(report, { resolved: false });
      setMsg('Report reopened.');
    } catch {
      setMsg('Failed to reopen report.');
    }
  };

  const handleReply = async () => {
    if (!selected || !replyText.trim()) return;
    try {
      const reportType = selected.type || 'account';
      await replyToReport(selected.id, reportType, replyText.trim());
      patch(selected, { admin_reply: replyText.trim(), type: reportType });
      setMsg('Reply saved.');
    } catch (err) {
      setMsg(err.response?.data?.error || 'Failed to save reply.');
    }
  };

  const visible = reports.filter(r => {
    if (filter === 'open') return !r.resolved;
    if (filter === 'resolved') return r.resolved;
    return true;
  });

  return (
    <div className="p-8">
      <h1 className="text-2xl font-bold text-gray-900 mb-1">User Reports</h1>
      <p className="text-gray-400 text-sm mb-6">Click a report to read the full details and respond</p>

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
                {['Type', 'Reporter', 'Target', 'Reason', 'Date', 'Status'].map(h => (
                  <th key={h} className="px-4 py-3 text-left font-semibold">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-50">
              {visible.map(report => (
                <tr
                  key={`${report.type ?? 'account'}-${report.id}`}
                  onClick={() => openReport(report)}
                  className="hover:bg-blue-50 cursor-pointer"
                >
                  <td className="px-4 py-3">
                    <span className={`px-2 py-0.5 rounded text-xs font-medium ${
                      report.type === 'listing' ? 'bg-orange-100 text-orange-600' : 'bg-purple-100 text-purple-600'
                    }`}>
                      {report.type === 'listing' ? 'Listing' : 'User'}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-gray-700">{report.reporter_name ?? `#${report.reporter_id}`}</td>
                  <td className="px-4 py-3 text-gray-700">{report.target_name ?? `#${report.target_id}`}</td>
                  <td className="px-4 py-3 font-medium text-gray-900 max-w-xs truncate">{report.reason ?? '—'}</td>
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
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {selected && (
        <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4" onClick={() => setSelected(null)}>
          <div className="bg-white rounded-2xl shadow-xl max-w-xl w-full p-6 max-h-[90vh] overflow-y-auto" onClick={e => e.stopPropagation()}>
            <div className="flex items-start justify-between mb-4">
              <div>
                <span className={`px-2 py-0.5 rounded text-xs font-medium ${
                  selected.type === 'listing' ? 'bg-orange-100 text-orange-600' : 'bg-purple-100 text-purple-600'
                }`}>
                  {selected.type === 'listing' ? 'Listing report' : 'User report'}
                </span>
                <h2 className="text-lg font-bold text-gray-900 mt-2">{selected.reason ?? 'Report'}</h2>
              </div>
              <span className={`px-2 py-0.5 rounded text-xs font-medium ${
                selected.resolved ? 'bg-green-100 text-green-600' : 'bg-yellow-100 text-yellow-600'
              }`}>
                {selected.resolved ? 'Resolved' : 'Open'}
              </span>
            </div>

            <dl className="text-sm space-y-2 mb-4">
              <div><span className="text-gray-400">Reporter: </span>{selected.reporter_name} (#{selected.reporter_id})</div>
              <div><span className="text-gray-400">Target: </span>{selected.target_name} (#{selected.target_id})</div>
              {selected.auction_id && (
                <div>
                  <span className="text-gray-400">Auction: </span>
                  <Link to={`/auction/${selected.auction_id}`} className="text-blue-500 hover:underline">#{selected.auction_id}</Link>
                </div>
              )}
              <div><span className="text-gray-400">Submitted: </span>
                {selected.created_at ? new Date(selected.created_at).toLocaleString() : '—'}
              </div>
            </dl>

            <div className="bg-gray-50 rounded-lg p-4 mb-4">
              <p className="text-xs font-semibold text-gray-500 mb-1">User message</p>
              <p className="text-sm text-gray-800 whitespace-pre-wrap">{selected.comment || '—'}</p>
            </div>

            <div className="mb-4">
              <label className="text-xs font-semibold text-gray-500 block mb-1">Admin reply</label>
              <textarea
                value={replyText}
                onChange={e => setReplyText(e.target.value)}
                rows={4}
                placeholder="Write a response to the reporter…"
                className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-100 resize-none"
              />
            </div>

            {msg && <div className="text-sm text-blue-600 mb-3">{msg}</div>}

            <div className="flex flex-wrap gap-2">
              <button onClick={handleReply} className="px-4 py-2 bg-blue-500 text-white text-sm rounded-lg hover:bg-blue-600">Save reply</button>
              {!selected.resolved ? (
                <button onClick={() => handleResolve(selected)} className="flex items-center gap-1 px-4 py-2 bg-green-500 text-white text-sm rounded-lg hover:bg-green-600">
                  <CheckCircle size={14} /> Resolve
                </button>
              ) : (
                <button onClick={() => handleDismiss(selected)} className="flex items-center gap-1 px-4 py-2 border border-gray-200 text-gray-600 text-sm rounded-lg hover:bg-gray-50">
                  <XCircle size={14} /> Reopen
                </button>
              )}
              <button onClick={() => setSelected(null)} className="px-4 py-2 text-sm text-gray-500 hover:text-gray-700 ml-auto">Close</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
