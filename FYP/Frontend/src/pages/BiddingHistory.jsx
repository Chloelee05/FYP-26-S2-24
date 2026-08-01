import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { Star, Package, Gavel, Check } from 'lucide-react';
import { getBiddingHistory } from '../api/auction';
import { formatCurrency } from '../utils/helpers';
import { publicPath } from '../utils/appBase';

// Backend BidHistoryRow fields: auctionId, itemTitle, bidAmount, bidTime, auctionStatus ("Live"/"Ended"), won (boolean)
const deriveStatus = (row) => {
  if (row.auctionStatus === 'Ended') return row.won ? 'won' : 'lost';
  return 'active';
};

const STATUS_BADGE = {
  won: 'badge-info',
  lost: 'badge-neutral',
  active: 'badge-success',
};

const FILTERS = ['all', 'active', 'won', 'lost'];

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

  const countFor = (f) => (f === 'all' ? history.length : history.filter(h => deriveStatus(h) === f).length);

  return (
    <div className="max-w-4xl mx-auto px-4 py-8">
      <h1 className="page-title">Bidding History</h1>
      <p className="page-subtitle mb-6">Every auction you’ve placed a bid on.</p>

      <div className="flex gap-2 mb-6 flex-wrap">
        {FILTERS.map(f => (
          <button
            key={f}
            onClick={() => setFilter(f)}
            className={`tab-pill capitalize ${filter === f ? 'tab-pill-active' : ''}`}
          >
            {f}
            <span className={`ml-1.5 text-xs ${filter === f ? 'text-white/60' : 'text-ink-400'}`}>{countFor(f)}</span>
          </button>
        ))}
      </div>

      <div className="space-y-3">
        {filtered.map((item) => {
          const status = deriveStatus(item);
          const isLive = item.auctionStatus === 'Live';
          return (
            <div key={item.auctionId} className="card card-hover p-4 flex items-center gap-4">
              <Link
                to={`/auction/${item.auctionId}`}
                className="w-16 h-16 rounded-xl bg-ink-50 grid place-items-center text-ink-400 shrink-0 overflow-hidden"
              >
                {item.thumbnailUrl ? (
                  <img
                    src={publicPath(item.thumbnailUrl)}
                    alt=""
                    loading="lazy"
                    className="w-full h-full object-contain p-1"
                  />
                ) : (
                  <Package size={22} />
                )}
              </Link>

              <div className="flex-1 min-w-0">
                <Link to={`/auction/${item.auctionId}`} className="font-bold text-ink-900 hover:text-primary-600 text-sm transition-colors line-clamp-1">
                  {item.itemTitle}
                </Link>
                <div className="flex flex-wrap gap-x-4 gap-y-1 mt-1.5 text-xs text-ink-500">
                  <span>My bid <strong className="text-ink-900 tabular-nums">{formatCurrency(item.bidAmount)}</strong></span>
                  {item.bidTime && <span>{new Date(item.bidTime).toLocaleDateString()}</span>}
                </div>
                {isLive && (
                  <p className="flex items-center gap-1.5 mt-1.5 text-xs font-semibold text-emerald-600">
                    <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 animate-pulse-dot" /> Auction is live
                  </p>
                )}
              </div>

              <div className="flex flex-col items-end gap-2 shrink-0">
                <span className={`${STATUS_BADGE[status] || 'badge-neutral'} capitalize`}>{status}</span>
                {isLive && (
                  <Link to={`/auction/${item.auctionId}`} className="btn-primary btn-sm">
                    <Gavel size={13} /> Bid again
                  </Link>
                )}
                {/* Rating is one-per-auction, so the invite disappears once it is used
                    rather than sending the buyer to a page that only says "already rated". */}
                {status === 'won' && (item.rated ? (
                  <span className="inline-flex items-center gap-1 text-xs font-medium text-ink-400">
                    <Check size={13} /> Rated
                  </span>
                ) : (
                  <Link
                    to={`/rate-seller/${item.auctionId}`}
                    className="inline-flex items-center gap-1 text-xs font-semibold text-amber-600 hover:text-amber-700 transition-colors"
                  >
                    <Star size={13} /> Rate seller
                  </Link>
                ))}
              </div>
            </div>
          );
        })}

        {filtered.length === 0 && (
          <div className="card p-16 text-center">
            <span className="grid place-items-center w-14 h-14 rounded-2xl bg-ink-100 text-ink-400 mx-auto mb-4">
              <Gavel size={24} />
            </span>
            <p className="font-semibold text-ink-800">No bids in this category</p>
            <p className="text-sm text-ink-500 mt-1">Once you bid on an auction it shows up here.</p>
            <Link to="/search" className="btn-primary mt-6">Find auctions to bid on</Link>
          </div>
        )}
      </div>
    </div>
  );
}
