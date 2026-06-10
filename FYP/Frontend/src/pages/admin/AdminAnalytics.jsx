import { useState, useEffect } from 'react';
import {
  getAdminAnalytics, downloadAdminReport,
  getAdminUsers, emailSellerAnalytics, emailAllSellerAnalytics,
} from '../../api/admin';
import { apiErrorMessage } from '../../utils/apiError';

const REPORTS = [
  { icon: '📄', label: 'User Activity Report', sub: 'Export user statistics', color: 'text-blue-500', type: 'user-activity', filename: 'user-activity-report.txt' },
  { icon: '📗', label: 'Revenue Report', sub: 'Financial analytics', color: 'text-green-500', type: 'revenue', filename: 'revenue-report.txt' },
  { icon: '📕', label: 'Moderation Report', sub: 'Flags and bans summary', color: 'text-purple-500', type: 'moderation', filename: 'moderation-report.txt' },
];

function triggerBlobDownload(blob, filename) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

export default function AdminAnalytics() {
  const [data, setData] = useState(null);
  const [sellers, setSellers] = useState([]);
  const [selectedSellerId, setSelectedSellerId] = useState('');
  const [msg, setMsg] = useState('');
  const [reportBusy, setReportBusy] = useState(null);
  const [emailBusy, setEmailBusy] = useState(false);

  useEffect(() => {
    getAdminAnalytics().then(r => setData(r.data)).catch(() => {});
    getAdminUsers()
      .then(r => setSellers((r.data ?? []).filter(u => u.role === 'SELLER' && u.statusId === 1)))
      .catch(() => {});
  }, []);

  const stats = data ? [
    { label: 'Total Users', value: data.totalUsers ?? '—', color: 'text-blue-600', bg: 'bg-blue-50' },
    { label: 'Active Users', value: data.activeUsers ?? '—', color: 'text-green-600', bg: 'bg-green-50' },
    { label: 'Total Listings', value: data.totalListings ?? '—', color: 'text-purple-600', bg: 'bg-purple-50' },
    { label: 'Active Listings', value: data.activeListings ?? '—', color: 'text-indigo-600', bg: 'bg-indigo-50' },
    { label: 'Flagged', value: data.flagged ?? '—', color: 'text-yellow-600', bg: 'bg-yellow-50' },
    { label: 'Revenue', value: data.revenue != null ? `$${Number(data.revenue).toLocaleString()}` : '—', color: 'text-green-700', bg: 'bg-green-50' },
  ] : [];

  const handleDownloadReport = async (report) => {
    setReportBusy(report.type);
    setMsg('');
    try {
      const r = await downloadAdminReport(report.type);
      triggerBlobDownload(r.data, report.filename);
      setMsg(`${report.label} downloaded.`);
    } catch (err) {
      setMsg(apiErrorMessage(err, `Could not generate ${report.label}.`));
    } finally {
      setReportBusy(null);
    }
  };

  const handleEmailSeller = async () => {
    if (!selectedSellerId) {
      setMsg('Select a seller first.');
      return;
    }
    setEmailBusy(true);
    setMsg('');
    try {
      const r = await emailSellerAnalytics(selectedSellerId);
      setMsg(r.data?.message ?? 'Analytics email sent.');
    } catch (err) {
      setMsg(apiErrorMessage(err, 'Could not send analytics email.'));
    } finally {
      setEmailBusy(false);
    }
  };

  const handleEmailAllSellers = async () => {
    if (!window.confirm(`Email analytics reports to all ${sellers.length} active sellers?`)) return;
    setEmailBusy(true);
    setMsg('');
    try {
      const r = await emailAllSellerAnalytics();
      setMsg(r.data?.message ?? 'Analytics emails sent.');
    } catch (err) {
      setMsg(apiErrorMessage(err, 'Could not send analytics emails.'));
    } finally {
      setEmailBusy(false);
    }
  };

  return (
    <div className="p-8">
      <h1 className="text-2xl font-bold text-gray-900 mb-1">Analytics & Reports</h1>
      <p className="text-gray-400 text-sm mb-6">Generate reports and view insights</p>

      {msg && <div className="text-sm text-blue-600 mb-4">{msg}</div>}

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

      <div className="card p-5 mb-6">
        <h2 className="font-bold text-gray-900 mb-4">Generate Reports</h2>
        <div className="grid md:grid-cols-3 gap-4">
          {REPORTS.map(r => (
            <button
              key={r.label}
              type="button"
              onClick={() => handleDownloadReport(r)}
              disabled={reportBusy === r.type}
              className="flex flex-col items-center gap-2 p-5 border border-gray-200 rounded-xl hover:bg-gray-50 hover:border-gray-300 transition-colors disabled:opacity-50"
            >
              <span className={`text-3xl ${r.color}`}>{r.icon}</span>
              <span className="font-medium text-sm text-gray-900">{r.label}</span>
              <span className="text-xs text-gray-400">{reportBusy === r.type ? 'Generating…' : r.sub}</span>
            </button>
          ))}
        </div>
      </div>

      <div className="card p-5">
        <h2 className="font-bold text-gray-900 mb-1">Seller Analytics Email</h2>
        <p className="text-sm text-gray-400 mb-4">Send a seller analytics report by email (admin-initiated)</p>
        <div className="flex flex-wrap items-end gap-3">
          <div>
            <label className="block text-xs text-gray-500 mb-1">Seller</label>
            <select
              value={selectedSellerId}
              onChange={e => setSelectedSellerId(e.target.value)}
              className="border border-gray-200 rounded-lg px-3 py-2 text-sm min-w-[200px]"
            >
              <option value="">Select seller…</option>
              {sellers.map(s => (
                <option key={s.id} value={s.id}>{s.username} ({s.email})</option>
              ))}
            </select>
          </div>
          <button
            type="button"
            onClick={handleEmailSeller}
            disabled={emailBusy || !selectedSellerId}
            className="px-4 py-2 bg-blue-500 text-white text-sm rounded-lg hover:bg-blue-600 disabled:opacity-50"
          >
            Email selected seller
          </button>
          <button
            type="button"
            onClick={handleEmailAllSellers}
            disabled={emailBusy || sellers.length === 0}
            className="px-4 py-2 border border-gray-200 text-sm rounded-lg hover:bg-gray-50 disabled:opacity-50"
          >
            Email all active sellers
          </button>
        </div>
      </div>
    </div>
  );
}
