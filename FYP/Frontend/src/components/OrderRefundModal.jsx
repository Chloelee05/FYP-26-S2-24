import { useState } from 'react';
import { X, AlertTriangle } from 'lucide-react';
import { requestOrderRefund } from '../api/orders';

const REASONS = [
  'Item not received',
  'Item damaged or defective',
  'Wrong item received',
  'Not as described',
  'Other issue',
];

export default function OrderRefundModal({ order, onClose, onSubmitted }) {
  const [reasonType, setReasonType] = useState(REASONS[0]);
  const [details, setDetails] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  if (!order) return null;

  const handleSubmit = async (e) => {
    e.preventDefault();
    const reason = `${reasonType}: ${details.trim()}`.trim();
    if (details.trim().length < 10) {
      setError('Please describe the issue in at least 10 characters.');
      return;
    }
    setLoading(true);
    setError('');
    try {
      await requestOrderRefund(order.id, reason);
      onSubmitted?.();
      onClose();
    } catch (err) {
      setError(err.response?.data?.error || 'Could not submit refund request.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4" onClick={onClose}>
      <div className="bg-white rounded-2xl shadow-xl max-w-md w-full p-6" onClick={e => e.stopPropagation()}>
        <div className="flex items-start justify-between mb-4">
          <div>
            <h2 className="font-bold text-gray-900 flex items-center gap-2">
              <AlertTriangle size={18} className="text-orange-500" /> Request refund
            </h2>
            <p className="text-sm text-gray-500">{order.auctionTitle}</p>
          </div>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600"><X size={18} /></button>
        </div>

        <p className="text-xs text-gray-500 mb-4 bg-orange-50 border border-orange-100 rounded-lg p-3">
          Your request goes to the seller, who will approve or decline it. If declined and you still
          have an issue, you can escalate via Contact Admin. Do not confirm receipt if you have not received the item.
        </p>

        <form onSubmit={handleSubmit}>
          <label className="text-xs font-medium text-gray-600 block mb-1">Reason</label>
          <select
            value={reasonType}
            onChange={e => setReasonType(e.target.value)}
            className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm mb-3"
          >
            {REASONS.map(r => <option key={r} value={r}>{r}</option>)}
          </select>

          <label className="text-xs font-medium text-gray-600 block mb-1">Details</label>
          <textarea
            value={details}
            onChange={e => setDetails(e.target.value.slice(0, 500))}
            placeholder="Describe the problem so the seller can review your request…"
            rows={4}
            required
            className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm resize-none mb-2"
          />

          {error && <p className="text-xs text-red-500 mb-2">{error}</p>}
          <div className="flex gap-2 mt-2">
            <button type="button" onClick={onClose} className="flex-1 border border-gray-200 py-2 rounded-lg text-sm hover:bg-gray-50">
              Cancel
            </button>
            <button
              type="submit"
              disabled={loading}
              className="flex-1 bg-orange-500 text-white py-2 rounded-lg text-sm font-medium hover:bg-orange-600 disabled:opacity-50"
            >
              {loading ? 'Submitting…' : 'Submit request'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
