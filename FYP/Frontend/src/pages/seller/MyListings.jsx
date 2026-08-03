import { useState, useEffect, useCallback } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  Plus, Edit2, XCircle, RotateCcw, Eye, Star, Sparkles, AlertCircle, BarChart3,
  Heart, ImageIcon, Search, ChevronLeft, ChevronRight, MinusCircle,
} from 'lucide-react';
import { apiErrorMessage } from '../../utils/apiError';
import {
  getSellerAuctions, cancelAuction, relistAuction, rateBuyer,
  removeAuctionUnit, featureOwnAuction,
} from '../../api/seller';
import { getOrders } from '../../api/orders';
import { formatCurrency } from '../../utils/helpers';
import { listingStatusLabel, listingPrice } from '../../utils/listings';
import { publicPath } from '../../utils/appBase';
import useDebouncedValue from '../../hooks/useDebouncedValue';
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
  UNSOLD: 'badge-warning',
  CANCELLED: 'badge-neutral',
};

// Tab keys are SellerAuctionDAO.ListingBucket values: the server filters and counts by
// these, so a tab is one query rather than a slice of whatever page is loaded.
// "Unsold" and "Cancelled" are separate tabs because they are separate things — an auction
// nobody bid on versus one the seller withdrew, which the audit found sharing a label.
const TABS = [
  ['ACTIVE', 'Active'],
  ['FINISHED', 'Finished'],
  ['UNSOLD', 'Unsold'],
  ['CANCELLED', 'Cancelled'],
];

const SORTS = [
  { key: 'newest',    label: 'Newest listed' },
  { key: 'oldest',    label: 'Oldest listed' },
  { key: 'priceHigh', label: 'Price: high to low' },
  { key: 'priceLow',  label: 'Price: low to high' },
  { key: 'likes',     label: 'Most liked' },
  { key: 'ending',    label: 'Ending soonest' },
];

const PAGE_SIZE = 10;

const EMPTY_VIEW = { key: null, auctions: [], counts: {}, total: 0, totalPages: 1 };

// Common reasons, offered as one click each. The requirement's own example is "cancel entire
// auction (due to lack of bids)", so that phrasing is the first option and lands verbatim in
// auction.cancel_reason — which is the only place the database can evidence it.
const CANCEL_REASONS = [
  'No bids received',
  'Item is no longer available',
  'Item was damaged',
  'Listed by mistake',
  'Sold elsewhere',
];

