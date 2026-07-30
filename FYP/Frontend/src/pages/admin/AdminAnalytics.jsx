import { useState, useEffect } from 'react';
import { FileText, TrendingUp, ShieldAlert } from 'lucide-react';
import {
  getAdminAnalytics, downloadAdminReport,
  getAdminUsers, emailSellerAnalytics, emailAllSellerAnalytics,
  getRecommendationConfig, saveRecommendationConfig,
} from '../../api/admin';
import { apiErrorMessage } from '../../utils/apiError';
import RecommendationAttributionPanel from './RecommendationAttributionPanel';

const REPORTS = [
  { icon: FileText, label: 'User Activity Report', sub: 'Export user statistics', color: 'text-primary-600', bg: 'bg-primary-50', type: 'user-activity', filename: 'user-activity-report.txt' },
  { icon: TrendingUp, label: 'Revenue Report', sub: 'Financial analytics', color: 'text-emerald-600', bg: 'bg-emerald-50', type: 'revenue', filename: 'revenue-report.txt' },
  { icon: ShieldAlert, label: 'Moderation Report', sub: 'Flags and bans summary', color: 'text-purple-600', bg: 'bg-purple-50', type: 'moderation', filename: 'moderation-report.txt' },
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
  const [recMetrics, setRecMetrics] = useState(null);
  const [recForm, setRecForm] = useState({ itemsShown: 8, similarityThreshold: 0.1 });
  const [recSaving, setRecSaving] = useState(false);

  useEffect(() => {
    getAdminAnalytics().then(r => setData(r.data)).catch(() => {});
    getAdminUsers()
      // Sellers are identified by the capability: merged accounts keep the BUYER role.
      .then(r => setSellers((r.data ?? []).filter(u => u.canSell && u.statusId === 1)))
      .catch(() => {});
    getRecommendationConfig()
      .then(r => {
        setRecMetrics(r.data?.metrics ?? null);
        if (r.data?.settings) setRecForm(r.data.settings);
      })
      .catch(() => {});
  }, []);

  const handleSaveRecSettings = async () => {
    setRecSaving(true);
    setMsg('');
    try {
      const r = await saveRecommendationConfig(recForm.itemsShown, recForm.similarityThreshold);
      setMsg(r.data?.message ?? 'Recommendation settings saved.');
    } catch (err) {
      setMsg(apiErrorMessage(err, 'Could not save recommendation settings.'));
    } finally {
      setRecSaving(false);
    }
  };

  const stats = data ? [
    { label: 'Total Users', value: data.totalUsers ?? '—', color: 'text-primary-600', bg: 'bg-primary-50' },
    { label: 'Active Users', value: data.activeUsers ?? '—', color: 'text-green-600', bg: 'bg-green-50' },
    { label: 'Total Listings', value: data.totalListings ?? '—', color: 'text-purple-600', bg: 'bg-purple-50' },
    { label: 'Active Listings', value: data.activeListings ?? '—', color: 'text-indigo-600', bg: 'bg-indigo-50' },
    { label: 'Flagged', value: data.flagged ?? '—', color: 'text-yellow-600', bg: 'bg-yellow-50' },
    { label: 'Revenue', value: data.revenue != null ? `$${Number(data.revenue).toLocaleString()}` : '—', color: 'text-green-700', bg: 'bg-green-50' },
    { label: 'Commission Revenue (6%)', value: data.platformCommissionRevenue != null ? `$${Number(data.platformCommissionRevenue).toLocaleString(undefined, { minimumFractionDigits: 2 })}` : '—', color: 'text-emerald-700', bg: 'bg-emerald-50' },
    { label: 'Featured Listing Fees', value: data.featuredListingRevenue != null ? `$${Number(data.featuredListingRevenue).toLocaleString(undefined, { minimumFractionDigits: 2 })}` : '—', color: 'text-amber-700', bg: 'bg-amber-50' },
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
      <h1 className="page-title">Analytics & Reports</h1>
      <p className="page-subtitle mb-6">Generate reports and view insights</p>

      {msg && <div className="text-sm text-primary-600 mb-4">{msg}</div>}

      {data ? (
        <>
          <div className="grid grid-cols-2 md:grid-cols-3 gap-4 mb-8">
            {stats.map(s => (
              <div key={s.label} className={`card p-5 ${s.bg}`}>
                <p className="text-xs text-ink-500 font-medium mb-1">{s.label}</p>
                <p className={`text-3xl font-bold ${s.color}`}>{s.value}</p>
              </div>
            ))}
          </div>

          <div className="grid md:grid-cols-2 gap-6 mb-6">
            {data.topCreators?.length > 0 && (
              <div className="card p-5">
                <h2 className="section-title text-base mb-3">Top Sellers by Listings</h2>
                <div className="space-y-2">
                  {data.topCreators.map((c, i) => (
                    <div key={i} className="flex items-center justify-between text-sm">
                      <span className="text-ink-700">{c.user?.username ?? c.username ?? `User ${i + 1}`}</span>
                      <span className="font-medium text-ink-900">{c.auction_count ?? c.count ?? 0} listings</span>
                    </div>
                  ))}
                </div>
              </div>
            )}
            {data.topRevenue?.length > 0 && (
              <div className="card p-5">
                <h2 className="section-title text-base mb-3">Top Sellers by Revenue</h2>
                <div className="space-y-2">
                  {data.topRevenue.map((c, i) => (
                    <div key={i} className="flex items-center justify-between text-sm">
                      <span className="text-ink-700">{c.user?.username ?? c.username ?? `User ${i + 1}`}</span>
                      <span className="font-medium text-ink-900">
                        ${Number(c.total_revenue ?? c.revenue ?? 0).toLocaleString(undefined, { minimumFractionDigits: 2 })}
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        </>
      ) : (
        <div className="text-center py-12 text-ink-400">Loading analytics…</div>
      )}

      <div className="card p-5 mb-6">
        <h2 className="section-title text-base mb-4">Generate Reports</h2>
        <div className="grid md:grid-cols-3 gap-4">
          {REPORTS.map(({ icon: ReportIcon, ...r }) => (
            <button
              key={r.label}
              type="button"
              onClick={() => handleDownloadReport(r)}
              disabled={reportBusy === r.type}
              className="flex flex-col items-center gap-2 p-5 border border-ink-200 rounded-xl hover:bg-ink-50 hover:border-ink-300 transition-colors disabled:opacity-50"
            >
              <span className={`grid place-items-center w-12 h-12 rounded-2xl ${r.bg} ${r.color}`}>
                <ReportIcon size={22} />
              </span>
              <span className="font-semibold text-sm text-ink-900">{r.label}</span>
              <span className="text-xs text-ink-400">{reportBusy === r.type ? 'Generating…' : r.sub}</span>
            </button>
          ))}
        </div>
      </div>

      <div className="card p-5 mb-6">
        <h2 className="font-bold text-ink-900 mb-1">Recommendation System</h2>
        <p className="text-sm text-ink-400 mb-4">Performance of the "Recommended for You" section and tunable parameters</p>

        <div className="grid grid-cols-2 md:grid-cols-5 gap-4 mb-5">
          {[
            { label: 'Impressions', value: recMetrics?.impressions ?? '—' },
            { label: 'Clicks', value: recMetrics?.clicks ?? '—' },
            { label: 'Bids After Click', value: recMetrics?.conversions ?? '—' },
            { label: 'Click-Through Rate', value: recMetrics ? `${(recMetrics.clickThroughRate * 100).toFixed(2)}%` : '—' },
            { label: 'Conversion Rate', value: recMetrics ? `${(recMetrics.conversionRate * 100).toFixed(2)}%` : '—' },
          ].map(m => (
            <div key={m.label} className="bg-ink-50 rounded-xl p-4">
              <p className="text-xs text-ink-500 font-medium mb-1">{m.label}</p>
              <p className="text-xl font-bold text-ink-900">{m.value}</p>
            </div>
          ))}
        </div>

        <div className="flex flex-wrap items-end gap-3">
          <div>
            <label className="block text-xs text-ink-500 mb-1">Items shown (1–24)</label>
            <input
              type="number" min="1" max="24"
              value={recForm.itemsShown}
              onChange={e => setRecForm(f => ({ ...f, itemsShown: e.target.value }))}
              className="border border-ink-200 rounded-lg px-3 py-2 text-sm w-32"
            />
          </div>
          <div>
            <label className="block text-xs text-ink-500 mb-1">Similarity threshold (0–1)</label>
            <input
              type="number" min="0" max="1" step="0.05"
              value={recForm.similarityThreshold}
              onChange={e => setRecForm(f => ({ ...f, similarityThreshold: e.target.value }))}
              className="border border-ink-200 rounded-lg px-3 py-2 text-sm w-40"
            />
          </div>
          <button
            type="button"
            onClick={handleSaveRecSettings}
            disabled={recSaving}
            className="btn-primary"
          >
            {recSaving ? 'Saving…' : 'Save settings'}
          </button>
        </div>
      </div>

      <RecommendationAttributionPanel />

      <div className="card p-5">
        <h2 className="font-bold text-ink-900 mb-1">Seller Analytics Email</h2>
        <p className="text-sm text-ink-400 mb-4">Send a seller analytics report by email (admin-initiated)</p>
        <div className="flex flex-wrap items-end gap-3">
          <div>
            <label className="block text-xs text-ink-500 mb-1">Seller</label>
            <select
              value={selectedSellerId}
              onChange={e => setSelectedSellerId(e.target.value)}
              className="border border-ink-200 rounded-lg px-3 py-2 text-sm min-w-[200px]"
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
            className="btn-primary"
          >
            Email selected seller
          </button>
          <button
            type="button"
            onClick={handleEmailAllSellers}
            disabled={emailBusy || sellers.length === 0}
            className="px-4 py-2 border border-ink-200 text-sm rounded-lg hover:bg-ink-50 disabled:opacity-50"
          >
            Email all active sellers
          </button>
        </div>
      </div>
    </div>
  );
}
