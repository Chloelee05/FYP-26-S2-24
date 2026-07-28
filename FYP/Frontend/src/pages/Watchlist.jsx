import { useState, useEffect } from 'react';
import { Trash2, Heart, Package, AlertCircle } from 'lucide-react';
import { Link } from 'react-router-dom';
import { getWatchlist, removeFromWatchlist } from '../api/auction';
import { apiErrorMessage } from '../utils/apiError';
import { formatCurrency } from '../utils/helpers';
import CountdownTimer from '../components/CountdownTimer';

// statusId: 1=ACTIVE, 2=FINISHED, 3=CANCELLED, 4=PENDING
const STATUS_LABEL = { 1: 'Active', 2: 'Ended', 3: 'Cancelled', 4: 'Pending' };
const STATUS_CLASS  = {
  1: 'badge-success',
  2: 'badge-neutral',
  3: 'badge-danger',
  4: 'badge-warning',
};

export default function Watchlist() {
  const [items, setItems] = useState([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    getWatchlist()
      .then(r => setItems(r.data ?? []))
      .catch(err => setError(apiErrorMessage(err, 'Could not load your watchlist.')))
      .finally(() => setLoading(false));
  }, []);

  const handleRemove = async (auctionId) => {
    setError('');
    try {
      await removeFromWatchlist(auctionId);
      setItems(prev => prev.filter(i => i.auctionId !== auctionId));
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not remove that item. Please try again.'));
    }
  };

  return (
    <div className="max-w-4xl mx-auto px-4 py-8">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="page-title">My Watchlist</h1>
          <p className="page-subtitle">
            {items.length === 0 ? 'Auctions you save appear here.' : `${items.length} saved auction${items.length === 1 ? '' : 's'}`}
          </p>
        </div>
      </div>

      {error && (
        <div className="alert-error mb-5">
          <AlertCircle size={16} className="mt-0.5 shrink-0" />
          <span>{error}</span>
        </div>
      )}

      {loading ? (
        <div className="space-y-3">
          {Array.from({ length: 3 }, (_, i) => <div key={i} className="skeleton h-24 rounded-2xl" />)}
        </div>
      ) : items.length === 0 ? (
        <div className="card p-16 text-center">
          <span className="grid place-items-center w-14 h-14 rounded-2xl bg-red-50 text-red-400 mx-auto mb-4">
            <Heart size={26} />
          </span>
          <p className="font-semibold text-ink-800">Your watchlist is empty</p>
          <p className="text-sm text-ink-500 mt-1">Tap the heart on any auction to keep an eye on it.</p>
          <Link to="/search" className="btn-primary mt-6">Browse auctions</Link>
        </div>
      ) : (
        <div className="space-y-3">
          {items.map(item => (
            <div key={item.auctionId} className="card card-hover p-4 flex items-center gap-4">
              <div className="w-16 h-16 rounded-xl bg-ink-100 grid place-items-center text-ink-400 shrink-0">
                <Package size={22} />
              </div>

              <div className="flex-1 min-w-0">
                <Link to={`/auction/${item.auctionId}`} className="font-bold text-ink-900 hover:text-primary-600 text-sm transition-colors line-clamp-1">
                  {item.title}
                </Link>
                <div className="flex flex-wrap items-center gap-x-4 gap-y-1 mt-1.5">
                  <span className="text-xs text-ink-500">
                    Current bid <strong className="text-ink-900 tabular-nums">{formatCurrency(item.currentBid)}</strong>
                  </span>
                  <span className="text-xs text-ink-400">
                    {item.bidCount === 1 ? '1 bid' : `${item.bidCount} bids`}
                  </span>
                  {item.statusId === 1 && item.endDate && (
                    <CountdownTimer endTime={item.endDate} size={12} className="text-xs" />
                  )}
                </div>
                <p className="text-xs text-ink-400 mt-1">
                  Added {item.addedAt ? new Date(item.addedAt).toLocaleDateString() : '—'}
                </p>
              </div>

              <div className="flex flex-col items-end gap-2 shrink-0">
                <span className={STATUS_CLASS[item.statusId] ?? 'badge-neutral'}>
                  {STATUS_LABEL[item.statusId] ?? 'Unknown'}
                </span>
                <div className="flex items-center gap-2">
                  <button
                    onClick={() => handleRemove(item.auctionId)}
                    title="Remove from watchlist"
                    className="p-2 rounded-lg text-ink-400 hover:text-red-500 hover:bg-red-50 transition-colors"
                  >
                    <Trash2 size={15} />
                  </button>
                  <Link to={`/auction/${item.auctionId}`} className="btn-primary btn-sm">
                    View
                  </Link>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
