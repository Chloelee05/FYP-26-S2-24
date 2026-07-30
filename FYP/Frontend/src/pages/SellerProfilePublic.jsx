import { useState, useEffect, useMemo } from 'react';
import { useParams, Link } from 'react-router-dom';
import { Flag, Star, Package, Search, CheckCircle2, AlertCircle } from 'lucide-react';
import { getSellerProfile, reportUser } from '../api/auction';
import { decodeHtmlEntities } from '../utils/helpers';
import { publicPath } from '../utils/appBase';
import StarRating from '../components/StarRating';
import AuctionCard from '../components/AuctionCard';
import { useAuth } from '../context/AuthContext';

// Backend response fields: id, username, email (masked), memberSince, profileImageUrl,
//   activeListings (count), completedSales, avgRating, reviewCount, totalReviews,
//   reviews[], listings[] (auctionId, title, category, currentPrice, endDate,
//   thumbnailUrl, watchCount)
// Route: /seller/:sellerId — must navigate with numeric seller ID

export default function SellerProfilePublic() {
  const { username: sellerId } = useParams();
  const { user } = useAuth();
  const [seller, setSeller] = useState(null);
  const [tab, setTab] = useState('listings');
  const [query, setQuery] = useState('');
  const [reportReason, setReportReason] = useState('');
  const [showReport, setShowReport] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');

  useEffect(() => {
    getSellerProfile(sellerId)
      .then(r => setSeller(r.data))
      .catch(() => setError('Could not load this seller profile.'));
  }, [sellerId]);

  const listings = useMemo(() => {
    const all = seller?.listings ?? [];
    const q = query.trim().toLowerCase();
    return q ? all.filter(l => (l.title ?? '').toLowerCase().includes(q)) : all;
  }, [seller, query]);

  const handleReport = async (e) => {
    e.preventDefault();
    try {
      await reportUser({ reportedId: seller.id, reason: reportReason });
      setMessage('Report submitted. Our team will review it.');
      setShowReport(false);
      setReportReason('');
    } catch {
      setError('Failed to submit report. Please try again.');
    }
  };

  if (error && !seller) {
    return (
      <div className="max-w-5xl mx-auto px-4 py-16 text-center">
        <p className="font-semibold text-ink-800">{error}</p>
        <Link to="/search" className="btn-secondary mt-5">Browse auctions</Link>
      </div>
    );
  }

  if (!seller) {
    return (
      <div className="max-w-5xl mx-auto px-4 py-8 space-y-5" aria-busy="true">
        <div className="skeleton h-32 rounded-2xl" />
        <div className="skeleton h-10 w-64" />
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
          {[0, 1, 2, 3].map(i => <div key={i} className="skeleton h-72 rounded-2xl" />)}
        </div>
      </div>
    );
  }

  const avgRating = seller.avgRating ?? 0;
  const reviews = seller.reviews ?? [];
  const joined = seller.memberSince
    ? new Date(seller.memberSince).toLocaleDateString('en-SG', { month: 'short', year: 'numeric' })
    : '—';

  return (
    <div className="max-w-5xl mx-auto px-4 py-8">
      {/* Identity */}
      <div className="card card-pad mb-6">
        <div className="flex flex-wrap items-start justify-between gap-5">
          <div className="flex items-center gap-4 min-w-0">
            {seller.profileImageUrl ? (
              <img
                src={publicPath(seller.profileImageUrl)}
                alt=""
                className="w-20 h-20 rounded-full object-cover border border-ink-200 shrink-0"
              />
            ) : (
              <div className="w-20 h-20 rounded-full bg-gradient-to-br from-primary-400 to-purple-500 flex items-center justify-center text-white text-2xl font-bold shrink-0">
                {seller.username?.[0]?.toUpperCase() ?? 'S'}
              </div>
            )}
            <div className="min-w-0">
              <h1 className="page-title">{seller.username}</h1>
              <div className="flex items-center gap-2 mt-1.5">
                <StarRating value={Math.round(avgRating)} size={16} />
                <span className="text-sm font-bold text-ink-900 tabular-nums">{avgRating.toFixed(1)}</span>
                <span className="text-sm text-ink-400">
                  ({seller.reviewCount ?? 0} {seller.reviewCount === 1 ? 'review' : 'reviews'})
                </span>
              </div>
              <div className="flex flex-wrap gap-x-4 gap-y-1 mt-2 text-sm text-ink-500">
                <span>Joined {joined}</span>
                <span>{seller.activeListings ?? 0} active listings</span>
                <span>{seller.completedSales ?? 0} completed sales</span>
              </div>
            </div>
          </div>

          <button
            onClick={() => { setShowReport(v => !v); setError(''); }}
            className="btn-secondary btn-sm shrink-0"
          >
            <Flag size={13} /> Report
          </button>
        </div>

        {showReport && (
          <form onSubmit={handleReport} className="mt-5 pt-5 divider flex flex-wrap gap-2">
            <input
              value={reportReason}
              onChange={e => setReportReason(e.target.value)}
              placeholder="Why are you reporting this seller?"
              required
              className="input-field flex-1 min-w-[15rem]"
            />
            <button type="submit" className="btn-danger">Submit report</button>
          </form>
        )}

        {message && (
          <div className="alert-success mt-4">
            <CheckCircle2 size={16} className="mt-0.5 shrink-0" />
            <span>{message}</span>
          </div>
        )}
        {error && (
          <div className="alert-error mt-4">
            <AlertCircle size={16} className="mt-0.5 shrink-0" />
            <span>{error}</span>
          </div>
        )}

        {user && user.role !== 'ADMIN' && (
          <p className="mt-5 pt-5 divider text-sm text-ink-500">
            Rate this seller from your <Link to="/profile" className="link-subtle">Orders</Link> tab after a completed purchase.
          </p>
        )}
      </div>

      {/* Listings / Reviews */}
      <div className="flex gap-6 border-b border-ink-200 mb-6">
        {[
          { key: 'listings', label: 'Listings', count: seller.listings?.length ?? 0 },
          { key: 'reviews',  label: 'Reviews',  count: seller.totalReviews ?? reviews.length },
        ].map(t => (
          <button
            key={t.key}
            onClick={() => setTab(t.key)}
            aria-current={tab === t.key ? 'page' : undefined}
            className={`-mb-px border-b-2 px-1 pb-3 text-sm font-semibold transition-colors ${
              tab === t.key
                ? 'border-primary-600 text-primary-600'
                : 'border-transparent text-ink-500 hover:text-ink-800'
            }`}
          >
            {t.label}
            {t.count > 0 && <span className="ml-1.5 text-xs text-ink-400 tabular-nums">{t.count}</span>}
          </button>
        ))}
      </div>

      {tab === 'listings' && (
        <>
          <div className="flex flex-wrap items-center justify-between gap-3 mb-5">
            <h2 className="section-title text-base">Listings</h2>
            <div className="relative w-full sm:w-72">
              <Search size={16} className="absolute left-3.5 top-1/2 -translate-y-1/2 text-ink-400 pointer-events-none" />
              <input
                value={query}
                onChange={e => setQuery(e.target.value)}
                placeholder="Search listings…"
                aria-label="Search this seller's listings"
                className="input-field pl-10"
              />
            </div>
          </div>

          {listings.length === 0 ? (
            <div className="card p-14 text-center">
              <span className="grid place-items-center w-12 h-12 rounded-2xl bg-ink-100 text-ink-400 mx-auto mb-3">
                <Package size={20} />
              </span>
              <p className="font-semibold text-sm text-ink-700">
                {query.trim() ? 'Nothing matches that search' : 'No live listings right now'}
              </p>
              <p className="text-sm text-ink-400 mt-1">
                {query.trim() ? 'Try a different title.' : 'Check back later — this seller has nothing on sale at the moment.'}
              </p>
            </div>
          ) : (
            <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
              {listings.map(l => (
                <AuctionCard
                  key={l.auctionId}
                  auction={{
                    auctionId: l.auctionId,
                    title: l.title,
                    currentPrice: l.currentPrice,
                    endDate: l.endDate,
                    thumbnailUrl: l.thumbnailUrl,
                    category: l.category,
                  }}
                />
              ))}
            </div>
          )}
        </>
      )}

      {tab === 'reviews' && (
        <>
          <h2 className="section-title text-base mb-5">Reviews</h2>
          {reviews.length === 0 ? (
            <div className="card p-14 text-center">
              <span className="grid place-items-center w-12 h-12 rounded-2xl bg-ink-100 text-ink-400 mx-auto mb-3">
                <Star size={20} />
              </span>
              <p className="font-semibold text-sm text-ink-700">No reviews yet</p>
              <p className="text-sm text-ink-400 mt-1">Buyers can rate this seller once an order completes.</p>
            </div>
          ) : (
            <div className="card divide-y divide-ink-100">
              {reviews.map((r, i) => (
                <div key={i} className="p-5 flex gap-3.5">
                  <div className="w-10 h-10 rounded-full bg-ink-200 grid place-items-center shrink-0 text-ink-600 font-semibold text-sm">
                    {(r.reviewerMaskedName ?? 'B').charAt(0).toUpperCase()}
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center justify-between gap-3 mb-1">
                      <span className="font-semibold text-sm text-ink-900 truncate">
                        {r.reviewerMaskedName ?? 'Buyer'}
                      </span>
                      <span className="text-xs text-ink-400 shrink-0">
                        {r.reviewDate ? new Date(r.reviewDate).toLocaleDateString('en-SG', { day: 'numeric', month: 'short', year: 'numeric' }) : ''}
                      </span>
                    </div>
                    <StarRating value={r.rating ?? r.score ?? 0} size={14} />
                    {r.auctionTitle && <p className="text-xs text-ink-400 mt-1">on {r.auctionTitle}</p>}
                    {r.comment && <p className="text-sm text-ink-600 mt-1.5 leading-relaxed">{decodeHtmlEntities(r.comment)}</p>}
                  </div>
                </div>
              ))}
            </div>
          )}
          {seller.totalReviews > reviews.length && (
            <p className="text-xs text-ink-400 mt-3">
              Showing the {reviews.length} most recent of {seller.totalReviews} reviews.
            </p>
          )}
        </>
      )}
    </div>
  );
}
