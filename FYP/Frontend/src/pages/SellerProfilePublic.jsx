import { useState, useEffect } from 'react';
import { useParams, Link } from 'react-router-dom';
import { Flag } from 'lucide-react';
import { getSellerProfile, reportUser } from '../api/auction';
import StarRating from '../components/StarRating';
import { useAuth } from '../context/AuthContext';

// Backend response fields: id, username, email (masked), memberSince, profileImageUrl,
//   activeListings (count), avgRating, reviewCount, totalReviews, reviews[]
// Route: /seller/:sellerId — must navigate with numeric seller ID

export default function SellerProfilePublic() {
  const { username: sellerId } = useParams();
  const { user } = useAuth();
  const [seller, setSeller] = useState(null);
  const [reportReason, setReportReason] = useState('');
  const [showReport, setShowReport] = useState(false);
  const [message, setMessage] = useState('');

  useEffect(() => {
    getSellerProfile(sellerId).then(r => setSeller(r.data)).catch(() => {});
  }, [sellerId]);

  const handleReport = async (e) => {
    e.preventDefault();
    try {
      await reportUser({ reportedId: seller.id, reason: reportReason });
      setMessage('Report submitted. Our team will review it.');
      setShowReport(false);
    } catch {
      setMessage('Failed to submit report. Please try again.');
    }
  };

  if (!seller) {
    return <div className="max-w-5xl mx-auto px-4 py-16 text-center text-gray-400">Loading seller profile…</div>;
  }

  return (
    <div className="max-w-5xl mx-auto px-4 py-8">
      <div className="card p-8 mb-6">
        <div className="flex items-start justify-between">
          <div className="flex items-center gap-5">
            <div className="w-20 h-20 rounded-full bg-gradient-to-br from-blue-400 to-purple-500 flex items-center justify-center text-white text-2xl font-bold">
              {seller.username?.[0] ?? 'S'}
            </div>
            <div>
              <h1 className="text-2xl font-bold text-gray-900">{seller.username}</h1>
              <p className="text-gray-400 text-sm">
                Joined {seller.memberSince
                  ? new Date(seller.memberSince).toLocaleDateString('en-US', { month: 'short', year: 'numeric' })
                  : '—'}
              </p>
              <div className="flex items-center gap-2 mt-1">
                <StarRating value={Math.round(seller.avgRating ?? 0)} />
                <span className="text-sm font-bold">{(seller.avgRating ?? 0).toFixed(1)}</span>
                <span className="text-sm text-gray-400">({seller.reviewCount ?? 0} reviews)</span>
              </div>
              <div className="flex flex-wrap gap-x-4 gap-y-1 mt-2 text-sm text-gray-500">
                <span>{seller.completedSales ?? 0} completed sales</span>
                <span>{seller.activeListings ?? 0} active listings</span>
              </div>
            </div>
          </div>
          <button onClick={() => setShowReport(v => !v)} className="flex items-center gap-1 text-sm text-gray-400 hover:text-red-400">
            <Flag size={14} /> Report
          </button>
        </div>

        {showReport && (
          <form onSubmit={handleReport} className="mt-4 flex gap-2">
            <input
              value={reportReason}
              onChange={e => setReportReason(e.target.value)}
              placeholder="Reason for report…"
              required
              className="flex-1 border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none"
            />
            <button type="submit" className="bg-red-500 text-white px-4 py-2 rounded-lg text-sm hover:bg-red-600">Submit</button>
          </form>
        )}
        {message && <div className="mt-3 text-green-600 text-sm">{message}</div>}

        {user?.role === 'BUYER' && (
          <p className="mt-4 pt-4 border-t border-gray-100 text-sm text-gray-500">
            Rate this seller from your <Link to="/profile" className="text-blue-500 hover:underline">Orders</Link> tab after a completed purchase.
          </p>
        )}
      </div>

      {seller.reviews?.length > 0 && (
        <>
          <h2 className="font-bold text-gray-900 mb-4">Reviews</h2>
          <div className="space-y-3 mb-6">
            {seller.reviews.map((r, i) => (
              <div key={i} className="card p-4">
                <div className="flex items-center gap-2 mb-1">
                  <StarRating value={r.score ?? r.rating ?? 0} size={14} />
                  <span className="text-xs text-gray-400">{r.buyerUsername ?? r.reviewer ?? 'Buyer'}</span>
                </div>
                {r.comment && <p className="text-sm text-gray-600">{r.comment}</p>}
              </div>
            ))}
          </div>
        </>
      )}
    </div>
  );
}
