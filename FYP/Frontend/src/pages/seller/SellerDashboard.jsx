import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { Plus, Edit2, XCircle, RotateCcw, Eye, Star, BarChart3, Mail } from 'lucide-react';
import {
  getSellerAuctions, cancelAuction, relistAuction, rateBuyer,
  getSellerAnalytics, emailSellerAnalytics,
} from '../../api/seller';
import { formatCurrency } from '../../utils/helpers';
import CountdownTimer from '../../components/CountdownTimer';
import StarRating from '../../components/StarRating';

// Backend SellerAuctionRow fields: auctionId, title, startingPrice, maxPrice,
//   currentBid, bidCount, startDate (Instant), endDate (Instant), statusName (String)
// statusName values e.g. "ACTIVE", "ENDED", "CANCELLED"

const STATUS_STYLE = {
  ACTIVE: 'bg-green-100 text-green-600',
  PENDING: 'bg-yellow-100 text-yellow-600',
  FINISHED: 'bg-blue-100 text-blue-600',
  CANCELLED: 'bg-gray-100 text-gray-500',
};

export default function SellerDashboard() {
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

  useEffect(() => {
    getSellerAuctions()
      .then(r => setAuctions(r.data.auctions ?? r.data ?? []))
      .catch(() => {});
    getSellerAnalytics().then(r => setAnalytics(r.data)).catch(() => {});
  }, []);

  const handleEmailAnalytics = async () => {
    setEmailing(true);
    setAnalyticsMsg('');
    try {
      const r = await emailSellerAnalytics();
      setAnalyticsMsg(r.data.message || 'Analytics report emailed.');
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
    try {
      await cancelAuction(id);
      setAuctions(prev => prev.map(a => a.auctionId === id ? { ...a, statusName: 'CANCELLED' } : a));
    } catch {}
  };

  const handleRelist = async (id) => {
    try {
      await relistAuction(id);
      alert('Auction relisted! Please set new dates.');
    } catch {}
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
        <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50">
          <div className="bg-white rounded-2xl shadow-xl p-6 w-80">
            <h3 className="font-bold text-gray-900 mb-1">Rate the Buyer</h3>
            <p className="text-sm text-gray-500 mb-4">"{ratingAuction.title}"</p>
            <div className="flex justify-center mb-4">
              <StarRating value={ratingScore} onChange={setRatingScore} size={32} />
            </div>
            <textarea
              value={ratingComment}
              onChange={e => setRatingComment(e.target.value.slice(0, 300))}
              placeholder="Add a comment about this buyer (optional)…"
              rows={3}
              className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-100 resize-none mb-1"
            />
            <p className="text-xs text-gray-400 text-right mb-3">{ratingComment.length} / 300</p>
            <div className="flex gap-3">
              <button
                onClick={handleRateBuyer}
                disabled={ratingLoading || !ratingScore}
                className="flex-1 bg-blue-500 text-white py-2 rounded-lg text-sm font-medium hover:bg-blue-600 disabled:opacity-50"
              >
                {ratingLoading ? 'Submitting…' : 'Submit'}
              </button>
              <button
                onClick={() => { setRatingAuction(null); setRatingScore(0); setRatingComment(''); }}
                className="flex-1 border border-gray-200 text-gray-600 py-2 rounded-lg text-sm hover:bg-gray-50"
              >
                Cancel
              </button>
            </div>
          </div>
        </div>
      )}
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold text-gray-900">Seller Dashboard</h1>
        <Link to="/seller/create" className="flex items-center gap-2 bg-blue-500 text-white px-4 py-2 rounded-lg text-sm font-medium hover:bg-blue-600 transition-colors">
          <Plus size={16} /> New Auction
        </Link>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-3 gap-4 mb-6">
        {[
          { label: 'Active', value: active.length, color: 'text-green-500' },
          { label: 'Ended', value: ended.length, color: 'text-blue-500' },
          { label: 'Cancelled', value: cancelled.length, color: 'text-gray-400' },
        ].map(s => (
          <div key={s.label} className="card p-4 text-center">
            <span className={`text-3xl font-bold ${s.color}`}>{s.value}</span>
            <p className="text-sm text-gray-400 mt-1">{s.label}</p>
          </div>
        ))}
      </div>

      {/* Analytics */}
      {analytics && (
        <div className="card p-5 mb-6">
          <div className="flex items-center justify-between mb-4">
            <h2 className="font-bold text-gray-900 flex items-center gap-2">
              <BarChart3 size={18} className="text-blue-500" /> Performance Analytics
            </h2>
            <button
              onClick={handleEmailAnalytics}
              disabled={emailing}
              className="flex items-center gap-2 border border-gray-200 px-3 py-1.5 rounded-lg text-sm hover:bg-gray-50 disabled:opacity-50"
            >
              <Mail size={14} /> {emailing ? 'Sending…' : 'Email me this report'}
            </button>
          </div>
          {analyticsMsg && <div className="text-sm text-blue-600 mb-3">{analyticsMsg}</div>}
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
              <div key={m.label} className="bg-gray-50 rounded-lg p-3 text-center">
                <span className="block text-lg font-bold text-gray-900">{m.value}</span>
                <span className="text-xs text-gray-400">{m.label}</span>
              </div>
            ))}
          </div>
          {analytics.topListings?.length > 0 && (
            <div>
              <p className="text-xs font-semibold text-gray-500 mb-2">Top listings by bids</p>
              <div className="space-y-1">
                {analytics.topListings.map((t, i) => (
                  <div key={i} className="flex items-center justify-between text-sm border-b border-gray-50 pb-1">
                    <span className="text-gray-700 truncate">{t.title}</span>
                    <span className="text-gray-400 shrink-0 ml-2">{t.bidCount} bids · {formatCurrency(t.topBid)}</span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      )}

      {/* Tabs */}
      <div className="flex gap-2 mb-4">
        {[['ACTIVE', 'Active'], ['ENDED', 'Ended'], ['CANCELLED', 'Cancelled']].map(([key, label]) => (
          <button
            key={key}
            onClick={() => setTab(key)}
            className={`px-4 py-2 rounded-full text-sm font-medium capitalize transition-colors ${tab === key ? 'bg-blue-500 text-white' : 'bg-gray-100 text-gray-600 hover:bg-gray-200'}`}
          >
            {label}
          </button>
        ))}
      </div>

      <div className="space-y-3">
        {current.map(auction => (
          <div key={auction.auctionId} className="card p-4 flex items-center gap-4">
            <div className="flex-1">
              <h3 className="font-bold text-gray-900 text-sm">{auction.title}</h3>
              <div className="flex gap-4 mt-1 text-xs text-gray-500">
                <span>{auction.bidCount} bids</span>
                <span>{formatCurrency(auction.currentBid ?? auction.startingPrice ?? 0)}</span>
              </div>
              {tab === 'ACTIVE' && auction.endDate && (
                <div className="mt-1"><CountdownTimer endTime={auction.endDate} /></div>
              )}
            </div>
            <span className={`px-2 py-0.5 rounded text-xs font-medium ${STATUS_STYLE[auction.statusName?.toUpperCase()] || 'bg-gray-100 text-gray-500'}`}>
              {auction.statusName}
            </span>
            <div className="flex gap-2">
              <Link to={`/auction/${auction.auctionId}`} className="p-2 text-gray-400 hover:text-blue-500 transition-colors" title="View">
                <Eye size={16} />
              </Link>
              {tab === 'ACTIVE' && (
                <>
                  <Link to={`/seller/auction/${auction.auctionId}/edit`} className="p-2 text-gray-400 hover:text-blue-500 transition-colors" title="Edit">
                    <Edit2 size={16} />
                  </Link>
                  <button onClick={() => handleCancel(auction.auctionId)} className="p-2 text-gray-400 hover:text-red-500 transition-colors" title="Cancel">
                    <XCircle size={16} />
                  </button>
                </>
              )}
              {tab !== 'ACTIVE' && (
                <>
                  <button onClick={() => handleRelist(auction.auctionId)} className="p-2 text-gray-400 hover:text-green-500 transition-colors" title="Relist">
                    <RotateCcw size={16} />
                  </button>
                  {tab === 'ENDED' && !ratedIds.has(auction.auctionId) && (
                    <button
                      onClick={() => { setRatingAuction(auction); setRatingScore(0); }}
                      className="p-2 text-gray-400 hover:text-yellow-500 transition-colors"
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
          <div className="text-center py-12 text-gray-400">No {tab.toLowerCase()} auctions.</div>
        )}
      </div>
    </div>
  );
}
