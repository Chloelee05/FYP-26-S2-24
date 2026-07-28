import { useState, useEffect } from 'react';
import { Trash2, Star } from 'lucide-react';
import { getAdminReviews, adminDeleteReview } from '../../api/admin';
import { decodeHtmlEntities } from '../../utils/helpers';

// Backend row fields: id, auctionId, auctionTitle, reviewerName, revieweeName,
//   rating, comment, createdAt

export default function AdminReviews() {
  const [reviews, setReviews] = useState([]);
  const [loading, setLoading] = useState(true);
  const [msg, setMsg] = useState('');

  useEffect(() => {
    getAdminReviews()
      .then(r => setReviews(Array.isArray(r.data) ? r.data : []))
      .catch(() => setMsg('Could not load reviews.'))
      .finally(() => setLoading(false));
  }, []);

  const handleDelete = async (id) => {
    if (!window.confirm('Remove this review? This cannot be undone.')) return;
    setMsg('');
    try {
      await adminDeleteReview(id);
      setReviews(prev => prev.filter(r => r.id !== id));
      setMsg('Review removed.');
    } catch (err) {
      setMsg(err.response?.data?.error || 'Could not remove the review.');
    }
  };

  return (
    <div className="p-8">
      <h1 className="page-title">Review Moderation</h1>
      <p className="page-subtitle mb-6">Remove inappropriate reviews so the review system stays trustworthy</p>

      {msg && <div className="text-sm text-primary-600 mb-4">{msg}</div>}

      {loading ? (
        <div className="text-center py-12 text-ink-400">Loading reviews…</div>
      ) : reviews.length === 0 ? (
        <div className="text-center py-12 text-ink-400">No reviews.</div>
      ) : (
        <div className="card overflow-hidden">
          <table className="w-full text-sm">
            <thead className="text-xs text-ink-500 uppercase tracking-wider bg-ink-50 border-b border-ink-200">
              <tr>
                <th className="px-4 py-3 text-left font-bold whitespace-nowrap">Reviewer</th>
                <th className="px-4 py-3 text-left font-bold whitespace-nowrap">About</th>
                <th className="px-4 py-3 text-left font-bold whitespace-nowrap">Rating</th>
                <th className="px-4 py-3 text-left font-bold whitespace-nowrap">Comment</th>
                <th className="px-4 py-3 text-left font-bold whitespace-nowrap">Date</th>
                <th className="px-4 py-3 text-left font-bold whitespace-nowrap">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-ink-100">
              {reviews.map(r => (
                <tr key={r.id} className="hover:bg-ink-50 align-top">
                  <td className="px-4 py-3 font-medium text-ink-900">{r.reviewerName}</td>
                  <td className="px-4 py-3 text-ink-600">
                    {r.revieweeName}
                    {r.auctionTitle && <p className="text-xs text-ink-400">on {r.auctionTitle}</p>}
                  </td>
                  <td className="px-4 py-3">
                    <span className="flex items-center gap-1 text-ink-700">
                      <Star size={14} className="text-yellow-400 fill-yellow-400" /> {r.rating}/5
                    </span>
                  </td>
                  <td className="px-4 py-3 text-ink-600 max-w-xs">
                    {r.comment ? decodeHtmlEntities(r.comment) : <span className="text-ink-300">—</span>}
                  </td>
                  <td className="px-4 py-3 text-ink-400 whitespace-nowrap">
                    {r.createdAt ? new Date(r.createdAt).toLocaleDateString() : ''}
                  </td>
                  <td className="px-4 py-3">
                    <button
                      onClick={() => handleDelete(r.id)}
                      className="text-ink-400 hover:text-red-500 transition-colors p-1"
                      title="Remove review"
                    >
                      <Trash2 size={14} />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
