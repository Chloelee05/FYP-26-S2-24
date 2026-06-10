import { useState, useEffect } from 'react';
import { Trash2 } from 'lucide-react';
import { Link } from 'react-router-dom';
import { getWatchlist, removeFromWatchlist } from '../api/auction';
import { formatCurrency } from '../utils/helpers';
import CountdownTimer from '../components/CountdownTimer';

// statusId: 1=ACTIVE, 2=FINISHED, 3=CANCELLED, 4=PENDING
const STATUS_LABEL = { 1: 'Active', 2: 'Ended', 3: 'Cancelled', 4: 'Pending' };
const STATUS_CLASS  = {
  1: 'bg-green-100 text-green-600',
  2: 'bg-gray-100 text-gray-500',
  3: 'bg-red-100 text-red-500',
  4: 'bg-yellow-100 text-yellow-600',
};

export default function Watchlist() {
  const [items, setItems] = useState([]);

  useEffect(() => {
    getWatchlist().then(r => setItems(r.data ?? [])).catch(() => {});
  }, []);

  const handleRemove = async (auctionId) => {
    try {
      await removeFromWatchlist(auctionId);
      setItems(prev => prev.filter(i => i.auctionId !== auctionId));
    } catch {}
  };

  return (
    <div className="max-w-4xl mx-auto px-4 py-8">
      <h1 className="text-2xl font-bold text-gray-900 mb-6">My Watchlist</h1>

      {items.length === 0 ? (
        <div className="text-center py-16 text-gray-400">
          <p className="text-4xl mb-3">❤️</p>
          <p>Your watchlist is empty.</p>
          <Link to="/search" className="mt-3 inline-block text-blue-500 underline">Browse auctions</Link>
        </div>
      ) : (
        <div className="space-y-3">
          {items.map(item => (
            <div key={item.auctionId} className="card p-4 flex items-center gap-4">
              <div className="w-20 h-16 rounded-lg bg-gray-100 flex items-center justify-center text-3xl shrink-0">
                🏷
              </div>
              <div className="flex-1 min-w-0">
                <Link to={`/auction/${item.auctionId}`} className="font-bold text-gray-900 hover:text-blue-500 text-sm">
                  {item.title}
                </Link>
                <div className="flex flex-wrap items-center gap-x-4 gap-y-1 mt-1">
                  <span className="text-xs text-gray-500">
                    Current bid: <strong className="text-gray-800">{formatCurrency(item.currentBid)}</strong>
                  </span>
                  <span className="text-xs text-gray-400">
                    {item.bidCount === 1 ? '1 bid' : `${item.bidCount} bids`}
                  </span>
                  {item.statusId === 1 && item.endDate && (
                    <span className="text-xs">
                      <CountdownTimer endTime={item.endDate} />
                    </span>
                  )}
                </div>
                <p className="text-xs text-gray-400 mt-0.5">
                  Added {item.addedAt ? new Date(item.addedAt).toLocaleDateString() : '—'}
                </p>
              </div>
              <div className="flex flex-col items-end gap-2 shrink-0">
                <span className={`px-2 py-0.5 rounded text-xs font-medium ${STATUS_CLASS[item.statusId] ?? 'bg-gray-100 text-gray-500'}`}>
                  {STATUS_LABEL[item.statusId] ?? 'Unknown'}
                </span>
                <Link to={`/auction/${item.auctionId}`} className="bg-blue-500 text-white px-4 py-1.5 rounded-lg text-xs font-medium hover:bg-blue-600">
                  View
                </Link>
                <button onClick={() => handleRemove(item.auctionId)} className="text-gray-400 hover:text-red-400 transition-colors">
                  <Trash2 size={14} />
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
