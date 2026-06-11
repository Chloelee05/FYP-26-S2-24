import { Link } from 'react-router-dom';
import { Clock } from 'lucide-react';
import { formatCurrency, timeRemaining } from '../utils/helpers';
import { publicPath } from '../utils/appBase';

export default function AuctionCard({ auction }) {
  const {
    auctionId, title, currentPrice, endDate, thumbnailUrl, sellerUsername,
  } = auction;

  return (
    <div className="card overflow-hidden hover:shadow-md transition-shadow">
      <div className="aspect-square bg-gray-100 overflow-hidden">
        {thumbnailUrl
          ? <img src={publicPath(thumbnailUrl)} alt={title} className="w-full h-full object-cover" />
          : <div className="w-full h-full flex items-center justify-center text-gray-400 text-sm">No image</div>
        }
      </div>
      <div className="p-4">
        <h3 className="font-bold text-sm text-gray-900 leading-tight mb-1 line-clamp-2">{title}</h3>
        {sellerUsername && <p className="text-xs text-gray-400 mb-2">Seller: {sellerUsername}</p>}
        <div className="flex items-center gap-1 text-xs text-gray-500 mb-1">
          <Clock size={12} />
          <span>End in: {timeRemaining(endDate)}</span>
        </div>
        <p className="text-xs text-gray-500 mb-1">Current Bid</p>
        <p className="font-bold text-gray-900">{formatCurrency(currentPrice)}</p>
        <Link
          to={`/auction/${auctionId}`}
          className="mt-3 block w-full text-center bg-blue-500 hover:bg-blue-600 text-white text-sm font-medium py-2 rounded-lg transition-colors"
        >
          BID NOW
        </Link>
      </div>
    </div>
  );
}
