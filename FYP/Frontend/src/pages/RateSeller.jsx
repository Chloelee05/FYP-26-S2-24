import { useState, useEffect } from 'react';
import { useParams, Link, useNavigate } from 'react-router-dom';
import { ChevronLeft } from 'lucide-react';
import { getAuctionDetail, rateSeller, getSellerProfile, checkSellerRated } from '../api/auction';
import StarRating from '../components/StarRating';
import { publicPath } from '../utils/appBase';

function StarDisplay({ value, size = 18 }) {
  return (
    <div className="flex">
      {[1, 2, 3, 4, 5].map(i => (
        <span key={i} style={{ fontSize: size, lineHeight: 1 }}
              className={i <= Math.round(value) ? 'text-yellow-400' : 'text-gray-200'}>
          ★
        </span>
      ))}
    </div>
  );
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
      setError(err.response?.data?.error || 'Failed to submit rating. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  if (!auction) {
    return (
      <div className="max-w-3xl mx-auto px-4 py-16 text-center text-gray-400">Loading…</div>
    );
  }

  const avgRating   = seller?.avgRating   ?? 0;
  const totalReviews = seller?.totalReviews ?? seller?.reviewCount ?? 0;
  const reviews     = seller?.reviews     ?? [];

  if (alreadyRated && !submitted) {
    return (
      <div className="max-w-3xl mx-auto px-4 py-16 text-center">
        <p className="text-5xl mb-4">✓</p>
        <h2 className="text-xl font-bold text-gray-900 mb-2">You already rated this seller</h2>
        <p className="text-gray-500 text-sm mb-6">Each buyer can submit one review per completed order.</p>
        <Link to="/profile" className="bg-blue-500 text-white px-6 py-2.5 rounded-lg text-sm font-medium hover:bg-blue-600 inline-block">
          Back to Orders
        </Link>
      </div>
    );
  }

  if (submitted) {
    return (
      <div className="max-w-3xl mx-auto px-4 py-16 text-center">
        <p className="text-5xl mb-4">⭐</p>
        <h2 className="text-xl font-bold text-gray-900 mb-2">Thank you for your review!</h2>
        <p className="text-gray-500 text-sm mb-6">Your feedback helps other buyers make better decisions.</p>
        <button
          onClick={() => navigate('/bidding-history')}
          className="bg-blue-500 text-white px-6 py-2.5 rounded-lg text-sm font-medium hover:bg-blue-600"
        >
          Back to Bidding History
        </button>
      </div>
    );
  }

  return (
    <div className="max-w-3xl mx-auto px-4 py-8">
      <Link to="/bidding-history" className="flex items-center gap-1 text-sm text-blue-500 hover:underline mb-6">
        <ChevronLeft size={16} /> Back to Bidding History
      </Link>

      {/* Product header */}
      <div className="card p-5 flex items-center gap-4 mb-6">
        <div className="w-20 h-20 rounded-xl bg-gray-100 flex items-center justify-center text-3xl shrink-0">
          {auction.images?.[0]
            ? <img src={publicPath(auction.images[0])} alt={auction.title} className="w-full h-full object-cover rounded-xl" />
            : '🏷'}
        </div>
        <div>
          <h1 className="text-lg font-bold text-gray-900">{auction.title}</h1>
          <div className="flex items-center gap-2 mt-1.5 flex-wrap">
            <StarDisplay value={avgRating} size={16} />
            <span className="text-sm text-gray-600 font-medium">{avgRating.toFixed(1)} out of 5</span>
            <span className="text-gray-200">|</span>
            <span className="text-sm text-blue-500">{totalReviews} {totalReviews === 1 ? 'review' : 'reviews'}</span>
          </div>
          <p className="text-xs text-gray-400 mt-1">Seller: {auction.seller}</p>
        </div>
      </div>

      <hr className="border-gray-100 mb-6" />

      {/* Write a Review */}
      <section className="mb-6">
        <h2 className="text-base font-bold text-gray-900 mb-5">Write a Review</h2>

        <div className="mb-5">
          <label className="text-sm font-medium text-gray-700 block mb-2">Your Rating</label>
          <StarRating value={score} onChange={setScore} size={28} />
          {!score && <p className="text-xs text-gray-400 mt-1">Click a star to rate</p>}
        </div>

        <div className="mb-5">
          <label className="text-sm font-medium text-gray-700 block mb-2">Your Review</label>
          <div className="relative">
            <textarea
              value={comment}
              onChange={e => setComment(e.target.value.slice(0, 300))}
              placeholder="Share your experience about this seller..."
              rows={5}
              className="w-full border border-gray-200 rounded-lg px-3 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-100 resize-none"
            />
            <span className="absolute bottom-2.5 right-3 text-xs text-gray-400 select-none">
              {comment.length} / 300
            </span>
          </div>
          <p className="text-xs text-gray-400 mt-1">Minimum 10 characters</p>
        </div>

        {error && <p className="text-red-500 text-sm mb-3">{error}</p>}

        <div className="flex justify-end">
          <button
            onClick={handleSubmit}
            disabled={loading || !score}
            className="bg-blue-600 text-white px-6 py-2.5 rounded-lg text-sm font-semibold hover:bg-blue-700 disabled:opacity-50 transition-colors"
          >
            {loading ? 'Submitting…' : 'Submit Review'}
          </button>
        </div>
      </section>

      <hr className="border-gray-100 mb-6" />

      {/* Customer Reviews */}
      <section>
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-base font-bold text-gray-900">Customer Reviews</h2>
          {reviews.length > 0 && (
            <span className="text-sm text-gray-400">Newest First</span>
          )}
        </div>

        {reviews.length === 0 ? (
          <p className="text-sm text-gray-400 py-4">No reviews yet for this seller.</p>
        ) : (
          <div className="divide-y divide-gray-100">
            {reviews.map((rev, i) => (
              <div key={i} className="py-4 flex gap-3">
                <div className="w-10 h-10 rounded-full bg-gray-200 flex items-center justify-center shrink-0 text-gray-600 font-semibold text-sm">
                  {(rev.reviewerMaskedName ?? 'B').charAt(0).toUpperCase()}
                </div>
                <div className="flex-1">
                  <div className="flex items-center justify-between mb-1">
                    <span className="font-semibold text-sm text-gray-800">
                      {rev.reviewerMaskedName ?? 'Buyer'}
                    </span>
                    <span className="text-xs text-gray-400">
                      {rev.reviewDate
                        ? new Date(rev.reviewDate).toLocaleDateString('en-US', {
                            day: 'numeric', month: 'short', year: 'numeric'
                          })
                        : ''}
                    </span>
                  </div>
                  <StarDisplay value={rev.rating ?? 0} size={15} />
                  {rev.comment && (
                    <p className="text-sm text-gray-600 mt-1.5">{rev.comment}</p>
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
