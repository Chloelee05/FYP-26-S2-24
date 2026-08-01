import { useState, useEffect } from 'react';
import { useParams, Link, useNavigate } from 'react-router-dom';
import { ChevronLeft, CheckCircle2, Star, Package } from 'lucide-react';
import { getAuctionDetail, rateSeller, getSellerProfile, checkSellerRated } from '../api/auction';
import StarRating from '../components/StarRating';
import { publicPath } from '../utils/appBase';
import { apiErrorMessage } from '../utils/apiError';

function StarDisplay({ value, size = 18 }) {
  return <StarRating value={Math.round(value)} size={size} />;
}

export default function RateSeller() {
  const { auctionId } = useParams();
  const navigate = useNavigate();

  const [auction, setAuction] = useState(null);
  const [seller, setSeller]   = useState(null);
  const [score, setScore]     = useState(0);
  const [comment, setComment] = useState('');
  const [loading, setLoading] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const [alreadyRated, setAlreadyRated] = useState(false);
  const [error, setError]     = useState('');

  useEffect(() => {
    getAuctionDetail(auctionId)
      .then(r => {
        setAuction(r.data);
        return getSellerProfile(r.data.sellerId);
      })
      .then(r => setSeller(r.data))
      .catch(() => {});
    checkSellerRated(auctionId)
      .then(r => { if (r.data?.rated) setAlreadyRated(true); })
      .catch(() => {});
  }, [auctionId]);

  const handleSubmit = async () => {
    if (!score) { setError('Please select a rating.'); return; }
    if (comment.trim() && comment.trim().length < 10) {
      setError('Review text must be at least 10 characters (or leave it blank).');
      return;
    }
    setLoading(true);
    setError('');
    try {
      await rateSeller(auctionId, score, comment.trim());
      setSubmitted(true);
    } catch (err) {
      setError(apiErrorMessage(err, 'Failed to submit rating. Please try again.'));
    } finally {
      setLoading(false);
    }
  };

  if (!auction) {
    return (
      <div className="max-w-3xl mx-auto px-4 py-16 text-center text-ink-400">Loading…</div>
    );
  }

  const avgRating   = seller?.avgRating   ?? 0;
  const totalReviews = seller?.totalReviews ?? seller?.reviewCount ?? 0;
  const reviews     = seller?.reviews     ?? [];

  if (alreadyRated && !submitted) {
    return (
      <div className="max-w-3xl mx-auto px-4 py-16 text-center">
        <span className="grid place-items-center w-16 h-16 rounded-2xl bg-emerald-50 text-emerald-500 mx-auto mb-5">
          <CheckCircle2 size={30} />
        </span>
        <h2 className="text-xl font-bold text-ink-900 mb-2">You already rated this seller</h2>
        <p className="text-ink-500 text-sm mb-6">Each buyer can submit one review per completed order.</p>
        <Link to="/purchases" className="btn-primary inline-flex">
          Back to My purchases
        </Link>
      </div>
    );
  }

  if (submitted) {
    return (
      <div className="max-w-3xl mx-auto px-4 py-16 text-center">
        <span className="grid place-items-center w-16 h-16 rounded-2xl bg-amber-50 text-amber-500 mx-auto mb-5">
          <Star size={30} className="fill-amber-400 text-amber-400" />
        </span>
        <h2 className="text-xl font-bold text-ink-900 mb-2">Thank you for your review!</h2>
        <p className="text-ink-500 text-sm mb-6">Your feedback helps other buyers make better decisions.</p>
        <button
          onClick={() => navigate('/bidding-history')}
          className="btn-primary"
        >
          Back to Bidding History
        </button>
      </div>
    );
  }

  return (
    <div className="max-w-3xl mx-auto px-4 py-8">
      <Link to="/bidding-history" className="inline-flex items-center gap-1 text-sm font-medium text-ink-500 hover:text-primary-600 transition-colors mb-6">
        <ChevronLeft size={16} /> Back to Bidding History
      </Link>

      {/* Product header */}
      <div className="card p-5 flex items-center gap-4 mb-6">
        <div className="w-20 h-20 rounded-xl bg-ink-50 grid place-items-center text-ink-400 shrink-0 overflow-hidden">
          {auction.images?.[0]
            ? <img src={publicPath(auction.images[0])} alt={auction.title} className="w-full h-full object-contain p-1.5" />
            : <Package size={26} />}
        </div>
        <div>
          <h1 className="font-display text-lg font-bold text-ink-900">{auction.title}</h1>
          <div className="flex items-center gap-2 mt-1.5 flex-wrap">
            <StarDisplay value={avgRating} size={16} />
            <span className="text-sm text-ink-600 font-medium">{avgRating.toFixed(1)} out of 5</span>
            <span className="text-ink-200">|</span>
            <span className="text-sm text-primary-500">{totalReviews} {totalReviews === 1 ? 'review' : 'reviews'}</span>
          </div>
          <p className="text-xs text-ink-400 mt-1">Seller: {auction.seller}</p>
        </div>
      </div>

      <hr className="border-ink-100 mb-6" />

      {/* Write a Review */}
      <section className="mb-6">
        <h2 className="section-title mb-5">Write a Review</h2>

        <div className="mb-5">
          <label className="field-label">Your Rating</label>
          <StarRating value={score} onChange={setScore} size={28} />
          {!score && <p className="text-xs text-ink-400 mt-1">Click a star to rate</p>}
        </div>

        <div className="mb-5">
          <label className="field-label">Your Review</label>
          <div className="relative">
            <textarea
              value={comment}
              onChange={e => setComment(e.target.value.slice(0, 300))}
              placeholder="Share your experience about this seller..."
              rows={5}
              className="textarea-field"
            />
            <span className="absolute bottom-2.5 right-3 text-xs text-ink-400 select-none">
              {comment.length} / 300
            </span>
          </div>
          <p className="text-xs text-ink-400 mt-1">Minimum 10 characters</p>
        </div>

        {error && <p className="alert-error mb-3">{error}</p>}

        <div className="flex justify-end">
          <button
            onClick={handleSubmit}
            disabled={loading || !score}
            className="btn-primary"
          >
            {loading ? 'Submitting…' : 'Submit Review'}
          </button>
        </div>
      </section>

      <hr className="border-ink-100 mb-6" />

      {/* Customer Reviews */}
      <section>
        <div className="flex items-center justify-between mb-4">
          <h2 className="section-title">Customer Reviews</h2>
          {reviews.length > 0 && (
            <span className="text-sm text-ink-400">Newest First</span>
          )}
        </div>

        {reviews.length === 0 ? (
          <p className="text-sm text-ink-400 py-4">No reviews yet for this seller.</p>
        ) : (
          <div className="divide-y divide-ink-100">
            {reviews.map((rev, i) => (
              <div key={i} className="py-4 flex gap-3">
                <div className="w-10 h-10 rounded-full bg-ink-200 flex items-center justify-center shrink-0 text-ink-600 font-semibold text-sm">
                  {(rev.reviewerMaskedName ?? 'B').charAt(0).toUpperCase()}
                </div>
                <div className="flex-1">
                  <div className="flex items-center justify-between mb-1">
                    <span className="font-semibold text-sm text-ink-800">
                      {rev.reviewerMaskedName ?? 'Buyer'}
                    </span>
                    <span className="text-xs text-ink-400">
                      {rev.reviewDate
                        ? new Date(rev.reviewDate).toLocaleDateString('en-US', {
                            day: 'numeric', month: 'short', year: 'numeric'
                          })
                        : ''}
                    </span>
                  </div>
                  <StarDisplay value={rev.rating ?? 0} size={15} />
                  {rev.comment && (
                    <p className="text-sm text-ink-600 mt-1.5">{rev.comment}</p>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}
