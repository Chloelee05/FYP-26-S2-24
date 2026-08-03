import { useState, useEffect } from 'react';
import { FileText, TrendingUp, ShieldAlert } from 'lucide-react';
import {
  getAdminAnalytics, downloadAdminReport,
  getAdminUsers, emailSellerAnalytics, emailAllSellerAnalytics,
  getSellerAnalyticsReport,
  getRecommendationConfig, saveRecommendationConfig,
} from '../../api/admin';
import { apiErrorMessage } from '../../utils/apiError';
import RecommendationAttributionPanel from './RecommendationAttributionPanel';

// Readable names for the pipeline arms recorded on each impression / click.
// TRENDING_CONTROL is the plain popularity strip at the bottom of the landing page, which
// the recommender plays no part in — it is the baseline the personalised arms are read
// against, not an experiment arm anyone is randomised into.
const ARM_LABELS = {
  PEER_BIDS: 'Peer bids (item CF)',
  SIMILAR_TASTE: 'Similar taste (user CF)',
  SAME_CATEGORY: 'Content match',
  SEARCH_KEYWORD: 'Search keyword',
  TRENDING: 'Trending filler',
  TRENDING_CONTROL: 'Popularity baseline',
};

const percent = (v) => `${((v ?? 0) * 100).toFixed(2)}%`;

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
  const [sellerReport, setSellerReport] = useState(null);
  const [reportOnly, setReportOnly] = useState(false);
  const [recMetrics, setRecMetrics] = useState(null);
  const [recByReason, setRecByReason] = useState([]);
  const [recForm, setRecForm] = useState({
    itemsShown: 8, similarityThreshold: 0.1, trendingWindowDays: 7,
    weightBid: 3, weightWatchlist: 2, weightBrowse: 1,
    recencyTauDays: 30, contentWindowDays: 180,
    weightCf: 1, weightUbcf: 0.9, weightContent: 0.7,
    weightPopularity: 0.4, weightRecency: 0.2, diversityDivisor: 3,
  });
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
        setRecByReason(r.data?.metricsByReason ?? []);
        if (r.data?.settings) setRecForm(f => ({ ...f, ...r.data.settings }));
      })
      .catch(() => {});
  }, []);

  const handleSaveRecSettings = async () => {
    setRecSaving(true);
    setMsg('');
    try {
      const r = await saveRecommendationConfig(recForm);
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

  // Reads the report without sending anything, so the tool is demonstrable on a server
  // with no SMTP configured — which is every deployment of this app today.
  const handleViewReport = async () => {
    if (!selectedSellerId) {
      setMsg('Select a seller first.');
      return;
    }
    setEmailBusy(true);
    setMsg('');
    setSellerReport(null);
    try {
      const r = await getSellerAnalyticsReport(selectedSellerId);
      setSellerReport(r.data);
      setReportOnly(true);
      setMsg(r.data?.emailConfigured
        ? 'Report generated. Email is configured, so "Email selected seller" will deliver it.'
        : 'Report generated. Email is not configured on this server, so nothing would be sent.');
    } catch (err) {
      setMsg(apiErrorMessage(err, 'Could not generate the seller analytics report.'));
    } finally {
      setEmailBusy(false);
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
      // With SMTP unconfigured the server returns emailConfigured:false and hands the
      // report back inline rather than reporting a send that never happened.
      if (r.data?.report) {
        setSellerReport({
          report: r.data.report,
          emailConfigured: r.data.emailConfigured !== false,
          sellerUsername: sellers.find(s => String(s.id) === String(selectedSellerId))?.username,
        });
        setReportOnly(r.data.emailConfigured === false);
      }
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

      {/* Requirement (d): the admin generates and emails "data analytics" to a seller.
          Moved to the top of the page — it was the last card below a 300-line
          recommendation console, which is a poor place for a named requirement. */}
      <div className="card p-5 mb-6 border-l-4 border-primary-500">
        <h2 className="font-bold text-ink-900 mb-1">Seller Data Analytics</h2>
        <p className="text-sm text-ink-400 mb-4">
          Generate a seller's performance report — most popular product/service per calendar
          day, week, month and quarter, and the percentage of each star rating from buyers —
          then read it here or email it to them.
        </p>
        <div className="flex flex-wrap items-end gap-3">
          <div>
            <label className="block text-xs text-ink-500 mb-1" htmlFor="seller-select">Seller</label>
            <select
              id="seller-select"
              value={selectedSellerId}
              onChange={e => { setSelectedSellerId(e.target.value); setSellerReport(null); }}
              className="border border-ink-200 rounded-lg px-3 py-2 text-sm min-w-[240px]"
            >
              <option value="">Select seller…</option>
              {sellers.map(s => (
                <option key={s.id} value={s.id}>{s.username} ({s.email})</option>
              ))}
            </select>
          </div>
          <button
            type="button"
            onClick={handleViewReport}
            disabled={emailBusy || !selectedSellerId}
            className="btn-primary"
          >
            {emailBusy ? 'Generating…' : 'View report'}
          </button>
          <button
            type="button"
            onClick={handleEmailSeller}
            disabled={emailBusy || !selectedSellerId}
            className="btn-dark"
          >
            Email selected seller
          </button>
          <button
            type="button"
            onClick={handleEmailAllSellers}
            disabled={emailBusy || sellers.length === 0}
            className="px-4 py-2 border border-ink-200 text-sm rounded-lg hover:bg-ink-50 disabled:opacity-50"
          >
            Email all active sellers ({sellers.length})
          </button>
        </div>

        {sellerReport?.report && (
          <div className="mt-5 pt-5 border-t border-ink-100">
            <div className="flex flex-wrap items-center justify-between gap-2 mb-2">
              <h3 className="font-semibold text-sm text-ink-900">
                Report for {sellerReport.sellerUsername ?? 'the selected seller'}
              </h3>
              <button
                type="button"
                onClick={() => setSellerReport(null)}
                className="text-xs text-ink-400 hover:text-ink-600"
              >
                Hide
              </button>
            </div>
            {reportOnly && (
              <p className="text-xs text-amber-700 bg-amber-50 ring-1 ring-amber-200 rounded-lg px-3 py-2 mb-3">
                Email is not configured on this server, so this report was generated but not
                sent. Set the AUCTION_SMTP_* environment variables to enable delivery.
              </p>
            )}
            <pre className="text-xs text-ink-700 bg-ink-50 rounded-lg p-4 overflow-x-auto whitespace-pre-wrap font-mono leading-relaxed max-h-[28rem] overflow-y-auto">
              {sellerReport.report}
            </pre>
          </div>
        )}
      </div>

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
            { label: 'Click-Through Rate', value: recMetrics ? percent(recMetrics.clickThroughRate) : '—' },
            { label: 'Conversion Rate', value: recMetrics ? percent(recMetrics.conversionRate) : '—' },
          ].map(m => (
            <div key={m.label} className="bg-ink-50 rounded-xl p-4">
              <p className="text-xs text-ink-500 font-medium mb-1">{m.label}</p>
              <p className="text-xl font-bold text-ink-900">{m.value}</p>
            </div>
          ))}
        </div>

        {recByReason.length > 0 && (
          <div className="mb-5">
            <h3 className="font-semibold text-sm text-ink-900 mb-1">Click-through by arm</h3>
            <p className="text-xs text-ink-400 mb-3 max-w-3xl leading-relaxed">
              Personalised arms against the popularity baseline, compared on the same page.
              This is not a randomised A/B test: nobody is assigned to an arm, and the two
              strips sit at different heights on the landing page, so the higher one keeps a
              position advantage that more data will not remove. Impressions are counted once
              per card per browser session. Events recorded before arm labelling are omitted.
            </p>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left text-xs text-ink-500 border-b border-ink-200">
                    <th className="py-2 pr-4 font-medium">Arm</th>
                    <th className="py-2 pr-4 font-medium text-right">Impressions</th>
                    <th className="py-2 pr-4 font-medium text-right">Clicks</th>
                    <th className="py-2 pr-4 font-medium text-right">CTR</th>
                    <th className="py-2 pr-4 font-medium text-right">Bids after click</th>
                    <th className="py-2 font-medium text-right">Conversion</th>
                  </tr>
                </thead>
                <tbody>
                  {recByReason.map(r => (
                    <tr key={r.reasonCode} className="border-b border-ink-100 last:border-0">
                      <td className="py-2 pr-4 text-ink-800">
                        {ARM_LABELS[r.reasonCode] ?? r.reasonCode}
                        {r.reasonCode === 'TRENDING_CONTROL' && (
                          <span className="ml-2 text-[11px] text-ink-400">not personalised</span>
                        )}
                      </td>
                      <td className="py-2 pr-4 text-right tabular-nums text-ink-600">{r.impressions}</td>
                      <td className="py-2 pr-4 text-right tabular-nums text-ink-600">{r.clicks}</td>
                      <td className="py-2 pr-4 text-right tabular-nums font-semibold text-ink-900">{percent(r.clickThroughRate)}</td>
                      <td className="py-2 pr-4 text-right tabular-nums text-ink-600">{r.conversions}</td>
                      <td className="py-2 text-right tabular-nums text-ink-600">{percent(r.conversionRate)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}

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
          <div>
            <label className="block text-xs text-ink-500 mb-1">Trending window, days (1–365)</label>
            <input
              type="number" min="1" max="365" step="1"
              value={recForm.trendingWindowDays}
              onChange={e => setRecForm(f => ({ ...f, trendingWindowDays: e.target.value }))}
              className="border border-ink-200 rounded-lg px-3 py-2 text-sm w-44"
            />
          </div>
        </div>

        <div className="mt-5 pt-5 border-t border-ink-100">
          <h3 className="font-semibold text-sm text-ink-900 mb-1">Interaction weights</h3>
          <p className="text-xs text-ink-400 mb-3 max-w-3xl leading-relaxed">
            How much each kind of signal counts when matching buyers with similar taste. A bid
            commits money so it is weighted highest by default, a watchlist entry less, a page
            view least. Raising a weight changes which buyers are treated as similar, and so
            changes the "Recommended for You" strip on the next page load.
          </p>
          <div className="flex flex-wrap items-end gap-3">
            {[
              { key: 'weightBid', label: 'Bid' },
              { key: 'weightWatchlist', label: 'Watchlist' },
              { key: 'weightBrowse', label: 'Browse' },
            ].map(w => (
              <div key={w.key}>
                <label className="block text-xs text-ink-500 mb-1">{w.label} weight (0–100)</label>
                <input
                  type="number" min="0" max="100" step="0.5"
                  value={recForm[w.key]}
                  onChange={e => setRecForm(f => ({ ...f, [w.key]: e.target.value }))}
                  className="border border-ink-200 rounded-lg px-3 py-2 text-sm w-36"
                />
              </div>
            ))}
          </div>
        </div>

        <div className="mt-5 pt-5 border-t border-ink-100">
          <h3 className="font-semibold text-sm text-ink-900 mb-1">Recency</h3>
          <p className="text-xs text-ink-400 mb-3 max-w-3xl leading-relaxed">
            Each interaction is faded by exp(−days / τ) before it counts towards taste, so
            an old bid stops speaking as loudly as a recent one. A τ of 30 leaves a
            month-old signal worth about 37% of a fresh one; setting τ to 0 turns the fade
            off and weights every interaction equally, however old. The lookback window is
            how far back the content-based stage collects your bids, watchlist and browsing
            at all — set it shorter than the age of the data and that stage goes empty.
          </p>
          <div className="flex flex-wrap items-end gap-3">
            <div>
              <label className="block text-xs text-ink-500 mb-1">Recency τ, days (0 = off)</label>
              <input
                type="number" min="0" max="3650" step="1"
                value={recForm.recencyTauDays}
                onChange={e => setRecForm(f => ({ ...f, recencyTauDays: e.target.value }))}
                className="border border-ink-200 rounded-lg px-3 py-2 text-sm w-44"
              />
            </div>
            <div>
              <label className="block text-xs text-ink-500 mb-1">Content lookback, days (1–3650)</label>
              <input
                type="number" min="1" max="3650" step="1"
                value={recForm.contentWindowDays}
                onChange={e => setRecForm(f => ({ ...f, contentWindowDays: e.target.value }))}
                className="border border-ink-200 rounded-lg px-3 py-2 text-sm w-48"
              />
            </div>
          </div>
        </div>

        <div className="mt-5 pt-5 border-t border-ink-100">
          <h3 className="font-semibold text-sm text-ink-900 mb-1">Re-ranking weights</h3>
          <p className="text-xs text-ink-400 mb-3 max-w-3xl leading-relaxed">
            The four stages produce candidates; a single pass then scores every candidate as
            a weighted mean of these five signals, each normalised across the candidate set.
            Raising a weight promotes the listings that signal favours. Setting one to 0
            removes it from the blend entirely — drop popularity to 0, save, and reload the
            landing page to watch the popular listings fall down the strip. Setting all five
            to 0 leaves nothing to rank by, and the strip falls back to the original stage
            order rather than to an arbitrary one.
          </p>
          <div className="flex flex-wrap items-end gap-3">
            {[
              { key: 'weightCf', label: 'Peer bids' },
              { key: 'weightUbcf', label: 'Similar taste' },
              { key: 'weightContent', label: 'Content match' },
              { key: 'weightPopularity', label: 'Popularity' },
              { key: 'weightRecency', label: 'Ending soon' },
            ].map(w => (
              <div key={w.key}>
                <label className="block text-xs text-ink-500 mb-1">{w.label} (0–100)</label>
                <input
                  type="number" min="0" max="100" step="0.1"
                  value={recForm[w.key]}
                  onChange={e => setRecForm(f => ({ ...f, [w.key]: e.target.value }))}
                  className="border border-ink-200 rounded-lg px-3 py-2 text-sm w-36"
                />
              </div>
            ))}
          </div>
        </div>

        <div className="mt-5 pt-5 border-t border-ink-100">
          <h3 className="font-semibold text-sm text-ink-900 mb-1">Category diversity</h3>
          <p className="text-xs text-ink-400 mb-3 max-w-3xl leading-relaxed">
            No single category may take more than ⌈items shown ÷ divisor⌉ slots, so a buyer
            who looked at one Electronics listing does not get a page of nothing else.
            Listings held back by the cap are added again once the capped pass runs out, so
            the strip never comes back shorter. A divisor of 1 raises the cap to the whole
            page and turns the limit off.
          </p>
          <div className="flex flex-wrap items-end gap-3">
            <div>
              <label className="block text-xs text-ink-500 mb-1">Category divisor (1 = off)</label>
              <input
                type="number" min="1" max="24" step="1"
                value={recForm.diversityDivisor}
                onChange={e => setRecForm(f => ({ ...f, diversityDivisor: e.target.value }))}
                className="border border-ink-200 rounded-lg px-3 py-2 text-sm w-44"
              />
            </div>
            <p className="text-xs text-ink-500 pb-2">
              Current cap: {Math.max(1, Math.ceil(Number(recForm.itemsShown || 8)
                / Math.max(1, Number(recForm.diversityDivisor || 1))))} per category
            </p>
          </div>
        </div>

        <div className="mt-5 pt-5 border-t border-ink-100">
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
    </div>
  );
}
