import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { Star } from 'lucide-react';
import { getBiddingHistory } from '../api/auction';
import { formatCurrency } from '../utils/helpers';
import CountdownTimer from '../components/CountdownTimer';

// Backend BidHistoryRow fields: auctionId, itemTitle, bidAmount, bidTime, auctionStatus ("Live"/"Ended"), won (boolean)
const deriveStatus = (row) => {
  if (row.auctionStatus === 'Ended') return row.won ? 'won' : 'lost';
  return 'active';
};

const STATUS_BADGE = {
  won: 'bg-blue-100 text-blue-600',
  lost: 'bg-gray-100 text-gray-500',
  active: 'bg-green-100 text-green-600',
};

export default function BiddingHistory() {
  const [history, setHistory] = useState([]);
  const [filter, setFilter] = useState('all');

  useEffect(() => {
    getBiddingHistory()
      .then(r => {
        const raw = r.data.bids ?? r.data ?? [];
        // Deduplicate by auctionId keeping the highest bid per auction
        const byId = {};
        raw.forEach(h => {
          if (!byId[h.auctionId] || h.bidAmount > byId[h.auctionId].bidAmount) {
            byId[h.auctionId] = h;
          }
        });
        setHistory(Object.values(byId));
      })
      .catch(() => {});
  }, []);

  const filtered = history.filter(h => {
    if (filter === 'all') return true;
    return deriveStatus(h) === filter;
  });

  return (
    <div className="max-w-4xl mx-auto px-4 py-8">
      <h1 className="text-2xl font-bold text-gray-900 mb-6">Bidding History</h1>

      <div className="flex gap-2 mb-6 flex-wrap">
        {['all', 'active', 'won', 'lost'].map(f => (
          <button
            key={f}
            onClick={() => setFilter(f)}
            className={`px-4 py-2 rounded-full text-sm font-medium capitalize transition-colors ${filter === f ? 'bg-blue-500 text-white' : 'bg-gray-100 text-gray-600 hover:bg-gray-200'}`}
          >
            {f}
          </button>
        ))}
      </div>

      <div className="space-y-3">
        {filtered.map((item) => {
          const status = deriveStatus(item);
          const isLive = item.auctionStatus === 'Live';
          return (
            <div key={item.auctionId} className="card p-4 flex items-center gap-4">
              <div className="w-16 h-16 rounded-lg bg-gray-100 flex items-center justify-center text-2xl shrink-0">
                🏷
              </div>
              <div className="flex-1">
                <Link to={`/auction/${item.auctionId}`} className="font-bold text-gray-900 hover:text-blue-500 text-sm">
                  {item.itemTitle}
                </Link>
                <div className="flex gap-4 mt-1 text-xs text-gray-500">
                  <span>My Bid: <strong className="text-gray-700">{formatCurrency(item.bidAmount)}</strong></span>
                  {item.bidTime && <span>{new Date(item.bidTime).toLocaleDateString()}</span>}
                </div>
                {isLive && <div className="mt-1 text-xs text-green-600">Auction is live</div>}
              </div>
              <div className="flex flex-col items-end gap-2">
                <span className={`px-2 py-0.5 rounded text-xs font-medium capitalize ${STATUS_BADGE[status] || 'bg-gray-100 text-gray-500'}`}>
                  {status}
                </span>
                {isLive && (
                  <Link to={`/auction/${item.auctionId}`} className="text-xs text-blue-500 underline">Bid Again</Link>
                )}
                {status === 'won' && (
                  <Link
                    to={`/rate-seller/${item.auctionId}`}
                    className="flex items-center gap-1 text-xs text-yellow-500 hover:text-yellow-600 font-medium"
                  >
                    <Star size={13} /> Rate Seller
                  </Link>
                )}
              </div>
            </div>
          );
        })}

        {filtered.length === 0 && (
          <div className="text-center py-16 text-gray-400">No bids in this category.</div>
        )}
      </div>
    </div>
  );
}
