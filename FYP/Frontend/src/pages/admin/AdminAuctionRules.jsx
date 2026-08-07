/*
 * NEW page for the "platform-wide auction rules" admin story at "/admin/auction-rules". ADMIN
 * only, guarded the same way every other admin page is: the <ProtectedRoute roles={['ADMIN']}>
 * wrapper on the parent /admin route in App.jsx.
 *
 * Two platform-wide knobs, modelled on the recommendation settings form already on the
 * Analytics page (AdminAnalytics.jsx): a labelled numeric input per setting, a Save button,
 * and the settings loaded from the server on mount so the form always shows the value
 * actually in effect rather than a hardcoded placeholder.
 *
 *   - Minimum bid increment: how much a manual ascending bid must clear the current floor by.
 *     Enforced in BidDAO#placeBid. Does not apply to Dutch auctions (system-computed clock
 *     price, no buyer-chosen amount) or blind auctions (a sealed bid is compared only against
 *     the starting price, never against a visible current bid).
 *   - Maximum auction duration: the longest a listing's end date may be set from its start,
 *     enforced on both create and edit alongside the existing "end after start" check.
 *
 * Both default to values that reproduce today's exact behaviour (see the seed migration), so
 * this page changing nothing is itself a valid, safe state.
 */
import { useState, useEffect } from 'react';
import { Gauge } from 'lucide-react';
import { getAuctionRules, saveAuctionRules } from '../../api/admin';
import { apiErrorMessage } from '../../utils/apiError';

export default function AdminAuctionRules() {
  const [minBidIncrement, setMinBidIncrement] = useState('');
  const [maxAuctionDurationDays, setMaxAuctionDurationDays] = useState('');
  const [loaded, setLoaded] = useState(false);
  const [saving, setSaving] = useState(false);
  const [msg, setMsg] = useState('');
  const [err, setErr] = useState('');

  useEffect(() => {
    getAuctionRules()
      .then(r => {
        setMinBidIncrement(String(r.data?.minBidIncrement ?? ''));
        setMaxAuctionDurationDays(String(r.data?.maxAuctionDurationDays ?? ''));
      })
      .catch(e => setErr(apiErrorMessage(e, 'Could not load auction rules.')))
      .finally(() => setLoaded(true));
  }, []);

  const handleSave = async () => {
    setSaving(true);
    setMsg('');
    setErr('');
    try {
      const r = await saveAuctionRules({
        min_bid_increment: minBidIncrement,
        max_auction_duration_days: maxAuctionDurationDays,
      });
      setMinBidIncrement(String(r.data?.minBidIncrement ?? minBidIncrement));
      setMaxAuctionDurationDays(String(r.data?.maxAuctionDurationDays ?? maxAuctionDurationDays));
      setMsg('Auction rules saved.');
    } catch (e) {
      setErr(apiErrorMessage(e, 'Could not save auction rules.'));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="p-8 max-w-3xl">
      <h1 className="page-title flex items-center gap-2">
        <Gauge size={22} className="text-primary-600" /> Auction Rules
      </h1>
      <p className="page-subtitle mb-6">
        Platform-wide limits applied to every auction: the smallest step a bid may raise the
        price by, and the longest a listing may run.
      </p>

      {msg && (
        <div className="text-sm text-emerald-700 bg-emerald-50 ring-1 ring-emerald-200 rounded-lg px-3 py-2 mb-4">
          {msg}
        </div>
      )}
      {err && (
        <div className="text-sm text-red-700 bg-red-50 ring-1 ring-red-200 rounded-lg px-3 py-2 mb-4">
          {err}
        </div>
      )}

      <div className="card p-5">
        {!loaded ? (
          <div className="text-center py-8 text-ink-400">Loading current settings…</div>
        ) : (
          <>
            <div className="mb-5">
              <label className="block text-xs text-ink-500 mb-1" htmlFor="min-bid-increment">
                Minimum bid increment ($)
              </label>
              <input
                id="min-bid-increment"
                type="number" min="0.01" max="1000" step="0.01"
                value={minBidIncrement}
                onChange={e => setMinBidIncrement(e.target.value)}
                className="border border-ink-200 rounded-lg px-3 py-2 text-sm w-48"
              />
              <p className="text-xs text-ink-400 mt-1 max-w-lg leading-relaxed">
                A manual ascending bid must clear the current highest bid (or the starting
                price, if no bids yet) by at least this much. Applies to standard ascending
                auctions only — Dutch auctions accept a system-computed clock price with no
                buyer-chosen amount, and blind auctions compare a sealed bid only against the
                starting price, never against a visible current bid.
              </p>
            </div>

            <div className="mb-5 pt-5 border-t border-ink-100">
              <label className="block text-xs text-ink-500 mb-1" htmlFor="max-auction-duration">
                Maximum auction duration (days)
              </label>
              <input
                id="max-auction-duration"
                type="number" min="1" max="36500" step="1"
                value={maxAuctionDurationDays}
                onChange={e => setMaxAuctionDurationDays(e.target.value)}
                className="border border-ink-200 rounded-lg px-3 py-2 text-sm w-48"
              />
              <p className="text-xs text-ink-400 mt-1 max-w-lg leading-relaxed">
                The longest a listing's end date may be set from its start date, checked on
                both create and edit alongside the existing "end date must be after start
                date" rule.
              </p>
            </div>

            <button
              type="button"
              onClick={handleSave}
              disabled={saving}
              className="btn-primary"
            >
              {saving ? 'Saving…' : 'Save settings'}
            </button>
          </>
        )}
      </div>
    </div>
  );
}
