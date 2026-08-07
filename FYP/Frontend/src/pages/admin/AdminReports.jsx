/*
 * User reports at "/admin/reports". ADMIN only.
 * Reads GET /api/admin/reports and calls resolve, reopen and reply. Two kinds of report land
 * in the same list: an account report raised from a public seller profile, and a listing
 * report raised from an auction page. They are stored in separate tables, so every call has
 * to carry the type as well as the id, and rows are keyed by both.
 * The reply written here is what the reporter sees on their own profile page under Reports,
 * so this is the moderation team answering the person who complained.
 * Resolve and reopen are the same flag in both directions, which lets a report closed by
 * mistake be put back into the queue.
 */
import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { CheckCircle, XCircle, AlertCircle } from 'lucide-react';
import { getAdminReports, resolveReport, dismissReport, replyToReport } from '../../api/admin';
import Modal from '../../components/Modal';
import { apiErrorMessage } from '../../utils/apiError';

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

  // Ids are only unique within their own table, so an account report and a listing report can
  // share a number. Identity has to be the pair, or patching one would hit the other.
  const sameReport = (a, b) => a.id === b.id && (a.type ?? 'account') === (b.type ?? 'account');

  // Updates the row in the table and the open dialog together, so the badge in the modal
  // changes as soon as the action succeeds.
  const patch = (report, patch) => {
    setReports(prev => prev.map(r => sameReport(r, report) ? { ...r, ...patch } : r));
    if (selected && sameReport(selected, report)) setSelected(s => ({ ...s, ...patch }));
  };

  // Seeds the textarea with any reply already saved, so reopening a report shows the previous
  // response instead of a blank box that would overwrite it.
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
    } catch (err) {
      setMsg(apiErrorMessage(err, 'Failed to resolve report.'));
    }
  };

  const handleDismiss = async (report) => {
    try {
      await dismissReport(report.id, report.type);
      patch(report, { resolved: false });
      setMsg('Report reopened.');
    } catch (err) {
      setMsg(apiErrorMessage(err, 'Failed to reopen report.'));
    }
  };

  // Saves the moderation reply. Older rows can arrive without a type, so it falls back to
  // 'account' and writes the resolved value back onto the row to keep later calls consistent.
  const handleReply = async () => {
    if (!selected || !replyText.trim()) return;
    try {
      const reportType = selected.type || 'account';
      await replyToReport(selected.id, reportType, replyText.trim());
      patch(selected, { admin_reply: replyText.trim(), type: reportType });
      setMsg('Reply saved.');
    } catch (err) {
      setMsg(apiErrorMessage(err, 'Failed to save reply.'));
    }
  };

  const visible = reports.filter(r => {
    if (filter === 'open') return !r.resolved;
    if (filter === 'resolved') return r.resolved;
    return true;
  });

  return (
    <div className="p-8">
      <h1 className="page-title">User Reports</h1>
      <p className="page-subtitle mb-6">Click a report to read the full details and respond</p>

      <div className="flex gap-2 mb-5">
        {[['all', 'All'], ['open', 'Open'], ['resolved', 'Resolved']].map(([key, label]) => (
          <button
            key={key}
            onClick={() => setFilter(key)}
            className={`tab-pill ${filter === key ? 'tab-pill-active' : ''}`}
          >
            {label}
            {key === 'open' && (
              <span className="ml-1.5 bg-red-500 text-white text-xs font-bold rounded-full px-1.5 py-0.5">
                {reports.filter(r => !r.resolved).length}
              </span>
            )}
          </button>
        ))}
      </div>

      <div className="card overflow-hidden">
        {loading ? (
          <div className="text-center py-10 text-ink-400 text-sm">Loading reports…</div>
        ) : visible.length === 0 ? (
          <div className="text-center py-10 text-ink-400 text-sm">No reports found.</div>
        ) : (
          <table className="w-full text-sm">
            <thead className="text-xs text-ink-500 uppercase tracking-wider bg-ink-50 border-b border-ink-200">
              <tr>
                {['Type', 'Reporter', 'Target', 'Reason', 'Date', 'Status'].map(h => (
                  <th key={h} className="px-4 py-3 text-left font-bold whitespace-nowrap">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-ink-100">
              {visible.map(report => (
                <tr
                  key={`${report.type ?? 'account'}-${report.id}`}
                  onClick={() => openReport(report)}
                  className="hover:bg-primary-50/60 cursor-pointer transition-colors"
                >
                  <td className="px-4 py-3">
                    <span className={`badge ${
                      report.type === 'listing' ? 'bg-accent-50 text-accent-700 ring-accent-200' : 'bg-purple-50 text-purple-700 ring-purple-200'
                    }`}>
                      {report.type === 'listing' ? 'Listing' : 'User'}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-ink-700">{report.reporter_name ?? `#${report.reporter_id}`}</td>
                  <td className="px-4 py-3 text-ink-700">{report.target_name ?? `#${report.target_id}`}</td>
                  <td className="px-4 py-3 font-medium text-ink-900 max-w-xs truncate">{report.reason ?? '—'}</td>
                  <td className="px-4 py-3 text-ink-400 whitespace-nowrap">
                    {report.created_at ? new Date(report.created_at).toLocaleDateString() : '—'}
                  </td>
                  <td className="px-4 py-3">
                    <span className={`badge ${
                      report.resolved ? 'bg-emerald-50 text-emerald-700 ring-emerald-200' : 'bg-amber-50 text-amber-700 ring-amber-200'
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
        <Modal
          title={selected.reason ?? 'Report'}
          subtitle={selected.type === 'listing' ? 'Listing report' : 'User report'}
          icon={AlertCircle}
          size="xl"
          onClose={() => setSelected(null)}
        >
          <div className="p-6">
            <div className="flex justify-end mb-4">
              <span className={`badge ${
                selected.resolved ? 'bg-emerald-50 text-emerald-700 ring-emerald-200' : 'bg-amber-50 text-amber-700 ring-amber-200'
              }`}>
                {selected.resolved ? 'Resolved' : 'Open'}
              </span>
            </div>

            <dl className="text-sm space-y-2 mb-4">
              <div><span className="text-ink-400">Reporter: </span>{selected.reporter_name} (#{selected.reporter_id})</div>
              <div><span className="text-ink-400">Target: </span>{selected.target_name} (#{selected.target_id})</div>
              {selected.auction_id && (
                <div>
                  <span className="text-ink-400">Auction: </span>
                  <Link to={`/auction/${selected.auction_id}`} className="link-subtle">#{selected.auction_id}</Link>
                </div>
              )}
              <div><span className="text-ink-400">Submitted: </span>
                {selected.created_at ? new Date(selected.created_at).toLocaleString() : '—'}
              </div>
            </dl>

            <div className="bg-ink-50 rounded-lg p-4 mb-4">
              <p className="text-xs font-semibold text-ink-500 mb-1">User message</p>
              <p className="text-sm text-ink-800 whitespace-pre-wrap">{selected.comment || '—'}</p>
            </div>

            <div className="mb-4">
              <label className="text-xs font-semibold text-ink-500 block mb-1">Admin reply</label>
              <textarea
                value={replyText}
                onChange={e => setReplyText(e.target.value)}
                rows={4}
                placeholder="Write a response to the reporter…"
                className="textarea-field"
              />
            </div>

            {msg && <div className="text-sm text-primary-600 mb-3">{msg}</div>}

            <div className="flex flex-wrap gap-2">
              <button onClick={handleReply} className="btn-primary">Save reply</button>
              {!selected.resolved ? (
                <button onClick={() => handleResolve(selected)} className="btn-success">
                  <CheckCircle size={14} /> Resolve
                </button>
              ) : (
                <button onClick={() => handleDismiss(selected)} className="btn-secondary">
                  <XCircle size={14} /> Reopen
                </button>
              )}
              <button onClick={() => setSelected(null)} className="btn-ghost ml-auto">Close</button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  );
}
