import { useState, useEffect, useMemo } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  Plus, Edit2, XCircle, RotateCcw, Eye, Star, Sparkles, AlertCircle, BarChart3,
  Heart, ImageIcon, Search,
} from 'lucide-react';
import { apiErrorMessage } from '../../utils/apiError';
import {
  getSellerAuctions, cancelAuction, relistAuction, rateBuyer,
  reduceAuctionQuantity, featureOwnAuction,
} from '../../api/seller';
import { getOrders } from '../../api/orders';
import { formatCurrency } from '../../utils/helpers';
import { groupListings, listingStatusLabel } from '../../utils/listings';
import { publicPath } from '../../utils/appBase';
import CountdownTimer from '../../components/CountdownTimer';
import StarRating from '../../components/StarRating';
import Modal from '../../components/Modal';

// Backend SellerAuctionRow fields: auctionId, title, startingPrice, maxPrice,
//   currentBid, bidCount, startDate (Instant), endDate (Instant), statusName (String),
//   quantity, thumbnailUrl, watchCount
// statusName mirrors the AuctionStatus enum: "ACTIVE", "FINISHED", "CANCELLED", "PENDING"

const STATUS_STYLE = {
  ACTIVE: 'badge-success',
  PENDING: 'badge-warning',
  FINISHED: 'badge-info',
  CANCELLED: 'badge-neutral',
};

const TABS = [['ACTIVE', 'Active'], ['FINISHED', 'Finished'], ['CANCELLED', 'Cancelled']];

const SORTS = [
  { key: 'newest',    label: 'Newest listed' },
  { key: 'oldest',    label: 'Oldest listed' },
  { key: 'priceHigh', label: 'Price: high to low' },
  { key: 'priceLow',  label: 'Price: low to high' },
  { key: 'likes',     label: 'Most liked' },
  { key: 'ending',    label: 'Ending soonest' },
];

const priceOf = (a) => Number(a.currentBid ?? a.startingPrice ?? 0);
const listedAt = (a) => (a.startDate ? new Date(a.startDate).getTime() : 0);
const endsAt   = (a) => (a.endDate ? new Date(a.endDate).getTime() : Infinity);

function formatDate(iso) {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('en-SG', { day: 'numeric', month: 'short', year: 'numeric' });
}

/**
 * The seller's own listings and the per-listing actions. Kept separate from the
 * seller dashboard, which is the numbers-only overview.
 */
