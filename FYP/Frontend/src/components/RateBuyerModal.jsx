import { useState } from 'react';
import { X } from 'lucide-react';
import { rateBuyer } from '../api/seller';
import StarRating from './StarRating';

export default function RateBuyerModal({ order, onClose, onRated }) {
  const [score, setScore] = useState(0);
  const [comment, setComment] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  if (!order) return null;

  const handleSubmit = async () => {
    if (!score) { setError('Select a star rating.'); return; }
    setLoading(true);
    setError('');
    try {
      await rateBuyer(order.auctionId, score, comment.trim());
      onRated?.();
      onClose();
    } catch (err) {
      setError(err.response?.data?.error || 'Could not submit rating.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4" onClick={onClose}>
      <div className="bg-white rounded-2xl shadow-xl w-full max-w-sm p-6" onClick={e => e.stopPropagation()}>
        <div className="flex justify-between items-center mb-4">
          <h3 className="font-bold text-gray-900">Rate buyer</h3>
          <button onClick={onClose}><X size={18} className="text-gray-400" /></button>
        </div>
        <p className="text-sm text-gray-500 mb-4">"{order.auctionTitle}" — {order.counterparty}</p>
        <div className="flex justify-center mb-4">
          <StarRating value={score} onChange={setScore} size={32} />
        </div>
        <textarea
          value={comment}
          onChange={e => setComment(e.target.value.slice(0, 300))}
          placeholder="Optional comment…"
          rows={3}
          className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm resize-none mb-3"
        />
        {error && <p className="text-xs text-red-500 mb-2">{error}</p>}
        <button
          onClick={handleSubmit}
          disabled={loading || !score}
          className="w-full bg-blue-500 text-white py-2 rounded-lg text-sm font-medium hover:bg-blue-600 disabled:opacity-50"
        >
          {loading ? 'Submitting…' : 'Submit rating'}
        </button>
      </div>
    </div>
  );
}
