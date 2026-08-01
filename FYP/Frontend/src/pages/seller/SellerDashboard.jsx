import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { Plus, ListOrdered, Star, BarChart3, Mail } from 'lucide-react';
import { getSellerAuctions, getSellerAnalytics, emailSellerAnalytics } from '../../api/seller';
import { getTransactionHistory } from '../../api/user';
import { formatCurrency } from '../../utils/helpers';
import { groupListings } from '../../utils/listings';
import { apiErrorMessage } from '../../utils/apiError';

// Listing counts are grouped by the same rules as My listings, so the two pages
// never report different totals — see utils/listings.

export default function SellerDashboard() {
  const [auctions, setAuctions] = useState([]);
  const [analytics, setAnalytics] = useState(null);
  const [analyticsMsg, setAnalyticsMsg] = useState('');
  const [emailing, setEmailing] = useState(false);
  const [sales, setSales] = useState([]);
  const [inlineReport, setInlineReport] = useState('');

  useEffect(() => {
    getSellerAuctions()
      .then(r => setAuctions(r.data.auctions ?? r.data ?? []))
      .catch(() => {});
    getSellerAnalytics().then(r => setAnalytics(r.data)).catch(() => {});
    getTransactionHistory('SALE')
      .then(r => setSales(Array.isArray(r.data) ? r.data : []))
      .catch(() => {});
  }, []);

  const handleEmailAnalytics = async () => {
    setEmailing(true);
    setAnalyticsMsg('');
    setInlineReport('');
    try {
      const r = await emailSellerAnalytics();
      setAnalyticsMsg(r.data.message || 'Analytics report emailed.');
      // SMTP not configured — the server returns the report body to show inline instead.
      if (r.data.emailConfigured === false && r.data.report) {
        setInlineReport(r.data.report);
      }
    } catch (err) {
      setAnalyticsMsg(apiErrorMessage(err, 'Could not send report.'));
    } finally {
      setEmailing(false);
    }
  };

  const { active, finished, cancelled } = groupListings(auctions);

  return (
    <div className="max-w-5xl mx-auto px-4 py-8">
      <div className="flex flex-wrap items-center justify-between gap-3 mb-6">
        <div>
          <h1 className="page-title">Seller Dashboard</h1>
          <p className="page-subtitle">How your selling is going. Listings themselves live under My listings.</p>
        </div>
        <div className="flex gap-2">
          <Link to="/seller/listings" className="btn-secondary">
            <ListOrdered size={15} /> My listings
          </Link>
          <Link to="/seller/create" className="btn-primary">
            <Plus size={16} /> New auction
          </Link>
        </div>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-3 gap-4 mb-6">
        {[
          { label: 'Active', value: active.length, color: 'text-emerald-600', bg: 'bg-emerald-50' },
          { label: 'Finished', value: finished.length, color: 'text-primary-600', bg: 'bg-primary-50' },
          { label: 'Cancelled', value: cancelled.length, color: 'text-ink-500', bg: 'bg-ink-100' },
        ].map(stat => (
          <Link key={stat.label} to="/seller/listings" className="card card-hover p-5 text-center">
            <span className={`inline-grid place-items-center min-w-[3.5rem] h-14 px-3 rounded-2xl ${stat.bg} ${stat.color} text-3xl font-bold tabular-nums`}>
              {stat.value}
            </span>
            <p className="text-sm font-semibold text-ink-500 mt-2">{stat.label}</p>
          </Link>
        ))}
      </div>

      {/* Analytics */}
      {analytics && (
        <div className="card p-5 mb-6">
          <div className="flex flex-wrap items-center justify-between gap-3 mb-4">
            <h2 className="section-title text-base flex items-center gap-2">
              <BarChart3 size={18} className="text-primary-600" /> Performance Analytics
            </h2>
            <button onClick={handleEmailAnalytics} disabled={emailing} className="btn-secondary btn-sm">
              <Mail size={14} /> {emailing ? 'Sending…' : 'Email me this report'}
            </button>
          </div>
          {analyticsMsg && <div className="alert-info mb-4">{analyticsMsg}</div>}
          {inlineReport && (
            <div className="mb-5 surface-muted p-4">
              <div className="flex items-center justify-between mb-2">
                <p className="eyebrow">Analytics report</p>
                <button onClick={() => setInlineReport('')} className="text-xs font-semibold text-ink-400 hover:text-ink-700">
                  Dismiss
                </button>
              </div>
              <pre className="text-xs text-ink-700 whitespace-pre-wrap font-mono max-h-72 overflow-y-auto">{inlineReport}</pre>
            </div>
          )}

          {analytics.earningsSummary && (
            <div className="mb-6 p-5 rounded-2xl bg-primary-50 ring-1 ring-inset ring-primary-100">
              <p className="text-sm font-bold text-ink-900 mb-1">Earnings summary (simulated)</p>
              <p className="text-xs text-ink-500 mb-4 leading-relaxed">
                Based on completed orders and platform fees. Updated when buyers complete payment &amp; confirm receipt.
                No in-app wallet or withdrawals in this prototype.
              </p>
              <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
                {[
                  { label: 'Gross sales', value: formatCurrency(analytics.earningsSummary.grossSales) },
                  { label: `Platform fee (${analytics.earningsSummary.commissionRatePct ?? 6}%)`, value: formatCurrency(analytics.earningsSummary.platformFee) },
                  { label: 'Featured fees', value: formatCurrency(analytics.earningsSummary.featuredFees) },
                  { label: 'Net earnings', value: formatCurrency(analytics.earningsSummary.netEarnings), highlight: true },
                ].map(m => (
                  <div key={m.label} className={`rounded-xl p-3 text-center ${m.highlight ? 'bg-white ring-2 ring-primary-300 shadow-sm' : 'bg-white/70'}`}>
                    <span className={`block text-lg font-bold tabular-nums ${m.highlight ? 'text-primary-700' : 'text-ink-900'}`}>{m.value}</span>
                    <span className="text-xs text-ink-500">{m.label}</span>
                  </div>
                ))}
              </div>
              <p className="text-xs text-ink-400 mt-3">
                {analytics.earningsSummary.completedOrders ?? 0} completed order(s)
              </p>
            </div>
          )}

          <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-4">
            {[
              { label: 'Items Sold', value: analytics.soldCount },
              { label: 'Revenue', value: formatCurrency(analytics.totalRevenue) },
              { label: 'Avg Sale', value: formatCurrency(analytics.avgSalePrice) },
              { label: 'Sell-through', value: `${analytics.sellThroughRate}%` },
              { label: 'Total Listings', value: analytics.totalListings },
              { label: 'Active', value: analytics.activeListings },
              { label: 'Bids Received', value: analytics.bidsReceived },
            ].map(m => (
              <div key={m.label} className="surface-muted p-3 text-center">
                <span className="block text-lg font-bold text-ink-900 tabular-nums">{m.value}</span>
                <span className="text-xs text-ink-400">{m.label}</span>
              </div>
            ))}
          </div>
          {analytics.periodStats?.length > 0 && (
            <div className="mb-5">
              <p className="eyebrow mb-2.5">Period breakdown</p>
              <div className="grid grid-cols-2 md:grid-cols-4 gap-2">
                {analytics.periodStats.map(p => (
                  <div key={p.period} className="surface-muted p-2.5 text-center text-xs">
                    <p className="font-bold text-ink-800 capitalize">{p.period}</p>
                    <p className="text-ink-500 mt-0.5">{p.sold} sold · {formatCurrency(p.revenue)}</p>
                    <p className="text-ink-400">{p.bids} bids</p>
                  </div>
                ))}
              </div>
            </div>
          )}
          {analytics.topListings?.length > 0 && (
            <div className="mb-5">
              <p className="eyebrow mb-2.5">Top listings by bids</p>
              <div className="divide-y divide-ink-100">
                {analytics.topListings.map((t, i) => (
                  <div key={i} className="flex items-center justify-between gap-3 text-sm py-2">
                    <span className="text-ink-700 truncate">{t.title}</span>
                    <span className="text-ink-400 shrink-0 tabular-nums">{t.bidCount} bids · {formatCurrency(t.topBid)}</span>
                  </div>
                ))}
              </div>
            </div>
          )}
          {analytics.productRatings?.length > 0 && (
            <div>
              <p className="eyebrow mb-2.5">Product star ratings</p>
              <div className="divide-y divide-ink-100">
                {analytics.productRatings.map((pr, i) => (
                  <div key={i} className="text-sm py-2.5">
                    <div className="flex justify-between gap-3">
                      <span className="text-ink-700 truncate">{pr.title}</span>
                      <span className="text-ink-500 shrink-0 font-semibold">{pr.avgRating}/5 ({pr.reviewCount})</span>
                    </div>
                    <div className="flex flex-wrap gap-2 mt-1 text-xs text-ink-400">
                      {[5, 4, 3, 2, 1].map(star => (
                        <span key={star} className="inline-flex items-center gap-0.5">
                          {star}<Star size={10} className="fill-amber-400 text-amber-400" /> {pr.starPercentages?.[star] ?? 0}%
                        </span>
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      )}

      {/* Sale transactions (SCRUM-69) */}
      <div className="card p-6 mb-6">
        <h2 className="section-title text-base mb-4">Sale transactions</h2>
        {sales.length === 0 ? (
          <p className="text-sm text-ink-400">No sale transactions yet.</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="table-clean">
              <thead>
                <tr>
                  <th>Item</th>
                  <th>Amount</th>
                  <th>Date</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {sales.slice(0, 10).map((t, i) => (
                  <tr key={t.displayId ?? i}>
                    <td className="font-semibold text-ink-800">{t.itemTitle}</td>
                    <td className="tabular-nums">{formatCurrency(t.amount)}</td>
                    <td className="text-ink-500">{t.transactionDate}</td>
                    <td>
                      <span className={
                        t.status === 'Completed' ? 'badge-success' :
                        t.status === 'Cancelled' ? 'badge-danger' :
                        'badge-warning'
                      }>
                        {t.status}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

    </div>
  );
}