export default function MyListings() {
  const navigate = useNavigate();
  const [auctions, setAuctions] = useState([]);
  const [tab, setTab] = useState('ACTIVE');
  const [query, setQuery] = useState('');
  const [sort, setSort] = useState('newest');
  const [ratingAuction, setRatingAuction] = useState(null); // auction being rated
  const [ratingScore, setRatingScore] = useState(0);
  const [ratingComment, setRatingComment] = useState('');
  const [ratingLoading, setRatingLoading] = useState(false);
  const [ratedIds, setRatedIds] = useState(new Set()); // auctionIds already rated this session
  const [featuredIds, setFeaturedIds] = useState(new Set()); // featured this session
  const [actionError, setActionError] = useState('');

  useEffect(() => {
    getSellerAuctions()
      .then(r => setAuctions(r.data.auctions ?? r.data ?? []))
      .catch(() => {});
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
  }, []);

  const { active, finished, cancelled } = groupListings(auctions);

  const counts = { ACTIVE: active.length, FINISHED: finished.length, CANCELLED: cancelled.length };

  const visible = useMemo(() => {
    const base = tab === 'ACTIVE' ? active : tab === 'FINISHED' ? finished : cancelled;
    const q = query.trim().toLowerCase();
    const filtered = q ? base.filter(a => (a.title ?? '').toLowerCase().includes(q)) : base;

    const sorted = [...filtered];
    switch (sort) {
      case 'oldest':    sorted.sort((a, b) => listedAt(a) - listedAt(b)); break;
      case 'priceHigh': sorted.sort((a, b) => priceOf(b) - priceOf(a)); break;
      case 'priceLow':  sorted.sort((a, b) => priceOf(a) - priceOf(b)); break;
      case 'likes':     sorted.sort((a, b) => (b.watchCount ?? 0) - (a.watchCount ?? 0)); break;
      case 'ending':    sorted.sort((a, b) => endsAt(a) - endsAt(b)); break;
      default:          sorted.sort((a, b) => listedAt(b) - listedAt(a));
    }
    return sorted;
  }, [auctions, tab, query, sort]); // eslint-disable-line react-hooks/exhaustive-deps

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
      alert(apiErrorMessage(err, 'Could not remove unit.'));
    }
  };

  const handleRelist = async (id) => {
    try {
      await relistAuction(id);
      // Take the seller straight to the edit form so they can set new dates.
      navigate(`/seller/auction/${id}/edit`);
    } catch (err) {
      alert(apiErrorMessage(err, 'Could not relist this auction.'));
    }
  };

  const handleFeature = async (id) => {
    if (!window.confirm('Feature this listing on the homepage for 7 days for a $9.99 fee?')) return;
    try {
      const r = await featureOwnAuction(id, 7);
      setFeaturedIds(prev => new Set([...prev, id]));
      alert(r.data.message || 'Listing featured!');
    } catch (err) {
      alert(apiErrorMessage(err, 'Could not feature this listing.'));
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
      alert(apiErrorMessage(err, 'Failed to submit rating.'));
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
          <h1 className="page-title">My listings</h1>
          <p className="page-subtitle">Everything you have listed, and the actions for each one.</p>
        </div>
        <div className="flex gap-2">
          <Link to="/seller/dashboard" className="btn-secondary">
            <BarChart3 size={15} /> Dashboard
          </Link>
          <Link to="/seller/create" className="btn-primary">
            <Plus size={16} /> New auction
          </Link>
        </div>
      </div>

      {actionError && (
        <div className="alert-error mb-5">
          <AlertCircle size={16} className="mt-0.5 shrink-0" />
          <span>{actionError}</span>
        </div>
      )}

      {/* Filter and sort */}
      <div className="card card-pad mb-5">
        <h2 className="section-title text-base mb-3.5">Filter and sort</h2>
        <div className="flex flex-wrap gap-3">
          <div className="relative flex-1 min-w-[15rem]">
            <Search size={16} className="absolute left-3.5 top-1/2 -translate-y-1/2 text-ink-400 pointer-events-none" />
            <input
              value={query}
              onChange={e => setQuery(e.target.value)}
              placeholder="Search your listings…"
              aria-label="Search your listings"
              className="input-field pl-10"
            />
          </div>
          <label className="shrink-0">
            <span className="sr-only">Sort listings</span>
            <select value={sort} onChange={e => setSort(e.target.value)} className="select-field w-auto">
              {SORTS.map(o => <option key={o.key} value={o.key}>{o.label}</option>)}
            </select>
          </label>
        </div>

        <div className="flex gap-2 mt-3.5 flex-wrap">
          {TABS.map(([key, label]) => (
            <button
              key={key}
              onClick={() => setTab(key)}
              aria-current={tab === key ? 'page' : undefined}
              className={`tab-pill ${tab === key ? 'tab-pill-active' : ''}`}
            >
              {counts[key]} {label}
            </button>
          ))}
        </div>
      </div>

      <div className="card overflow-hidden">
        {visible.length === 0 ? (
          <div className="p-14 text-center">
            <p className="font-semibold text-ink-800">
              {query.trim() ? 'Nothing matches that search' : `No ${tab.toLowerCase()} auctions`}
            </p>
            <p className="text-sm text-ink-500 mt-1">
              {query.trim() ? 'Try a different title.' : 'Listings with this status will appear here.'}
            </p>
            {tab === 'ACTIVE' && !query.trim() && (
              <Link to="/seller/create" className="btn-primary mt-6"><Plus size={16} /> Create your first auction</Link>
            )}
          </div>
        ) : (
          <div className="overflow-x-auto p-5">
            <table className="table-clean">
              <thead>
                <tr>
                  <th className="min-w-[16rem]">Listing</th>
                  <th className="whitespace-nowrap">Listed on</th>
                  <th className="text-right whitespace-nowrap">Price</th>
                  <th className="text-right whitespace-nowrap">Likes</th>
                  <th className="text-right">Action</th>
                </tr>
              </thead>
              <tbody>
                {visible.map(auction => (
                  <tr key={auction.auctionId}>
                    <td>
                      <div className="flex items-center gap-3.5">
                        <Link
                          to={`/auction/${auction.auctionId}`}
                          className="shrink-0 w-16 h-16 rounded-xl overflow-hidden bg-ink-50 grid place-items-center"
                        >
                          {auction.thumbnailUrl ? (
                            <img
                              src={publicPath(auction.thumbnailUrl)}
                              alt=""
                              loading="lazy"
                              className="w-full h-full object-contain p-1"
                            />
                          ) : (
                            <ImageIcon size={18} className="text-ink-300" />
                          )}
                        </Link>
                        <div className="min-w-0">
                          <div className="flex items-center gap-2">
                            <Link
                              to={`/auction/${auction.auctionId}`}
                              className="font-semibold text-ink-900 line-clamp-1 hover:text-primary-600 transition-colors"
                            >
                              {auction.title}
                            </Link>
                            <span className={`${STATUS_STYLE[listingStatusLabel(auction)?.toUpperCase()] || 'badge-neutral'} shrink-0`}>
                              {listingStatusLabel(auction)}
                            </span>
                          </div>
                          <div className="flex flex-wrap gap-x-3 gap-y-0.5 mt-1 text-xs text-ink-400">
                            <span>{auction.bidCount} bids</span>
                            {(auction.quantity ?? 1) > 1 && <span>Qty: {auction.quantity}</span>}
                            {tab === 'ACTIVE' && auction.endDate && (
                              <CountdownTimer endTime={auction.endDate} size={12} className="text-xs" />
                            )}
                          </div>
                        </div>
                      </div>
                    </td>
                    <td className="text-ink-500 whitespace-nowrap">{formatDate(auction.startDate)}</td>
                    <td className="text-right font-semibold tabular-nums whitespace-nowrap">
                      {formatCurrency(priceOf(auction))}
                    </td>
                    <td className="text-right">
                      <span className="inline-flex items-center gap-1.5 text-ink-500 tabular-nums">
                        <Heart size={14} className="text-ink-400" /> {auction.watchCount ?? 0}
                      </span>
                    </td>
                    <td>
                      <div className="flex gap-1 items-center justify-end">
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
                            {tab === 'FINISHED' && !ratedIds.has(auction.auctionId) && (
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
