import { useState, useEffect } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Plus, Edit2, XCircle, RotateCcw, Eye, Star, BarChart3, Mail, Sparkles, AlertCircle } from 'lucide-react';
import { apiErrorMessage } from '../../utils/apiError';
import {
  getSellerAuctions, cancelAuction, relistAuction, rateBuyer,
  getSellerAnalytics, emailSellerAnalytics, reduceAuctionQuantity, featureOwnAuction,
} from '../../api/seller';
import { getOrders } from '../../api/orders';
import { getMyReviews, getTransactionHistory } from '../../api/user';
import { formatCurrency, decodeHtmlEntities } from '../../utils/helpers';
import CountdownTimer from '../../components/CountdownTimer';
import StarRating from '../../components/StarRating';
import Modal from '../../components/Modal';

// Backend SellerAuctionRow fields: auctionId, title, startingPrice, maxPrice,
//   currentBid, bidCount, startDate (Instant), endDate (Instant), statusName (String)
// statusName values e.g. "ACTIVE", "ENDED", "CANCELLED"

const STATUS_STYLE = {
  ACTIVE: 'badge-success',
  PENDING: 'badge-warning',
  FINISHED: 'badge-info',
  CANCELLED: 'badge-neutral',
};

export default function SellerDashboard() {
  const navigate = useNavigate();
  const [auctions, setAuctions] = useState([]);
  const [tab, setTab] = useState('ACTIVE');
  const [ratingAuction, setRatingAuction] = useState(null); // auction being rated
  const [ratingScore, setRatingScore] = useState(0);
  const [ratingComment, setRatingComment] = useState('');
  const [ratingLoading, setRatingLoading] = useState(false);
  const [ratedIds, setRatedIds] = useState(new Set()); // auctionIds already rated this session
  const [analytics, setAnalytics] = useState(null);
  const [analyticsMsg, setAnalyticsMsg] = useState('');
  const [emailing, setEmailing] = useState(false);
  const [reviews, setReviews] = useState([]);
  const [sales, setSales] = useState([]);
  const [inlineReport, setInlineReport] = useState('');
  const [featuredIds, setFeaturedIds] = useState(new Set()); // featured this session
  const [actionError, setActionError] = useState('');

  useEffect(() => {
    getSellerAuctions()
      .then(r => setAuctions(r.data.auctions ?? r.data ?? []))
      .catch(() => {});
    getSellerAnalytics().then(r => setAnalytics(r.data)).catch(() => {});
    getOrders()
      .then(r => {
        const rated = new Set(
          (r.data ?? [])
            .filter(o => o.role === 'seller' && o.hasRated)
            .map(o => o.auctionId)
        );
        setRatedIds(rated);
      })
      .catch(() => {});
    getMyReviews()
      .then(r => setReviews(Array.isArray(r.data) ? r.data : []))
      .catch(() => {});
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
      setAnalyticsMsg(err.response?.data?.error || 'Could not send report.');
    } finally {
      setEmailing(false);
    }
  };

  const s = (a) => a.statusName?.toUpperCase();
  const active    = auctions.filter(a => s(a) === 'ACTIVE' || s(a) === 'PENDING');
  const ended     = auctions.filter(a => s(a) === 'FINISHED');
  const cancelled = auctions.filter(a => s(a) === 'CANCELLED');

  const current = tab === 'ACTIVE' ? active : tab === 'ENDED' ? ended : cancelled;

  const handleCancel = async (id) => {
    if (!window.confirm('Cancel this auction?')) return;
    setActionError('');
    try {
      await cancelAuction(id);
      setAuctions(prev => prev.map(a => a.auctionId === id ? { ...a, statusName: 'CANCELLED' } : a));
    } catch (err) {
      setActionError(apiErrorMessage(err, 'Could not cancel that auction.'));
    }
  };

  const handleRemoveUnit = async (id) => {
    if (!window.confirm('Remove one unit from this listing?')) return;
    try {
      await reduceAuctionQuantity(id);
      setAuctions(prev => prev.map(a => a.auctionId === id
        ? { ...a, quantity: Math.max(1, (a.quantity ?? 1) - 1) }
        : a));
    } catch (err) {
      alert(err.response?.data?.error || 'Could not remove unit.');
    }
  };

  const handleRelist = async (id) => {
    try {
      await relistAuction(id);
      // Take the seller straight to the edit form so they can set new dates.
      navigate(`/seller/auction/${id}/edit`);
    } catch (err) {
      alert(err.response?.data?.error || 'Could not relist this auction.');
    }
  };

  const handleFeature = async (id) => {
    if (!window.confirm('Feature this listing on the homepage for 7 days for a $9.99 fee?')) return;
    try {
      const r = await featureOwnAuction(id, 7);
      setFeaturedIds(prev => new Set([...prev, id]));
      alert(r.data.message || 'Listing featured!');
    } catch (err) {
      alert(err.response?.data?.error || 'Could not feature this listing.');
    }
  };

  const handleRateBuyer = async () => {
    if (!ratingScore) { alert('Please select a star rating.'); return; }
    setRatingLoading(true);
    try {
      await rateBuyer(ratingAuction.auctionId, ratingScore, ratingComment.trim());
      setRatedIds(prev => new Set([...prev, ratingAuction.auctionId]));
      setRatingAuction(null);
      setRatingScore(0);
      setRatingComment('');
    } catch (err) {
      alert(err.response?.data?.error || err.response?.data?.message || 'Failed to submit rating.');
    } finally {
      setRatingLoading(false);
    }
  };

  return (
    <div className="max-w-5xl mx-auto px-4 py-8">
      {/* Rate Buyer Modal */}
      {ratingAuction && (
        <Modal
          title="Rate the Buyer"
          subtitle={`“${ratingAuction.title}”`}
          icon={Star}
          size="sm"
          onClose={() => { setRatingAuction(null); setRatingScore(0); setRatingComment(''); }}
        >
          <div className="p-6">
            <div className="flex justify-center py-4 mb-4 surface-muted">
              <StarRating value={ratingScore} onChange={setRatingScore} size={32} />
            </div>
            <textarea
              value={ratingComment}
              onChange={e => setRatingComment(e.target.value.slice(0, 300))}
              placeholder="Add a comment about this buyer (optional)…"
              rows={3}
              className="textarea-field"
            />
            <p className="text-xs text-ink-400 text-right mt-1.5 mb-4">{ratingComment.length} / 300</p>
            <div className="flex gap-3">
              <button
                onClick={() => { setRatingAuction(null); setRatingScore(0); setRatingComment(''); }}
                className="btn-secondary flex-1"
              >
                Cancel
              </button>
              <button
                onClick={handleRateBuyer}
                disabled={ratingLoading || !ratingScore}
                className="btn-primary flex-1"
              >
                {ratingLoading ? 'Submitting…' : 'Submit'}
              </button>
            </div>
          </div>
        </Modal>
      )}

      <div className="flex flex-wrap items-center justify-between gap-3 mb-6">
        <div>
          <h1 className="page-title">Seller Dashboard</h1>
          <p className="page-subtitle">Manage your listings, sales and buyer feedback.</p>
        </div>
        <Link to="/seller/create" className="btn-primary">
          <Plus size={16} /> New Auction
        </Link>
      </div>

      {actionError && (
        <div className="alert-error mb-5">
          <AlertCircle size={16} className="mt-0.5 shrink-0" />
          <span>{actionError}</span>
        </div>
      )}

      {/* Stats */}
      <div className="grid grid-cols-3 gap-4 mb-6">
        {[
          { label: 'Active', value: active.length, color: 'text-emerald-600', bg: 'bg-emerald-50' },
          { label: 'Ended', value: ended.length, color: 'text-primary-600', bg: 'bg-primary-50' },
          { label: 'Cancelled', value: cancelled.length, color: 'text-ink-500', bg: 'bg-ink-100' },
        ].map(stat => (
          <div key={stat.label} className="card card-hover p-5 text-center">
            <span className={`inline-grid place-items-center min-w-[3.5rem] h-14 px-3 rounded-2xl ${stat.bg} ${stat.color} text-3xl font-bold tabular-nums`}>
              {stat.value}
            </span>
            <p className="text-sm font-semibold text-ink-500 mt-2">{stat.label}</p>
          </div>
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

      {/* Reviews from buyers (SCRUM-43) */}
      <div className="card p-6 mb-6">
        <h2 className="section-title text-base mb-4 flex items-center gap-2">
          <Star size={18} className="text-amber-500" /> Reviews from buyers
        </h2>
        {reviews.length === 0 ? (
          <p className="text-sm text-ink-400">No reviews from buyers yet.</p>
        ) : (
          <div className="divide-y divide-ink-100">
            {reviews.slice(0, 8).map((rev, i) => (
              <div key={i} className="py-3.5 first:pt-0 last:pb-0">
                <div className="flex items-center justify-between gap-3 mb-1.5">
                  <span className="text-sm font-semibold text-ink-800">{rev.reviewerMaskedName ?? 'Buyer'}</span>
                  <span className="text-xs text-ink-400">
                    {rev.reviewDate ? new Date(rev.reviewDate).toLocaleDateString() : ''}
                  </span>
                </div>
                <StarRating value={rev.rating ?? 0} size={14} />
                {rev.auctionTitle && <p className="text-xs text-ink-400 mt-1">on {rev.auctionTitle}</p>}
                {rev.comment && <p className="text-sm text-ink-600 mt-1.5 leading-relaxed">{decodeHtmlEntities(rev.comment)}</p>}
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Tabs */}
      <div className="flex gap-2 mb-4 flex-wrap">
        {[['ACTIVE', 'Active'], ['ENDED', 'Ended'], ['CANCELLED', 'Cancelled']].map(([key, label]) => (
          <button
            key={key}
            onClick={() => setTab(key)}
            className={`tab-pill ${tab === key ? 'tab-pill-active' : ''}`}
          >
            {label}
          </button>
        ))}
      </div>

      <div className="space-y-3">
        {current.map(auction => (
          <div key={auction.auctionId} className="card card-hover p-4 flex flex-wrap items-center gap-4">
            <div className="flex-1 min-w-[12rem]">
              <h3 className="font-bold text-ink-900 text-sm line-clamp-1">{auction.title}</h3>
              <div className="flex flex-wrap gap-x-4 gap-y-1 mt-1.5 text-xs text-ink-500">
                <span>{auction.bidCount} bids</span>
                <span className="font-semibold text-ink-800 tabular-nums">{formatCurrency(auction.currentBid ?? auction.startingPrice ?? 0)}</span>
                {(auction.quantity ?? 1) > 1 && <span>Qty: {auction.quantity}</span>}
              </div>
              {tab === 'ACTIVE' && auction.endDate && (
                <div className="mt-1.5"><CountdownTimer endTime={auction.endDate} size={13} className="text-xs" /></div>
              )}
            </div>
            <span className={STATUS_STYLE[auction.statusName?.toUpperCase()] || 'badge-neutral'}>
              {auction.statusName}
            </span>
            <div className="flex gap-1 items-center">
              <Link to={`/auction/${auction.auctionId}`} className="p-2 rounded-lg text-ink-400 hover:text-primary-600 hover:bg-primary-50 transition-colors" title="View">
                <Eye size={16} />
              </Link>
              {tab === 'ACTIVE' && (
                <>
                  <button
                    type="button"
                    onClick={() => handleFeature(auction.auctionId)}
                    disabled={featuredIds.has(auction.auctionId)}
                    title={featuredIds.has(auction.auctionId)
                      ? 'This listing is featured.'
                      : 'Feature this listing on the homepage ($9.99 / 7 days)'}
                    className={`p-2 rounded-lg transition-colors ${
                      featuredIds.has(auction.auctionId)
                        ? 'text-amber-400 cursor-default'
                        : 'text-ink-400 hover:text-amber-500 hover:bg-amber-50'
                    }`}
                  >
                    <Sparkles size={16} />
                  </button>
                  <Link to={`/seller/auction/${auction.auctionId}/edit`} className="p-2 rounded-lg text-ink-400 hover:text-primary-600 hover:bg-primary-50 transition-colors" title="Edit">
                    <Edit2 size={16} />
                  </Link>
                  {(auction.quantity ?? 1) > 1 && (
                    <button onClick={() => handleRemoveUnit(auction.auctionId)} className="px-2 py-1.5 text-xs font-semibold text-accent-600 hover:bg-accent-50 rounded-lg transition-colors" title="Remove one unit">
                      −1 unit
                    </button>
                  )}
                  <button onClick={() => handleCancel(auction.auctionId)} className="p-2 rounded-lg text-ink-400 hover:text-red-500 hover:bg-red-50 transition-colors" title="Cancel">
                    <XCircle size={16} />
                  </button>
                </>
              )}
              {tab !== 'ACTIVE' && (
                <>
                  <button onClick={() => handleRelist(auction.auctionId)} className="p-2 rounded-lg text-ink-400 hover:text-emerald-600 hover:bg-emerald-50 transition-colors" title="Relist">
                    <RotateCcw size={16} />
                  </button>
                  {tab === 'ENDED' && !ratedIds.has(auction.auctionId) && (
                    <button
                      onClick={() => { setRatingAuction(auction); setRatingScore(0); }}
                      className="p-2 rounded-lg text-ink-400 hover:text-amber-500 hover:bg-amber-50 transition-colors"
                      title="Rate buyer"
                    >
                      <Star size={16} />
                    </button>
                  )}
                </>
              )}
            </div>
          </div>
        ))}
        {current.length === 0 && (
          <div className="card p-14 text-center">
            <p className="font-semibold text-ink-800">No {tab.toLowerCase()} auctions</p>
            <p className="text-sm text-ink-500 mt-1">Listings with this status will appear here.</p>
            {tab === 'ACTIVE' && <Link to="/seller/create" className="btn-primary mt-6"><Plus size={16} /> Create your first auction</Link>}
          </div>
        )}
      </div>
    </div>
  );
}