const REASON_MAX = 300;

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
  // One page of listings, plus the key of the request it answers. Loading is derived from
  // that key rather than kept as its own flag, which keeps every state update in an async
  // callback or an event handler and none in an effect body.
  const [view, setView] = useState(EMPTY_VIEW);
  const [page, setPage] = useState(1);
  const [reloadToken, setReloadToken] = useState(0);
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
  // { auction, lastUnit } — the listing about to be ended, and whether it is ending because
  // its final item is being removed rather than by an outright cancellation.
  const [ending, setEnding] = useState(null);
  const [endReason, setEndReason] = useState('');
  const [endLoading, setEndLoading] = useState(false);

  // Typing in the search box must not fire a request per keystroke now that searching is a
  // server round trip rather than a filter over an array already in memory.
  const debouncedQuery = useDebouncedValue(query.trim(), 300);

  // Identifies the request the table should be showing. reloadToken is part of it so an
  // action that changes a listing can ask for the same page again.
  const viewKey = [tab, debouncedQuery, sort, page, reloadToken].join('\u0000');
  const loading = view.key !== viewKey;
  const { auctions, counts, total, totalPages } = view;

  const reload = () => setReloadToken(t => t + 1);

  useEffect(() => {
    // Aborting matters here: a slow page 1 must not land on top of the page 2 the seller has
    // since clicked, and the same goes for a search that has moved on.
    const controller = new AbortController();
    getSellerAuctions(
      { bucket: tab, q: debouncedQuery || undefined, sort, page, size: PAGE_SIZE },
      { signal: controller.signal },
    )
      .then(r => {
        const d = r.data ?? {};
        setView({
          key: viewKey,
          auctions: d.auctions ?? [],
          counts: d.counts ?? {},
          total: d.total ?? 0,
          totalPages: d.totalPages ?? 1,
        });
        // The server clamps a page past the end, so follow it rather than leaving the pager
        // pointing somewhere the results are not.
        if (d.page && d.page !== page) setPage(d.page);
      })
      .catch(err => {
        if (controller.signal.aborted) return;
        setView({ ...EMPTY_VIEW, key: viewKey });
        setActionError(apiErrorMessage(err, 'Could not load your listings.'));
      });
    return () => controller.abort();
  }, [viewKey, tab, debouncedQuery, sort, page]);

  useEffect(() => {
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

  const tabCount = (key) => counts[key] ?? 0;

  const openEnd = (auction, lastUnit) => {
    setActionError('');
    setEnding({ auction, lastUnit });
    setEndReason(lastUnit ? '' : CANCEL_REASONS[0]);
  };

  // Memoised because Modal keys its focus-trap effect on onClose. With a fresh function on
  // every render the trap re-ran on every keystroke and pulled focus back to the first control
  // in the dialog, so a seller typing their own reason got one character in and no more.
  const closeEnd = useCallback(() => { setEnding(null); setEndReason(''); }, []);

  const closeRating = useCallback(() => {
    setRatingAuction(null);
    setRatingScore(0);
    setRatingComment('');
  }, []);

  const handleEndListing = async () => {
    if (!endReason.trim()) return;
    setEndLoading(true);
    setActionError('');
    try {
      if (ending.lastUnit) {
        await removeAuctionUnit(ending.auction.auctionId, endReason.trim());
      } else {
        await cancelAuction(ending.auction.auctionId, endReason.trim());
      }
      closeEnd();
      reload();
    } catch (err) {
      setActionError(apiErrorMessage(err, 'Could not end that listing.'));
      closeEnd();
    } finally {
      setEndLoading(false);
    }
  };

  const handleRemoveUnit = async (auction) => {
    // Removing the last item ends the listing, so it goes through the same confirmation as a
    // cancellation and collects the same reason. Anything above one unit is reversible enough
    // to be a single confirm.
    if ((auction.quantity ?? 1) <= 1) { openEnd(auction, true); return; }
    const remaining = (auction.quantity ?? 1) - 1;
    if (!window.confirm(`Remove one item from this listing? ${remaining} will remain on sale.`)) return;
    setActionError('');
    try {
      await removeAuctionUnit(auction.auctionId);
      reload();
    } catch (err) {
      setActionError(apiErrorMessage(err, 'Could not remove that item.'));
    }
  };

  const handleRelist = async (id) => {
    setActionError('');
    try {
      await relistAuction(id);
      // Take the seller straight to the edit form so they can set new dates.
      navigate(`/seller/auction/${id}/edit`);
    } catch (err) {
      setActionError(apiErrorMessage(err, 'Could not relist this auction.'));
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
      closeRating();
    } catch (err) {
      alert(apiErrorMessage(err, 'Failed to submit rating.'));
    } finally {
      setRatingLoading(false);
    }
  };

  const firstShown = total === 0 ? 0 : (page - 1) * PAGE_SIZE + 1;
  const lastShown = Math.min(page * PAGE_SIZE, total);

  return (
    <div className="max-w-5xl mx-auto px-4 py-8">
      {/* End listing (cancel, or remove the final item) */}
      {ending && (
        <Modal
          title={ending.lastUnit ? 'Remove the last item' : 'Cancel this auction'}
          subtitle={`“${ending.auction.title}”`}
          icon={ending.lastUnit ? MinusCircle : XCircle}
          size="sm"
          onClose={closeEnd}
        >
          <div className="p-6">
            <div className="alert-warning mb-4">
              <AlertCircle size={16} className="mt-0.5 shrink-0" />
              <span>
                {ending.lastUnit
                  ? 'This is the only item left on this listing. Removing it leaves nothing to sell, so the auction will be cancelled.'
                  : 'The auction will close immediately and stop accepting bids.'}
                {(ending.auction.bidCount ?? 0) > 0
                  ? ` ${ending.auction.bidCount} bid(s) have been placed — every bidder will be notified and told why.`
                  : ' There are no bids, so nobody needs to be notified.'}
                {' '}You can relist it later from the Cancelled tab.
              </span>
            </div>

            <label className="field-label" htmlFor="end-reason">Reason *</label>
            <div className="flex flex-wrap gap-1.5 mb-2">
              {CANCEL_REASONS.map(r => (
                <button
                  key={r}
                  type="button"
                  onClick={() => setEndReason(r)}
                  className={`px-2.5 py-1 rounded-full text-xs font-semibold border transition-colors ${
                    endReason === r
                      ? 'bg-primary-600 text-white border-primary-600'
                      : 'bg-white text-ink-600 border-ink-200 hover:border-primary-300 hover:text-primary-600'
                  }`}
                >
                  {r}
                </button>
              ))}
            </div>
            <textarea
              id="end-reason"
              value={endReason}
              onChange={e => setEndReason(e.target.value.slice(0, REASON_MAX))}
              placeholder="Why are you ending this listing? Bidders will see this."
              rows={3}
              className="textarea-field"
            />
            <p className="text-xs text-ink-400 text-right mt-1.5 mb-4">{endReason.length} / {REASON_MAX}</p>

            <div className="flex gap-3">
              <button onClick={closeEnd} className="btn-secondary flex-1">Keep listing</button>
              <button
                onClick={handleEndListing}
                disabled={endLoading || !endReason.trim()}
                className="btn-primary flex-1"
              >
                {endLoading
                  ? 'Working…'
                  : ending.lastUnit ? 'Remove and end listing' : 'Cancel auction'}
              </button>
            </div>
          </div>
        </Modal>
      )}

      {/* Rate Buyer Modal */}
      {ratingAuction && (
        <Modal
          title="Rate the Buyer"
          subtitle={`“${ratingAuction.title}”`}
          icon={Star}
          size="sm"
          onClose={closeRating}
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
                onClick={closeRating}
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
              onChange={e => { setQuery(e.target.value); setPage(1); }}
              placeholder="Search all your listings…"
              aria-label="Search your listings"
              className="input-field pl-10"
            />
          </div>
          <label className="shrink-0">
            <span className="sr-only">Sort listings</span>
            {/* Changing the tab, the search or the sort changes what "page 1" means, so the
                page resets here rather than in an effect that reacts to it. */}
            <select
              value={sort}
              onChange={e => { setSort(e.target.value); setPage(1); }}
              className="select-field w-auto"
            >
              {SORTS.map(o => <option key={o.key} value={o.key}>{o.label}</option>)}
            </select>
          </label>
        </div>

        <div className="flex gap-2 mt-3.5 flex-wrap">
          {TABS.map(([key, label]) => (
            <button
              key={key}
              onClick={() => { setTab(key); setPage(1); }}
              aria-current={tab === key ? 'page' : undefined}
              className={`tab-pill ${tab === key ? 'tab-pill-active' : ''}`}
            >
              {tabCount(key)} {label}
            </button>
          ))}
        </div>
      </div>

      <div className="card overflow-hidden">
        {loading && auctions.length === 0 ? (
          <div className="p-14 text-center text-sm text-ink-500">Loading your listings…</div>
        ) : auctions.length === 0 ? (
          <div className="p-14 text-center">
            <p className="font-semibold text-ink-800">
              {debouncedQuery ? 'Nothing matches that search' : `No ${tab.toLowerCase()} auctions`}
            </p>
            <p className="text-sm text-ink-500 mt-1">
              {debouncedQuery
                ? 'Try a different title — this searches every one of your listings, not just this page.'
                : 'Listings with this status will appear here.'}
            </p>
            {tab === 'ACTIVE' && !debouncedQuery && (
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
                {auctions.map(auction => (
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
                            <span>Qty: {auction.quantity ?? 1}</span>
                            {tab === 'ACTIVE' && auction.endDate && (
                              <CountdownTimer endTime={auction.endDate} size={12} className="text-xs" />
                            )}
                          </div>
                        </div>
                      </div>
                    </td>
                    <td className="text-ink-500 whitespace-nowrap">{formatDate(auction.startDate)}</td>
                    <td className="text-right font-semibold tabular-nums whitespace-nowrap">
                      {formatCurrency(listingPrice(auction))}
                      {!(auction.bidCount > 0) && (
                        <span className="block text-xs font-normal text-ink-400">starting price</span>
                      )}
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
                            <button
                              type="button"
                              onClick={() => handleRemoveUnit(auction)}
                              className="p-2 rounded-lg text-ink-400 hover:text-accent-600 hover:bg-accent-50 transition-colors"
                              title={(auction.quantity ?? 1) > 1
                                ? `Remove one item (${auction.quantity} on sale)`
                                : 'Remove the last item — this ends the listing'}
                            >
                              <MinusCircle size={16} />
                            </button>
                            <button
                              type="button"
                              onClick={() => openEnd(auction, false)}
                              className="p-2 rounded-lg text-ink-400 hover:text-red-500 hover:bg-red-50 transition-colors"
                              title="Cancel this auction"
                            >
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

        {/* Pagination. The seller's listings are paged by the server, so without this control
            everything past the first page was unreachable from this page entirely. */}
        {total > 0 && (
          <div className="flex flex-wrap items-center justify-between gap-3 px-5 py-4 border-t border-ink-100">
            <p className="text-xs text-ink-500 tabular-nums">
              Showing {firstShown}–{lastShown} of {total}
            </p>
            {totalPages > 1 && (
              <nav aria-label="Listing pages" className="flex items-center gap-1.5">
                <button
                  type="button"
                  onClick={() => setPage(p => Math.max(1, p - 1))}
                  disabled={page <= 1 || loading}
                  className="p-2 rounded-lg text-ink-500 enabled:hover:bg-ink-50 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                  aria-label="Previous page"
                >
                  <ChevronLeft size={16} />
                </button>
                {Array.from({ length: totalPages }, (_, i) => i + 1).map(n => (
                  <button
                    key={n}
                    type="button"
                    onClick={() => setPage(n)}
                    aria-current={n === page ? 'page' : undefined}
                    className={`min-w-[2rem] px-2 py-1 rounded-lg text-xs font-semibold tabular-nums transition-colors ${
                      n === page
                        ? 'bg-primary-600 text-white'
                        : 'text-ink-600 hover:bg-ink-50'
                    }`}
                  >
                    {n}
                  </button>
                ))}
                <button
                  type="button"
                  onClick={() => setPage(p => Math.min(totalPages, p + 1))}
                  disabled={page >= totalPages || loading}
                  className="p-2 rounded-lg text-ink-500 enabled:hover:bg-ink-50 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                  aria-label="Next page"
                >
                  <ChevronRight size={16} />
                </button>
              </nav>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
