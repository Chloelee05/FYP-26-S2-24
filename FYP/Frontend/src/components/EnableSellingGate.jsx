// Rendered by ProtectedRoute in place of a seller page when the signed-in account has not
// switched selling on yet. It is not a route of its own, so the URL the user asked for
// stays in the address bar and they land on it as soon as the capability is granted.
import { useState } from 'react';
import { Store, Check, AlertCircle } from 'lucide-react';
import { enableSelling } from '../api/user';
import { useAuth } from '../context/AuthContext';
import { apiErrorMessage } from '../utils/apiError';

const POINTS = [
  'List items with ascending, Dutch or sealed-bid auctions',
  'Track bids, orders and shipping from a seller dashboard',
  'Answer buyer questions and rate the buyers you sell to',
];

/**
 * Shown in place when a signed-in account opens a seller page before selling has
 * been switched on. There is only one account type, so this is a single confirming
 * click that continues straight to the page the user asked for — not a sign-up.
 */
export default function EnableSellingGate() {
  const { refreshUser } = useAuth();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  // Turn on canSell, then refresh the session. That second call is what matters: once
  // useAuth reports the capability, ProtectedRoute re-renders and this gate is replaced
  // by the page the user was heading for. Loading is deliberately left on in the success
  // path, since the component is about to be unmounted anyway.
  const handleEnable = async () => {
    setError(''); setLoading(true);
    try {
      await enableSelling();
      await refreshUser();
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not turn on selling. Please try again.'));
      setLoading(false);
    }
  };

  return (
    <div className="max-w-2xl mx-auto px-4 py-10">
      <div className="card p-8">
        <div className="flex items-start gap-4">
          <span className="grid place-items-center w-12 h-12 rounded-2xl bg-primary-50 text-primary-600 shrink-0">
            <Store size={22} />
          </span>
          <div className="min-w-0">
            <h1 className="page-title text-2xl">Start selling</h1>
            <p className="text-sm text-ink-500 mt-1 leading-relaxed">
              Selling runs on the account you already have. Turn it on and you'll go
              straight through — your bids, watchlist and order history stay exactly
              as they are.
            </p>

            <ul className="mt-5 space-y-2.5">
              {POINTS.map(point => (
                <li key={point} className="flex items-start gap-2.5 text-sm text-ink-600">
                  <Check size={16} className="text-emerald-600 mt-0.5 shrink-0" />
                  {point}
                </li>
              ))}
            </ul>

            {error && (
              <div className="alert-error mt-5">
                <AlertCircle size={16} className="mt-0.5 shrink-0" />
                <span>{error}</span>
              </div>
            )}

            <button onClick={handleEnable} disabled={loading} className="btn-primary btn-lg mt-6">
              {loading ? 'Turning on selling…' : 'Start selling & continue'}
            </button>
            <p className="field-hint">Nothing to pay up front, and no second account to create.</p>
          </div>
        </div>
      </div>
    </div>
  );
}
