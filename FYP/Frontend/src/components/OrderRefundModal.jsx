import { useState } from 'react';
import { AlertTriangle } from 'lucide-react';
import { requestOrderRefund } from '../api/orders';
import Modal from './Modal';
import { apiErrorMessage } from '../utils/apiError';

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
      setError(apiErrorMessage(err, 'Could not submit refund request.'));
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal
      title="Request refund"
      subtitle={order.auctionTitle}
      icon={AlertTriangle}
      onClose={onClose}
      size="md"
      dismissOnBackdrop={false}
    >
      <div className="p-6">
        <div className="alert-warning text-xs mb-5 leading-relaxed">
          <span>
            Your request goes to the seller, who will approve or decline it. If declined and you still
            have an issue, you can escalate via Contact Admin. Do not confirm receipt if you have not received the item.
          </span>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="field-label">Reason</label>
            <select
              value={reasonType}
              onChange={e => setReasonType(e.target.value)}
              className="select-field"
            >
              {REASONS.map(r => <option key={r} value={r}>{r}</option>)}
            </select>
          </div>

          <div>
            <label className="field-label">Details</label>
            <textarea
              value={details}
              onChange={e => setDetails(e.target.value.slice(0, 500))}
              placeholder="Describe the problem so the seller can review your request…"
              rows={4}
              required
              className="textarea-field"
            />
          </div>

          {error && <p className="text-xs text-red-600 font-medium">{error}</p>}
          <div className="flex gap-3 pt-1">
            <button type="button" onClick={onClose} className="btn-secondary flex-1">
              Cancel
            </button>
            <button
              type="submit"
              disabled={loading}
              className="btn flex-1 bg-accent-500 text-white hover:bg-accent-600 shadow-sm"
            >
              {loading ? 'Submitting…' : 'Submit request'}
            </button>
          </div>
        </form>
      </div>
    </Modal>
  );
}
