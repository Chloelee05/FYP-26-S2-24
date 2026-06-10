import { useState } from 'react';
import { reportListing } from '../api/auction';

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
      setError(err.response?.data?.error || 'Failed to submit report. Please try again.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 px-4">
      <div className="bg-white rounded-2xl shadow-xl w-full max-w-md">
        {/* Header */}
        <div className="flex items-start justify-between p-5 border-b border-gray-100">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-full bg-red-100 flex items-center justify-center shrink-0">
              <span className="text-red-500 text-base">⚑</span>
            </div>
            <div>
              <h2 className="font-bold text-gray-900">Report auction</h2>
              {auctionTitle && (
                <p className="text-xs text-gray-400 mt-0.5 line-clamp-1">"{auctionTitle}"</p>
              )}
            </div>
          </div>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600 transition-colors text-lg leading-none mt-0.5">✕</button>
        </div>

        {done ? (
          <div className="p-6 text-center">
            <div className="w-12 h-12 bg-green-100 rounded-full flex items-center justify-center mx-auto mb-3">
              <span className="text-green-500 text-xl">✓</span>
            </div>
            <p className="font-medium text-gray-900 mb-1">Report submitted</p>
            <p className="text-sm text-gray-500 mb-4">Our moderation team will review it shortly.</p>
            <button onClick={onClose} className="bg-gray-900 text-white text-sm font-medium px-6 py-2 rounded-lg hover:bg-gray-700 transition-colors">
              Close
            </button>
          </div>
        ) : (
          <form onSubmit={handleSubmit} className="p-5 space-y-4">
            {error && <div className="bg-red-50 text-red-600 text-sm px-3 py-2 rounded-lg">{error}</div>}

            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">
                Reason for reporting <span className="text-red-500">*</span>
              </label>
              <select
                value={reason}
                onChange={e => setReason(e.target.value)}
                required
                className="w-full border border-gray-200 rounded-lg px-3 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-200"
              >
                <option value="">Select a reason…</option>
                {REASONS.map(r => <option key={r} value={r}>{r}</option>)}
              </select>
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">
                Additional details <span className="text-red-500">*</span>
              </label>
              <textarea
                value={details}
                onChange={e => setDetails(e.target.value)}
                rows={4}
                placeholder="Please provide more details about your report…"
                className="w-full border border-gray-200 rounded-lg px-3 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-200 resize-none"
              />
              <p className={`text-xs mt-1 ${details.trim().length < 10 ? 'text-gray-400' : 'text-green-500'}`}>
                {details.trim().length} characters (minimum 10)
              </p>
            </div>

            <div className="bg-blue-50 border border-blue-100 rounded-lg px-4 py-3 text-xs text-blue-700">
              <span className="font-semibold">Please note:</span> All reports are reviewed by our moderation team. False or malicious reports may result in account suspension.
            </div>

            <div className="flex gap-3 pt-1">
              <button type="button" onClick={onClose}
                className="flex-1 border border-gray-200 text-gray-700 text-sm font-medium py-2.5 rounded-lg hover:bg-gray-50 transition-colors">
                Cancel
              </button>
              <button type="submit" disabled={!canSubmit}
                className="flex-1 bg-red-500 hover:bg-red-600 text-white text-sm font-medium py-2.5 rounded-lg transition-colors disabled:opacity-50">
                {submitting ? 'Submitting…' : 'Submit Report'}
              </button>
            </div>
          </form>
        )}
      </div>
    </div>
  );
}
