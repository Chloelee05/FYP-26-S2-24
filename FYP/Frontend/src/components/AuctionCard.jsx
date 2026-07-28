import { Link } from 'react-router-dom';
import { Clock, ImageIcon } from 'lucide-react';
import { formatCurrency, timeRemaining } from '../utils/helpers';
import { publicPath } from '../utils/appBase';

/** Auctions closing within this window get the urgent (amber) treatment. */
const URGENT_MS = 6 * 60 * 60 * 1000;

export default function AuctionCard({ auction }) {
  const {
    auctionId, title, currentPrice, endDate, thumbnailUrl, sellerUsername, category,
  } = auction;

  const msLeft = endDate ? new Date(endDate) - new Date() : null;
  const ended = msLeft != null && msLeft <= 0;
  const urgent = msLeft != null && msLeft > 0 && msLeft < URGENT_MS;

  return (
    <div className="card card-hover group overflow-hidden flex flex-col">
      <Link to={`/auction/${auctionId}`} className="block relative aspect-square bg-ink-100 overflow-hidden">
        {thumbnailUrl ? (
          <img
            src={publicPath(thumbnailUrl)}
            alt={title}
            loading="lazy"
            className="w-full h-full object-cover transition-transform duration-500 group-hover:scale-[1.06]"
          />
        ) : (
          <div className="w-full h-full flex flex-col items-center justify-center gap-1.5 text-ink-300 bg-gradient-to-br from-ink-100 to-ink-200">
            <ImageIcon size={26} />
            <span className="text-xs font-medium">No image</span>
          </div>
        )}

        {/* Time status chip */}
        <span
          className={`absolute top-2.5 left-2.5 inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-[11px] font-semibold backdrop-blur-md shadow-sm ${
            ended
              ? 'bg-ink-900/75 text-white'
              : urgent
                ? 'bg-accent-500/95 text-white'
                : 'bg-white/90 text-ink-700'
          }`}
        >
          <Clock size={11} />
          {ended ? 'Ended' : timeRemaining(endDate)}
        </span>

        {category && (
          <span className="absolute top-2.5 right-2.5 rounded-full bg-white/90 backdrop-blur-md px-2.5 py-1 text-[11px] font-semibold text-ink-600 shadow-sm">
            {category}
          </span>
        )}
      </Link>

      <div className="p-4 flex flex-col flex-1">
        <Link to={`/auction/${auctionId}`} className="block">
          <h3 className="font-semibold text-sm text-ink-900 leading-snug line-clamp-2 group-hover:text-primary-600 transition-colors">
            {title}
          </h3>
        </Link>
        {sellerUsername && (
          <p className="text-xs text-ink-400 mt-1 truncate">by {sellerUsername}</p>
        )}

        <div className="mt-auto pt-3">
          <p className="text-[11px] font-semibold uppercase tracking-wide text-ink-400">Current bid</p>
          <p className="text-lg font-bold text-ink-900 tabular-nums">{formatCurrency(currentPrice)}</p>
          <Link
            to={`/auction/${auctionId}`}
            className="btn-primary btn-block mt-3 text-xs uppercase tracking-wide"
          >
            {ended ? 'View Result' : 'Bid Now'}
          </Link>
        </div>
      </div>
    </div>
  );
}
