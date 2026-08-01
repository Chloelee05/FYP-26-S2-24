import { useState } from 'react';
import { Flag, CheckCircle2 } from 'lucide-react';
import { reportListing } from '../api/auction';
import Modal from './Modal';
import { apiErrorMessage } from '../utils/apiError';

const REASONS = [
  'Counterfeit / Fake item',
  'Prohibited or illegal item',
  'Misleading description or photos',
  'Suspected fraud or scam',
  'Duplicate listing',
  'Other',
];

export default function ReportModal({ auctionId, auctionTitle, onClose }) {
  const [reason, setReason] = useState('');
  const [details, setDetails] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const [done, setDone] = useState(false);

  const canSubmit = reason && details.trim().length >= 10 && !submitting;

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    setSubmitting(true);
    try {
      await reportListing(auctionId, `${reason}: ${details.trim()}`);
      setDone(true);
    } catch (err) {
      setError(apiErrorMessage(err, 'Failed to submit report. Please try again.'));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      title="Report auction"
      subtitle={auctionTitle ? `“${auctionTitle}”` : undefined}
      icon={Flag}
      onClose={onClose}
      size="md"
      dismissOnBackdrop={false}
    >
      <>
        {done ? (
          <div className="p-8 text-center">
            <span className="grid place-items-center w-14 h-14 rounded-full bg-emerald-50 text-emerald-500 mx-auto mb-4">
              <CheckCircle2 size={26} />
            </span>
            <p className="font-bold text-ink-900 mb-1">Report submitted</p>
            <p className="text-sm text-ink-500 mb-6">Our moderation team will review it shortly.</p>
            <button onClick={onClose} className="btn-dark px-8">Close</button>
          </div>
        ) : (
          <form onSubmit={handleSubmit} className="p-5 space-y-4">
            {error && <div className="alert-error">{error}</div>}

            <div>
              <label className="field-label">
                Reason for reporting <span className="text-red-500">*</span>
              </label>
              <select
                value={reason}
                onChange={e => setReason(e.target.value)}
                required
                className="select-field"
              >
                <option value="">Select a reason…</option>
                {REASONS.map(r => <option key={r} value={r}>{r}</option>)}
              </select>
            </div>

            <div>
              <label className="field-label">
                Additional details <span className="text-red-500">*</span>
              </label>
              <textarea
                value={details}
                onChange={e => setDetails(e.target.value)}
                rows={4}
                placeholder="Please provide more details about your report…"
                className="textarea-field"
              />
              <p className={`text-xs mt-1.5 font-medium ${details.trim().length < 10 ? 'text-ink-400' : 'text-emerald-600'}`}>
                {details.trim().length} characters (minimum 10)
              </p>
            </div>

            <div className="alert-info text-xs">
              <span><span className="font-semibold">Please note:</span> All reports are reviewed by our moderation team. False or malicious reports may result in account suspension.</span>
            </div>

            <div className="flex gap-3 pt-1">
              <button type="button" onClick={onClose} className="btn-secondary flex-1">
                Cancel
              </button>
              <button type="submit" disabled={!canSubmit} className="btn-danger flex-1">
                {submitting ? 'Submitting…' : 'Submit Report'}
              </button>
            </div>
          </form>
        )}
      </>
    </Modal>
  );
}
