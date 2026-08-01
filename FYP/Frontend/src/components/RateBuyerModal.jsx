import { useState } from 'react';
import { Star } from 'lucide-react';
import { rateBuyer } from '../api/seller';
import StarRating from './StarRating';
import Modal from './Modal';
import { apiErrorMessage } from '../utils/apiError';

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
      setError(apiErrorMessage(err, 'Could not submit rating.'));
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal
      title="Rate buyer"
      subtitle={`“${order.auctionTitle}” — ${order.counterparty}`}
      icon={Star}
      onClose={onClose}
      size="sm"
    >
      <div className="p-6">
        <div className="flex justify-center py-4 mb-4 surface-muted">
          <StarRating value={score} onChange={setScore} size={32} />
        </div>
        <textarea
          value={comment}
          onChange={e => setComment(e.target.value.slice(0, 300))}
          placeholder="Optional comment…"
          rows={3}
          className="textarea-field mb-3"
        />
        {error && <p className="text-xs text-red-600 font-medium mb-3">{error}</p>}
        <button onClick={handleSubmit} disabled={loading || !score} className="btn-primary btn-block">
          {loading ? 'Submitting…' : 'Submit rating'}
        </button>
      </div>
    </Modal>
  );
}
